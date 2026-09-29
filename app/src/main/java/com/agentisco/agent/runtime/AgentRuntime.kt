package com.agentisco.agent.runtime

import com.agentisco.agent.compact.CLEARED_TOOL_RESULT_PLACEHOLDER
import com.agentisco.agent.compact.CompactBoundary
import com.agentisco.agent.compact.CompactCoordinator
import com.agentisco.agent.compact.CompactPolicy
import com.agentisco.agent.compact.CompactPolicyConfig
import com.agentisco.agent.compact.CompactReason
import com.agentisco.agent.compact.CompactSummaryContext
import com.agentisco.agent.compact.CompactTokenMeter
import com.agentisco.agent.compact.ContextTokenUsage
import com.agentisco.agent.compact.ManualCompact
import com.agentisco.agent.compact.buildCompactSummaryMessage
import com.agentisco.agent.compact.estimateMessageTokens
import com.agentisco.agent.compact.groupByAssistantStartedRounds
import com.agentisco.agent.llm.LlmErrorKind
import com.agentisco.agent.llm.LlmException
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmToolCall
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.PlanMode
import com.agentisco.agent.tool.SubagentOutcome
import com.agentisco.agent.tool.SubagentTool
import com.agentisco.agent.tool.ToolContext
import com.agentisco.agent.tool.ToolArgumentError
import com.agentisco.agent.tool.ToolResult
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectFile
import com.agentisco.data.model.TerminalSession
import com.agentisco.data.repository.ChatHistoryMessage
import com.agentisco.data.repository.SessionCompaction
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Drives the agent loop: LLM request → (streamed) response → model-requested
 * tool calls → validation → permission policy → real tool execution (parallel
 * for batched calls) → tool results back to the model → repeat, until a final
 * answer or the iteration cap.
 *
 * Everything the UI shows comes from the [AgentStreamEvent]s emitted here —
 * there is no simulated activity. The model never executes tools itself and
 * never sees provider credentials.
 *
 * - Transient LLM failures (network, timeout, provider 5xx/429) are retried
 *   automatically up to [MAX_LLM_ATTEMPTS] times, resuming the exact pending
 *   request rather than restarting the task.
 * - The model may batch several tool calls in one response; they execute
 *   concurrently (bounded) and all results are sent back in a single round trip.
 * - The transcript sent to the provider is compressed by the two-tier
 *   [CompactCoordinator] (local tool-result clearing, then an LLM summary)
 *   while the persisted chat keeps every message the user has seen.
 */
class AgentRuntime(
  private val fileSystem: com.agentisco.workspace.filesystem.ProjectFileSystem,
  private val terminalManager: com.agentisco.workspace.terminal.TerminalProcessManager,
  private val gitManager: com.agentisco.workspace.git.GitRepositoryManager,
  private val llmService: LlmService,
  private val toolRegistry: AgentToolRegistry,
  /**
   * The workspace's live team board. Non-null for every run in a turn, including
   * delegated ones, so an agent is told which files another agent already holds
   * before it opens one. Null means no team awareness (a lone runtime, or a unit
   * test) and changes nothing else about the run.
   */
  private val teamBoard: AgentTeamBoard? = null,
  /**
   * Reads the `SKILL.md` folders, for the index every run is shown. The default has
   * no app context, so a unit test only ever sees a project's own skills.
   */
  private val skillStore: com.agentisco.agent.skill.SkillStore =
    com.agentisco.agent.skill.SkillStore(),
  /**
   * Compaction sink for the run in progress. Null (the default, and what unit
   * tests use) disables compaction entirely, so the runtime behaves exactly as
   * it did before the two-tier system existed.
   */
  private val compactSinkProvider: () -> CompactSink? = { null }
) {

  /**
   * Where the runtime reports compaction back to. Implemented by the chat
   * store; the runtime itself never touches persistence.
   */
  interface CompactSink {
    /**
     * Records a compaction. [summarizedThroughRowId] is the last persisted
     * message rowId folded into the summary: its text stays in the chat, but
     * from now on the session sends the summary instead of those messages.
     */
    suspend fun onCompacted(
      sessionId: String,
      summary: String,
      boundary: CompactBoundary,
      summarizedThroughRowId: Long,
      keptFromRowId: Long
    )

    /** The session's most recent compaction, when one exists. */
    suspend fun latestCompaction(sessionId: String): SessionCompaction?
  }

  private companion object {
    const val TAG = "AgentiscoAgent"
    const val MAX_LLM_ATTEMPTS = 5
    const val MAX_PARALLEL_TOOLS = 4
    /** Streamed characters between two live context-usage updates. */
    const val USAGE_EMIT_INTERVAL_CHARS = 1_000

    /**
     * The tool playbook sent with every run. Models call what they have been
     * shown: naming each tool and when to reach for it is what turns a tool
     * list into a working method, and the editing rules below are the ones the
     * file tools are built to enforce.
     */
    val TOOL_PLAYBOOK = """
Tools available:
  Explore   glob_files (names), search_files (fixed text), regex_search (patterns),
           list_files, directory_tree, file_info, read_file, read_files
  Team     delegate (hand a slice of work to another agent: explore, uiux, frontend,
           backend, debugger, qa, security, general)
  Change   edit_files (several exact-snippet edits, one call), edit_file,
           write_file (a whole new or replaced file), create_file, create_directory,
           move_file, copy_file, delete_file
  Run      run_command (Linux shell in the workspace), terminal_output,
           write_terminal_input (answer a prompt), interrupt_terminal, build, test
  Git      git_status, git_diff, git_log, git_show, git_stage, git_commit
  Other    web_search (find documentation or an issue for an error), web_fetch (read a
           URL as text), ask_user (let the user choose), task_plan (share a step-by-step plan)

Method:
  1. Understand first: locate with glob_files / search_files, then read_file the code you
     will touch and its callers. Never edit a file you have not read in this conversation.
  2. Delegate when the work would burn more files than it is worth here or belongs to a
     specialist: call delegate with the narrowest role that fits. explore only reads and
     reports; every other role implements, runs and tests in this same workspace. A
     delegated agent sees none of this history, cannot delegate again, and a question it
     asks halts the whole turn until the user answers, so the brief must state the goal,
     starting paths, what is already done and what the report must contain. Agents may run
     side by side: give each a slice with no file overlap (each is shown which files are
     already held) and expect a report back, not the files.
  3. Edit surgically: use edit_files for any change inside an existing file, with every
     edit of one change in a single call. old_string must be copied verbatim from
     read_file output, indentation included, and be unique unless replace_all is true.
     Use write_file only for new files or a genuine full rewrite - a partial write_file
     silently deletes the rest of the file.
  4. Verify every change: build / test / run_command, then read the output. On failure,
     diagnose the root cause, fix it and re-run until green. Never report unfinished or
     unverified work as done, and never leave the workspace in a broken state.
  5. Long-running commands (dev server, watch, large suites): run_command with
     run_in_background true, poll with terminal_output, stop with interrupt_terminal.
     A foreground command that hits its timeout is reported as stopped, not failed.
     If output stalls because the command is waiting for input, answer with
     write_terminal_input using the same runner_id - never start a second copy.
  6. Research instead of guessing: for unfamiliar library behaviour, unexplained errors
     or uncertain config, web_search, then web_fetch the authoritative page. Never cite a
     URL you have not read and never invent one.
  7. Outputs can be truncated and say so ("[…truncated: showing N of M characters]",
     "lines x..y not shown"). Never assume you saw a whole file: re-read the missing range
     with start_line / max_lines, and never fabricate what was cut off.
  8. Paths are workspace-relative; you cannot read, write or delete outside the project.

Habits:
  - Batch every independent call into one response; wait only when a call needs an
    earlier result.
  - Persist until the task is fully done. When a real decision belongs to the user
    (ambiguous requirement, destructive choice, two viable designs), call ask_user with
    2-4 concrete options rather than guessing or writing a question in prose. Two
    options is enough - a question with one option is an answer you should just give.
  - A refusal may come with the user's reason. That reason is your instruction: act on
    it instead of repeating the request in different words.
  - Anything the user must approve (protected commands, file deletion) opens a dialog.
    A denial is the user's decision: adapt to it and to the reason they typed, and never
    retry the refused action.
  - Finish with a plain-text response and NO tool calls: what you did, which files
    changed, and the verified outcome. That summary is the answer the user reads.

    """.trimIndent()

    /**
     * Sent only while the user has plan mode on. The runtime gate does the
     * refusing; this exists so the model plans instead of probing for a way
     * around the gate.
     */
    val PLAN_MODE_ADDENDUM = """
Plan mode is ON for this turn: the user wants a plan, not changes.
  - Investigate thoroughly with the read-only tools (glob_files, search_files, read_file,
    file_info, git_status, git_diff, web_search, web_fetch) and read-only shell
    commands (ls, cat, head, grep, find, wc).
  - Every tool that would change something (write_file, create_file, edit_file,
    edit_files, delete_file, move_file, copy_file, create_directory, git_stage,
    git_commit, build, test, and any other run_command) is refused by the app.
    A refusal is final: do not retry, rephrase or work around it.
  - Settle the whole change before answering: which files, exactly what changes in
    each, the order of work, how every step will be verified, and the risks.
  - Present it with task_plan, then repeat the same plan as your final text answer and
    say you can implement it as soon as the user turns plan mode off.
  - If the request is ambiguous, call ask_user to resolve the decision so the plan you
    present is the one the user actually wants.
    """.trimIndent()
  }

  /** Two-tier compaction over the in-memory transcript of the current run. */
  private val compactor = CompactCoordinator(llmService) { activePolicy }
  private var activePolicy: CompactPolicyConfig = CompactPolicyConfig()
  private var usageListener: ((ContextTokenUsage) -> Unit)? = null
  private var usageCharsSinceEmit = 0

  /** Serializes approval requests when tools run concurrently. */
  private val approvalMutex = Mutex()
  private var pendingApprovalDeferred: CompletableDeferred<UserDecision>? = null
  /**
   * The dialog of the run in flight, so a specialist it delegates to is handed this
   * same channel. One user watching one turn gets one queue of requests, and a
   * delegated run that reaches into it is asking in person instead of being
   * refused on the user's behalf.
   */
  private var activeApprovalChannel: (suspend (PendingApproval) -> UserDecision)? = null
  private val toolParallelism = Semaphore(MAX_PARALLEL_TOOLS)

  /** Tool calls the user SIGKILLed; keyed by the model's call id. */
  private val userCancelledCalls: MutableSet<String> =
    java.util.Collections.synchronizedSet(HashSet())
  /**
   * Approvals the user ended the turn on, keyed by approval id. A tool reporting
   * the outcome reads this to distinguish "the user refused" from "the turn was
   * stopped before the user decided".
   */
  private val terminatedApprovals: MutableMap<String, Boolean> =
    java.util.Collections.synchronizedMap(HashMap())
  /**
   * The reason the user typed for a decision, keyed by approval id, so a tool
   * can report "no, because…" instead of a bare refusal.
   */
  private val approvalRationales: MutableMap<String, String> =
    java.util.Collections.synchronizedMap(HashMap())
  /** Awaits the user's retry/continue decision for a cancelled tool call. */
  private val toolCancelDecisions =
    java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

  /**
   * What the user answered: permission granted or refused, plus the free-text or
   * chosen option for a question.
   *
   * [termination] marks a decision the user never made: the turn was stopped
   * (Stop/Pause) while the request was open, so the tool can no longer run. It
   * is recorded as "stalled" rather than "denied" because refusing would put a
   * choice in the user's mouth that they did not make.
   */
  data class UserDecision(
    val allowed: Boolean,
    val answer: String? = null,
    val termination: Boolean = false
  ) {
    /** Convenience for the tool-context adapter. */
    internal val approved: Boolean get() = !termination && allowed

    /**
     * The reason the user typed alongside the decision — a denial's "why". Null
     * when they picked a button without explaining.
     */
    internal var rationale: String? = null
  }

  fun resolvePendingApproval(allowed: Boolean, answer: String? = null, termination: Boolean = false) {
    resolvePendingApproval(allowed, answer, rationale = null, termination = termination)
  }

  /**
   * Applies the user's decision. [rationale] is the free text the user typed
   * alongside it — a denial's "why" — which the tools surface to the model in
   * place of a bare refusal.
   */
  fun resolvePendingApproval(allowed: Boolean, answer: String?, rationale: String?, termination: Boolean) {
    val deferred = pendingApprovalDeferred
    pendingApprovalDeferred = null
    deferred?.complete(UserDecision(allowed, answer, termination).apply { this.rationale = rationale })
  }

  /** SIGKILLs a specific running tool call; the loop then awaits user guidance. */
  fun cancelToolCall(callId: String) {
    if (callId.isBlank()) return
    userCancelledCalls.add(callId)
    com.agentisco.agent.tool.ToolCancellation.kill(callId)
  }

  /** Resolves a cancelled tool call: retry re-executes it, continue moves on. */
  fun resolveToolCancellation(callId: String, retry: Boolean) {
    toolCancelDecisions.remove(callId)?.complete(retry)
  }

  suspend fun executeTask(
    prompt: String,
    project: Project,
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    permissions: () -> AgentPermissions,
    terminalSession: TerminalSession,
    /**
     * Chat session identity, forwarded as the LLM conversation key. Stateful
     * protocols (Gemini Interactions) use it to resume their server-side chain
     * across turns and app restarts; stateless protocols ignore it.
     */
    sessionId: String? = null,
    history: List<ChatHistoryMessage> = emptyList(),
    resume: Boolean = false,
    /**
     * Set for a delegated run: the agent takes this role's responsibility, lane
     * and reporting duty, and the user-facing plan-mode wording does not apply to
     * it - there is no user watching this run to present a plan to.
     */
    role: com.agentisco.agent.model.AgentRole? = null,
    /**
     * Compaction budget for this run: the selected model's real context window
     * plus the user's Settings choices. Null derives it from the model.
     */
    compactPolicy: CompactPolicyConfig? = null,
    /** Fresh context-usage snapshots, for the composer's percent chip. */
    onTokenUsage: ((ContextTokenUsage) -> Unit)? = null,
    onRequestApproval: (PendingApproval) -> Unit,
    onEvent: (AgentStreamEvent) -> Unit,
    /**
     * How this run puts a request in front of the user. A delegated run is handed
     * the channel of the turn that delegated to it: one dialog serves the whole
     * turn, so a specialist's question — or a request the destructive guard insists
     * a human make — is asked and answered rather than refused on the user's
     * behalf by a run they were never shown. Null means this run owns the dialog.
     */
    approvalChannel: (suspend (PendingApproval) -> UserDecision)? = null
  ): AgentTaskResult = withContext(Dispatchers.IO) {
    onEvent(AgentStreamEvent.TaskStarted(prompt))

    // Pre-flight capability validation: never silently send tool calls a model can't honor.
    val useTools = model.capabilities.tools
    if (!useTools) {
      onEvent(AgentStreamEvent.Status("Selected model does not support tool calling — running in text-only mode."))
    }

    activePolicy = compactPolicy ?: CompactPolicyConfig.forModel(model.contextWindow, model.maxOutputTokens)
    usageListener = onTokenUsage
    usageCharsSinceEmit = 0
    compactor.resetCircuitBreaker()
    val sink = compactSinkProvider()
    // One dialog serves a turn, so a delegated run is handed this channel instead
    // of being left with nobody to ask.
    val askUserDecision: suspend (PendingApproval) -> UserDecision =
      approvalChannel ?: { approval -> awaitUserDecision(approval, onRequestApproval, onEvent) }
    activeApprovalChannel = askUserDecision
    val meter = CompactTokenMeter(activePolicy.contextWindow, activePolicy)

    // Declared before the transcript because the seat's file claims are this very
    // set: what other agents read is what this run has already changed.
    val modifiedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // The board is read, not joined, here: taking the seat after the prompt is
    // built is what keeps this run from listing itself as another agent, and it
    // means a run that fails before it starts has taken nothing to leave behind.
    val transcript = buildTranscript(
      project = project,
      useTools = useTools,
      sessionId = sessionId,
      history = history,
      sink = sink,
      prompt = prompt,
      resume = resume,
      planMode = permissions().planMode,
      role = role,
      teamActivity = teamBoard?.promptBlock(excludingId = null) ?: ""
    )
    val messages = transcript.messages
    val rowIds = transcript.rowIds

    /**
     * Replaces the transcript with a compressed one, keeping [rowIds] aligned.
     */
    fun replaceAll(newMessages: List<LlmMessage>, newRowIds: List<Long>) {
      messages.clear(); messages.addAll(newMessages)
      rowIds.clear(); rowIds.addAll(newRowIds)
    }

    meter.setBase(messages)
    emitUsage(meter, force = true)

    val maxIterations = permissions().maxToolIterations.coerceAtLeast(1)

    /**
     * Compresses the transcript before the next request.
     *
     * 1. Local tier: old tool-result payloads are replaced with a placeholder —
     *    no model call. The metadata in SQLite is untouched, so the chat keeps
     *    showing the real output.
     * 2. Model tier: when the transcript is still above the threshold, the
     *    older assistant-started rounds are summarized and replaced by that
     *    summary, keeping the newest rounds verbatim.
     */
    suspend fun compactTranscript(): Unit {
      // The transcript gained a whole round of tool output since the last
      // measurement, so the budget is re-read from the exact payload about to
      // be sent. Once per request — not once per streamed token.
      meter.setBase(messages)

      val micro = compactor.microcompact(messages, meter.snapshot().usedTokens)
      if (micro.applied) {
        replaceAll(micro.messages, rowIds.toList())
        meter.onMicrocompacted(micro.clearedResults, micro.savedTokens)
        onEvent(
          AgentStreamEvent.Status(
            "Cleared ${micro.clearedResults} old tool result(s) locally (saved ${micro.savedTokens} tokens); the chat still shows them in full."
          )
        )
        emitUsage(meter, force = true)
      }

      if (sink == null || sessionId == null) return
      val decision = CompactPolicy.evaluate(
        messages = messages,
        rounds = groupByAssistantStartedRounds(messages),
        config = activePolicy
      )
      if (!decision.shouldCompact) {
        hintIfClose(decision)
        return
      }
      summarizeRounds(
        transcript = transcript,
        decision = decision,
        ctx = CompactionContext(provider, model, apiKey, sessionId, sink, meter, null, onEvent)
      )
    }

    // Now this run is real: take the seat other agents are told about, with the
    // live file set as its claim.
    val seat = teamBoard?.join(
      role = role,
      task = prompt,
      delegated = role != null,
      files = modifiedFiles
    )
    // How the run ends is what the board reports, and every way it can end has to
    // close the seat - an open one keeps claiming files after the agent is gone.
    var seatStatus = AgentWorkStatus.DONE

    try {
      var finalText = ""
      taskLoop@ for (iteration in 1..maxIterations) {
        var attempt = 0
        while (true) {
          attempt++
          if (!currentCoroutineContext().isActive) throw CancellationException("Agent task cancelled")

          // Compress before sending: the previous iteration's tool results are
          // the biggest thing the transcript just gained.
          compactTranscript()

          val assistantText = StringBuilder()
          var completedMessage: LlmMessage? = null
          var failure: LlmException? = null

          try {
            llmService.streamChat(
              provider = provider,
              model = model,
              apiKey = apiKey,
              request = LlmRequest(
                messages = messages,
                tools = if (useTools) toolRegistry.specs() else emptyList(),
                maxOutputTokens = model.maxOutputTokens,
                conversationKey = sessionId
              )
            ) { event ->
              when (event) {
                is LlmStreamEvent.Started -> if (iteration == 1 && attempt == 1) onEvent(AgentStreamEvent.Status("Contacting ${provider.name} · ${model.displayName}…"))
                is LlmStreamEvent.Token -> {
                  assistantText.append(event.text)
                  onEvent(AgentStreamEvent.Token(event.text))
                  // The context chip must move with the stream, not only when a
                  // response finishes — that is the number the user watches.
                  meter.appendAssistantText(event.text)
                  usageCharsSinceEmit += event.text.length
                  if (usageCharsSinceEmit >= USAGE_EMIT_INTERVAL_CHARS) emitUsage(meter, force = true)
                }
                is LlmStreamEvent.ReasoningToken -> onEvent(AgentStreamEvent.ReasoningToken(event.text))
                is LlmStreamEvent.ToolCallRequested -> onEvent(AgentStreamEvent.Status("Model requested ${event.call.name}"))
                is LlmStreamEvent.Completed -> {
                  completedMessage = event.message
                  // The provider's own count is authoritative and includes the
                  // hidden reasoning tokens no local estimate can see, so the
                  // chip adopts it the moment the response lands.
                  meter.onResponseCompleted(event.message.usage)
                  emitUsage(meter, force = true)
                }
                is LlmStreamEvent.Interrupted -> failure = LlmException("Response stream was interrupted.", LlmErrorKind.CANCELLED)
                is LlmStreamEvent.Failed -> failure = event.error
              }
            }
          } catch (e: LlmException) {
            failure = e
          }

          val err = failure
          if (err != null && isTransient(err) && attempt < MAX_LLM_ATTEMPTS && currentCoroutineContext().isActive) {
            // Retry the exact pending request (same conversation state, same
            // tool results) after a backoff. Partial streamed text is kept in
            // the UI and the retry starts a fresh text block.
            onEvent(AgentStreamEvent.TextReset("retrying"))
            onEvent(
              AgentStreamEvent.Status(
                "Temporary error: ${err.message} — retrying (attempt ${attempt + 1}/$MAX_LLM_ATTEMPTS)…"
              )
            )
            // Wait longer between attempts — sleeping devices / flaky mobile
            // networks need more than one second to recover.
            delay(2000L * attempt)
            continue
          }
          err?.let { throw it }

          val message = completedMessage ?: LlmMessage(LlmRole.ASSISTANT, assistantText.toString())

          if (message.toolCalls.isEmpty() || !useTools) {
            finalText = message.content.ifBlank { assistantText.toString() }
            break@taskLoop // task complete — do NOT start another request
          }

          // Model requested tools: validate, enforce permissions, execute for
          // real (batched calls run concurrently), then feed structured results
          // back to the model. The assistant message echoed into history carries
          // normalized canonical calls (real ids, names, and arguments as valid
          // JSON object strings) so the next request is always well-formed.
          val canonicalCalls = message.toolCalls.map {
            it.copy(
              id = it.id.ifBlank { "call_${it.hashCode()}" },
              name = it.name.trim(),
              argumentsJson = normalizeArgsJson(it.argumentsJson)
            )
          }
          messages.add(message.copy(toolCalls = canonicalCalls))
          rowIds.add(0L)

          // Announce every call up front, in the model's own order, so the UI
          // shows the batch in a deterministic order (not execution order).
          canonicalCalls.forEach { call ->
            onEvent(AgentStreamEvent.ToolStarted(call.name, call.argumentsJson, call.id))
          }

          val results: List<Pair<LlmToolCall, ToolResult>> = coroutineScope {
            canonicalCalls.map { call ->
              async {
                val outcome: Pair<LlmToolCall, ToolResult> = toolParallelism.withPermit {
                  if (!currentCoroutineContext().isActive) {
                    call to ToolResult(success = false, error = "Agent task cancelled")
                  } else {
                    call to executeToolCall(call, project, permissions(), terminalSession, askUserDecision, onEvent, modifiedFiles)
                  }
                }
                outcome
              }
            }.awaitAll()
          }

          // Tool results go back as structured TOOL messages, in call order,
          // so a batched round trip costs exactly one request.
          for ((call, result) in results) {
            messages.add(
              LlmMessage(
                LlmRole.TOOL,
                listOfNotNull(
                  result.output.takeIf { it.isNotBlank() },
                  result.error?.let { "ERROR: $it" },
                  result.exitCode?.let { "exit code: $it" }
                ).joinToString("\n").ifBlank { "(no output)" },
                toolCallId = call.id,
                toolName = call.name,
                isError = !result.success
              )
            )
            rowIds.add(0L)
          }
          if (iteration == maxIterations) {
            finalText = message.content.ifBlank { "Stopped after $maxIterations tool iterations." }
            break@taskLoop
          }
          break // next iteration: request a new model response
        }
      }

      onEvent(AgentStreamEvent.Completed(finalText.ifBlank { "Task completed." }))
      AgentTaskResult(
        success = true,
        summary = finalText.take(500).ifBlank { "Task completed." },
        modifiedFiles = modifiedFiles.toList()
      )
    } catch (e: CancellationException) {
      seatStatus = AgentWorkStatus.CANCELLED
      onEvent(AgentStreamEvent.Cancelled())
      throw e
    } catch (e: LlmException) {
      if (e.kind == LlmErrorKind.CANCELLED) {
        seatStatus = AgentWorkStatus.CANCELLED
        onEvent(AgentStreamEvent.Cancelled("Generation stopped"))
        AgentTaskResult(success = false, summary = "Cancelled", modifiedFiles = modifiedFiles.toList())
      } else {
        seatStatus = AgentWorkStatus.FAILED
        onEvent(AgentStreamEvent.Failed(e.message ?: "LLM request failed"))
        AgentTaskResult(success = false, summary = e.message ?: "LLM request failed", modifiedFiles = modifiedFiles.toList())
      }
    } catch (e: Exception) {
      seatStatus = AgentWorkStatus.FAILED
      val reason = "Agent failed: ${e.message ?: e.javaClass.simpleName}"
      onEvent(AgentStreamEvent.Failed(reason))
      AgentTaskResult(success = false, summary = reason, modifiedFiles = modifiedFiles.toList())
    } finally {
      activeApprovalChannel = null
      seat?.finish(seatStatus)
    }
  }

  /**
   * Runs one delegated task in a fresh runtime over the same workspace and
   * returns only its report. The [role] decides what the child is allowed to be:
   * a research role gets [AgentToolRegistry.forDelegation]'s planning set and the
   * plan-mode gate, every other role works with the real tools under exactly the
   * permissions the user gave the agent that delegated to it.
   *
   * It has no chat session and no compaction sink: nothing it does is persisted,
   * and cancelling the parent turn cancels this call with the coroutine it runs
   * inside. It does share the parent's [AgentTeamBoard], which is how two agents
   * delegated in one batch each know the other is in the same files.
   *
   * [delegationId] is the parent's `delegate` call id, and every event the child
   * produces is tagged with it — the specialist's work belongs to that card.
   */
  suspend fun runSubagent(
    role: com.agentisco.agent.model.AgentRole,
    description: String,
    prompt: String,
    project: Project,
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    parentPermissions: AgentPermissions,
    terminalSession: TerminalSession,
    delegationId: String = "",
    onEvent: (AgentStreamEvent) -> Unit = {}
  ): SubagentOutcome {
    val child = AgentRuntime(
      fileSystem,
      terminalManager,
      gitManager,
      llmService,
      toolRegistry.forDelegation(role),
      // The same board, so the specialist is told what its siblings are holding
      // and every run it starts in turn is counted beside them.
      teamBoard,
      // The same skills: a specialist working in this repo reads the same files.
      skillStore
    ) { null }
    // This turn's dialog, if the user is watching one: the specialist's requests go
    // through it, so it can ask rather than be answered for.
    val parentChannel = activeApprovalChannel
    val result = child.executeTask(
      prompt = "${role.name} task (${description.ifBlank { "delegated work" }}): $prompt",
      role = role,
      project = project,
      provider = provider,
      model = model,
      apiKey = apiKey,
      permissions = { SubagentTool.childPermissions(role, parentPermissions) },
      terminalSession = terminalSession,
      // The specialist speaks to the user through this turn's one dialog: what it
      // asks is shown as its own question, and the answer comes from the user
      // rather than being decided for them. A run with no parent turn in flight
      // (a test, a call outside a turn) has no dialog to borrow, so its requests
      // are answered by the fallback below instead of hanging.
      approvalChannel = parentChannel?.let { channel ->
        { approval ->
          channel(
            approval.copy(
              title = if (approval.isQuestion) "${role.name} has a question" else "${role.name}: ${approval.title}"
            )
          )
        }
      },
      // Nothing reached here got through to the user: record it as a request that
      // was never made, which is what the tool then reports to the model.
      onRequestApproval = { child.resolvePendingApproval(allowed = false, termination = true) },
      onEvent = { event -> relay(role, delegationId, event, onEvent) }
    )
    return SubagentOutcome(
      success = result.success,
      summary = result.summary,
      error = result.summary.takeIf { !result.success },
      modifiedFiles = result.modifiedFiles
    )
  }

  /**
   * Surfaces a delegated run as its own work: every action it takes travels to
   * the parent's stream tagged with the delegation that started it, so the chat
   * can render reads, edits and commands live inside that card instead of showing
   * only the report at the end.
   *
   * The run's own begin/end stay unsaid — the delegation card already carries
   * them — and each tool it starts also moves the turn's status line, so the
   * user always sees which specialist is working.
   */
  private fun relay(
    role: com.agentisco.agent.model.AgentRole,
    delegationId: String,
    event: AgentStreamEvent,
    onEvent: (AgentStreamEvent) -> Unit
  ) {
    when (event) {
      is AgentStreamEvent.ToolStarted -> {
        onEvent(AgentStreamEvent.Status("${role.name}: ${event.name}"))
        onEvent(AgentStreamEvent.DelegationActivity(delegationId, role.name, event))
      }
      is AgentStreamEvent.Status -> onEvent(AgentStreamEvent.Status("${role.name}: ${event.text}"))
      is AgentStreamEvent.TaskStarted, is AgentStreamEvent.Completed -> Unit
      else -> onEvent(AgentStreamEvent.DelegationActivity(delegationId, role.name, event))
    }
  }

  /**
   * Builds the transcript the provider will receive: the system prompt (workspace,
   * role, who else is working, playbook), a summary of any earlier compaction, the
   * persisted rows that compaction did not fold in, and finally the new user prompt.
   *
   * Compaction is deliberately invisible in the chat database — it only
   * removes messages from the *request*, so the conversation the user reads
   * stays complete.
   */
  private suspend fun buildTranscript(
    project: Project,
    useTools: Boolean,
    sessionId: String?,
    history: List<ChatHistoryMessage>,
    sink: CompactSink?,
    prompt: String,
    resume: Boolean,
    planMode: Boolean = false,
    role: com.agentisco.agent.model.AgentRole? = null,
    teamActivity: String = ""
  ): Transcript {
    val messages = mutableListOf<LlmMessage>()
    // Persisted rowId each message came from (0 = produced by this run), so a
    // compaction can record exactly which stored messages it folded in.
    val rowIds = mutableListOf<Long>()
    fun add(message: LlmMessage, rowId: Long = 0L) {
      messages.add(message)
      rowIds.add(rowId)
    }

    add(LlmMessage(LlmRole.SYSTEM, buildSystemPrompt(project, useTools, planMode, role, teamActivity)))

    val priorCompaction = sessionId?.let { sink?.latestCompaction(it) }
    val compactedThrough = priorCompaction?.summarizedThroughRowId ?: 0L
    if (priorCompaction != null) {
      add(
        LlmMessage(
          LlmRole.USER,
          buildCompactSummaryMessage(
            priorCompaction.summary,
            CompactSummaryContext(
              preservedRecentCount = priorCompaction.keptMessages,
              tokensBefore = priorCompaction.tokensBefore,
              tokensAfter = priorCompaction.tokensAfter,
              trigger = CompactReason.entries.firstOrNull {
                it.name.equals(priorCompaction.trigger, true)
              } ?: CompactReason.MANUAL,
              clearedToolResults = priorCompaction.clearedToolResults
            )
          )
        )
      )
    }
    for (m in history) {
      // Rows a previous compaction already summarized are represented by that
      // summary; re-sending them would undo the saving.
      if (m.rowId != 0L && m.rowId <= compactedThrough) continue
      when (m.role) {
        "user" -> add(LlmMessage(LlmRole.USER, m.content), m.rowId)
        "assistant" -> add(LlmMessage(LlmRole.ASSISTANT, m.content), m.rowId)
        "assistant_tool_call" -> add(
          LlmMessage(
            LlmRole.ASSISTANT, m.content,
            toolCalls = listOf(LlmToolCall(m.toolCallId ?: "call_resumed", m.toolName ?: "unknown", m.toolArgs ?: "{}"))
          ),
          m.rowId
        )
        "tool" -> add(
          LlmMessage(
            LlmRole.TOOL,
            if (m.cleared) CLEARED_TOOL_RESULT_PLACEHOLDER else m.content,
            toolCallId = m.toolCallId,
            toolName = m.toolName,
            isError = m.content.startsWith("ERROR:") || m.content.contains("User denied")
          ),
          m.rowId
        )
      }
    }
    // A resumed turn already ends with tool results / prior context in history;
    // only a fresh user prompt is appended here.
    if (!resume && prompt.isNotBlank()) {
      add(LlmMessage(LlmRole.USER, prompt))
    }
    return Transcript(messages, rowIds, compactedThrough)
  }

  /**
   * Mutable transcript, the persisted row each message came from, and the
   * rowId through which an earlier compaction already summarized.
   */
  private class Transcript(
    val messages: MutableList<LlmMessage>,
    val rowIds: MutableList<Long>,
    val compactedThrough: Long
  )

  /** Everything tier 2 needs beyond the transcript it compresses. */
  private class CompactionContext(
    val provider: AIProvider,
    val model: AIModel,
    val apiKey: String,
    val sessionId: String,
    val sink: CompactSink,
    val meter: CompactTokenMeter,
    val customInstructions: String?,
    val onEvent: (AgentStreamEvent) -> Unit
  )

  /**
   * Tier 2 over [transcript], in place: the older assistant-started rounds are
   * replaced by one summary message, the preserved tail keeps the persisted rows
   * it was built from, and the boundary is recorded through the sink so the next
   * turn rebuilds the compressed transcript instead of the full one.
   *
   * Returns the boundary when the transcript shrank, or null when nothing was
   * compacted — no material, nothing new since the last summary, or a failed
   * summary — in which case the caller keeps sending the history as it is.
   */
  private suspend fun summarizeRounds(
    transcript: Transcript,
    decision: com.agentisco.agent.compact.CompactDecision,
    ctx: CompactionContext
  ): CompactBoundary? {
    val messages = transcript.messages
    val rowIds = transcript.rowIds
    val meter = ctx.meter
    val forced = decision.reason == CompactReason.MANUAL
    val tokensBeforeLocal = estimateMessageTokens(messages)
    /** An on-demand pass must explain itself; an automatic one stays silent. */
    fun report(text: String) {
      if (forced) ctx.onEvent(AgentStreamEvent.Status(text))
    }

    val plan = ManualCompact.plan(messages, activePolicy)
    if (plan.summarizedRoundCount == 0) {
      report("Nothing to compact yet — this conversation has no earlier turns to summarize.")
      return null
    }
    val systemCount = messages.count { it.role == LlmRole.SYSTEM }
    // The plan keeps the trailing non-system messages of [messages] as-is.
    val keptTailCount = plan.keepRounds.sumOf { it.messages.size }
    val keepStart = (messages.size - keptTailCount).coerceIn(systemCount, messages.size)
    val summarizedThrough = rowIds.subList(0, keepStart).filter { it > 0L }.maxOrNull()
      ?: transcript.compactedThrough
    val keptFrom = rowIds.getOrNull(keepStart)?.takeIf { it > 0L } ?: summarizedThrough
    if (summarizedThrough <= transcript.compactedThrough) {
      report("Nothing to compact yet — everything older is already summarized.")
      return null
    } // nothing new to summarize

    val result = compactor.compactWithModel(
      plan = plan,
      provider = ctx.provider,
      model = ctx.model,
      apiKey = ctx.apiKey,
      tokensBefore = meter.snapshot().usedTokens,
      clearedToolResults = meter.snapshot().clearedToolResults,
      trigger = decision.reason,
      customInstructions = ctx.customInstructions,
      onProgress = { ctx.onEvent(AgentStreamEvent.Status(it)) }
    )
    if (result == null) {
      emitUsage(meter, force = true)
      return null
    }

    // A summary carries a fixed continuation preamble, so forcing a pass over a
    // short conversation can cost more than it saves. On demand that is reported
    // instead of recorded; the automatic pass only ever runs above the threshold.
    if (forced && estimateMessageTokens(result.messages) >= tokensBeforeLocal) {
      report("Nothing to compact yet — this conversation is too short for a summary to save anything.")
      return null
    }

    // Rebuild the parallel row map: the preserved tail still points at the
    // rows it was built from (system prompt and summary were never rows).
    val newRowIds = MutableList(result.messages.size) { 0L }
    for (offset in 0 until keptTailCount) {
      val source = keepStart + offset
      val target = systemCount + 1 + offset
      if (source < rowIds.size && target < newRowIds.size) newRowIds[target] = rowIds[source]
    }
    messages.clear(); messages.addAll(result.messages)
    rowIds.clear(); rowIds.addAll(newRowIds)
    meter.onCompacted(
      ContextTokenUsage(
        usedTokens = estimateMessageTokens(result.messages),
        contextWindow = activePolicy.contextWindow,
        thresholdTokens = activePolicy.thresholdTokens,
        clearedToolResults = result.boundary.clearedToolResults
      )
    )
    ctx.onEvent(AgentStreamEvent.ContextCompacted(result.boundary, summarizedThrough, result.summary))
    ctx.sink.onCompacted(ctx.sessionId, result.summary, result.boundary, summarizedThrough, keptFrom)
    // A stateful provider keeps the transcript on its own side and receives only
    // each new delta, so the summary would never land and the summarized turns
    // would stay billed. Reopening the chain sends the compressed history as the
    // conversation from now on.
    llmService.invalidateConversation(ctx.sessionId)
    emitUsage(meter, force = true)
    return result.boundary
  }

  /**
   * The user's explicit "compact now": run the model tier over the stored
   * conversation without asking the provider for a new answer.
   *
   * The transcript is rebuilt exactly as a turn would rebuild it — system prompt,
   * previous summary, uncompacted rows — so the plan, the row bookkeeping and the
   * recorded boundary match what the next turn will send. The persisted chat is
   * never rewritten; only what goes to the provider shrinks.
   *
   * Returns the boundary when the conversation shrank, null when there was
   * nothing to compact. Progress and failures are reported through [onEvent].
   */
  suspend fun compactNow(
    project: Project,
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    sessionId: String,
    history: List<ChatHistoryMessage>,
    compactPolicy: CompactPolicyConfig? = null,
    customInstructions: String? = null,
    onTokenUsage: ((ContextTokenUsage) -> Unit)? = null,
    onEvent: (AgentStreamEvent) -> Unit = {}
  ): CompactBoundary? = withContext(Dispatchers.IO) {
    val sink = compactSinkProvider()
    if (sink == null) {
      onEvent(AgentStreamEvent.Status("Compaction is unavailable for this session."))
      return@withContext null
    }
    activePolicy = compactPolicy ?: CompactPolicyConfig.forModel(model.contextWindow, model.maxOutputTokens)
    usageListener = onTokenUsage
    usageCharsSinceEmit = 0
    compactor.resetCircuitBreaker()
    val meter = CompactTokenMeter(activePolicy.contextWindow, activePolicy)

    val transcript = buildTranscript(
      project = project,
      useTools = model.capabilities.tools,
      sessionId = sessionId,
      history = history,
      sink = sink,
      prompt = "",
      resume = true
    )
    meter.setBase(transcript.messages)
    emitUsage(meter, force = true)

    val decision = CompactPolicy.forceCompact(
      messages = transcript.messages,
      rounds = groupByAssistantStartedRounds(transcript.messages),
      config = activePolicy
    )
    val boundary = summarizeRounds(
      transcript = transcript,
      decision = decision,
      ctx = CompactionContext(provider, model, apiKey, sessionId, sink, meter, customInstructions, onEvent)
    )
    // summarizeRounds explains a no-op itself: only an on-demand pass reports.
    emitUsage(meter, force = true)
    boundary
  }

  /** Pushes the meter's current snapshot to the UI, throttled by the caller. */
  private fun emitUsage(meter: CompactTokenMeter, force: Boolean = false) {
    val listener = usageListener ?: return
    if (!force && usageCharsSinceEmit < USAGE_EMIT_INTERVAL_CHARS) return
    usageCharsSinceEmit = 0
    runCatching { listener(meter.snapshot()) }
  }

  /** Warns once the transcript is nearly at the compact threshold. */
  private fun hintIfClose(decision: com.agentisco.agent.compact.CompactDecision) {
    if (decision.reason != CompactReason.BELOW_THRESHOLD) return
    if (decision.pressurePercent < 90) return
    Log.i(TAG, "context at ${decision.pressurePercent}% of the compact threshold")
  }

  /** Validates, permission-checks, and executes one tool call; emits its events. */
  private suspend fun executeToolCall(
    call: LlmToolCall,
    project: Project,
    permissions: AgentPermissions,
    terminalSession: TerminalSession,
    askUserDecision: suspend (PendingApproval) -> UserDecision,
    onEvent: (AgentStreamEvent) -> Unit,
    modifiedFiles: MutableSet<String>
  ): ToolResult {
    val requestedName = call.name.trim()
    Log.d(TAG, "tool call id=${call.id} name=\"$requestedName\" args=${call.argumentsJson.take(300)}")

    // Never let an undefined/blank tool name reach the executor: return a
    // structured tool error the model can correct.
    val tool = requestedName.takeIf { it.isNotEmpty() && it != "null" }?.let { toolRegistry.get(it) }
    if (requestedName.isEmpty() || requestedName == "null" || tool == null) {
      val reason = "Error: \"$requestedName\" is not an available tool. Available tools: ${toolRegistry.tools.joinToString(", ") { it.name }}."
      Log.w(TAG, "unresolved tool call id=${call.id} name=\"$requestedName\"")
      onEvent(AgentStreamEvent.ToolFinished(requestedName.ifBlank { "unknown" }, false, "Unknown tool \"$requestedName\"", reason, null, call.id))
      return ToolResult(success = false, error = reason)
    }

    // Parse + schema-validate exactly once; validation failures become
    // structured tool results so the model can retry with correct args.
    val args: org.json.JSONObject = try {
      tool.parseAndValidate(call.argumentsJson)
    } catch (e: ToolArgumentError) {
      val reason = "Error: ${e.message ?: "Invalid arguments"}"
      Log.w(TAG, "validation failed id=${call.id} name=${tool.name}: $reason")
      onEvent(AgentStreamEvent.ToolFinished(tool.name, false, e.message ?: "Invalid arguments", reason, null, call.id))
      return ToolResult(success = false, error = reason)
    }
    Log.d(TAG, "validated args id=${call.id} name=${tool.name} -> $args")

    // Plan mode is enforced here, not inside the tools: nothing that changes the
    // workspace can reach an implementation while the user is still deciding.
    PlanMode.evaluate(tool.name, args, permissions.planMode)?.let { refusal ->
      Log.i(TAG, "plan mode refused tool ${tool.name}")
      onEvent(
        AgentStreamEvent.ToolFinished(
          tool.name, false, "Plan mode: no changes", refusal.error ?: "Planning", null, call.id
        )
      )
      return refusal
    }

    val toolContext = buildToolContext(call.id, project, permissions, terminalSession, askUserDecision)
    while (true) {
      val result: ToolResult = try {
        tool.execute(args, toolContext)
      } catch (e: Exception) {
        Log.e(TAG, "tool ${tool.name} threw", e)
        ToolResult(success = false, error = "Tool execution failed: ${e.message ?: e.javaClass.simpleName}")
      }

      // The user SIGKILLed this exact call: let them choose retry or continue
      // instead of silently feeding a failure result to the model.
      if (userCancelledCalls.remove(call.id)) {
        onEvent(AgentStreamEvent.ToolCancelled(call.id, tool.name))
        val retry = try {
          val deferred = CompletableDeferred<Boolean>()
          toolCancelDecisions[call.id] = deferred
          deferred.await()
        } catch (e: Exception) {
          false
        }
        if (retry && currentCoroutineContext().isActive) {
          Log.d(TAG, "tool ${tool.name} re-run after user retry")
          continue
        }
        return ToolResult(
          success = false,
          error = "User cancelled this operation (process killed) and chose to continue without it. " +
            "The user may complete it manually — do not repeat it automatically unless asked."
        )
      }

      result.metadata["file"]?.let { modifiedFiles.add(it) }
      Log.d(TAG, "tool ${tool.name} success=${result.success} exitCode=${result.exitCode}")

      val summary = when {
        !result.success -> result.error?.take(180) ?: "Failed"
        else -> result.output.lineSequence().firstOrNull()?.take(140)?.ifBlank { null }
          ?: result.exitCode?.let { "exit code $it" } ?: "Done"
      }
      val detail = listOfNotNull(
        result.output.takeIf { it.isNotBlank() },
        result.error?.takeIf { it.isNotBlank() },
        result.exitCode?.let { "exit code: $it" }
      ).joinToString("\n")
      onEvent(AgentStreamEvent.ToolFinished(call.name, result.success, summary, detail, result.exitCode, call.id))
      return result
    }
  }

  /** Whether a failure is worth an automatic retry (transient provider/network issues). */
  private fun isTransient(e: LlmException): Boolean = when (e.kind) {
    LlmErrorKind.NETWORK, LlmErrorKind.TIMEOUT, LlmErrorKind.SERVER, LlmErrorKind.RATE_LIMIT -> true
    else -> e.httpCode?.let { it == 429 || it in 500..599 } ?: false
  }

  private fun buildToolContext(
    callId: String,
    project: Project,
    permissions: AgentPermissions,
    terminalSession: TerminalSession,
    askUserDecision: suspend (PendingApproval) -> UserDecision
  ): ToolContext = ToolContext(
    project = project,
    toolCallId = callId,
    permissions = { permissions }, // Dynamic: reads current permissions at tool execution time
    terminalSession = terminalSession,
    onApprovalTerminated = { id -> terminatedApprovals[id] == true },
    onApprovalRationale = { id -> approvalRationales[id] },
    requestApproval = { approval ->
      // Only a real, user-made decision counts. The termination flag and the
      // rationale are recorded first so a tool reading
      // [ToolContext.requestApprovalDecision] can tell a refusal from a stopped
      // turn, and can say *why* the user refused.
      val decision = askUserDecision(approval)
      terminatedApprovals[approval.id] = decision.termination
      decision.rationale?.let { approvalRationales[approval.id] = it }
      decision.approved
    },
    askUser = { approval ->
      val decision = askUserDecision(approval)
      // Only a genuine answer is an answer: a refusal, a dismissal, and a
      // stopped turn all mean "the user did not pick one".
      if (decision.termination || !decision.allowed) null else decision.answer
    },
    activeSessions = { listOf(terminalSession) }
  )

  /**
   * Shows one user-facing request at a time and waits for the answer. Concurrent
   * tools queue here: the UI only ever holds a single dialog, and an answer must
   * not be able to resolve the wrong request.
   */
  private suspend fun awaitUserDecision(
    approval: PendingApproval,
    onRequestApproval: (PendingApproval) -> Unit,
    onEvent: (AgentStreamEvent) -> Unit
  ): UserDecision = approvalMutex.withLock {
    onEvent(
      AgentStreamEvent.ApprovalRequested(
        approvalId = approval.id,
        command = approval.command,
        title = approval.title,
        impact = approval.impactDescription,
        options = approval.options,
        isQuestion = approval.isQuestion
      )
    )
    val deferred = CompletableDeferred<UserDecision>()
    pendingApprovalDeferred = deferred
    onRequestApproval(approval)
    val decision = try {
      deferred.await()
    } catch (e: CancellationException) {
      // The turn ended while the request was open: drop it and propagate.
      pendingApprovalDeferred = null
      throw e
    }
    pendingApprovalDeferred = null
    onEvent(
      AgentStreamEvent.ApprovalResolved(
        approvalId = approval.id,
        allowed = decision.allowed,
        answer = decision.answer,
        rationale = decision.rationale,
        terminated = decision.termination
      )
    )
    decision
  }

  private fun buildSystemPrompt(
    project: Project,
    toolsAvailable: Boolean,
    planMode: Boolean,
    role: com.agentisco.agent.model.AgentRole? = null,
    teamActivity: String = ""
  ): String {
    val files = fileSystem.getFileTree(project, maxDepth = 3)
    val paths = StringBuilder()
    fun walk(items: List<ProjectFile>, depth: Int) {
      if (depth > 2) return
      for (f in items) {
        paths.appendLine(f.path)
        if (f.isDirectory) walk(f.children, depth + 1)
      }
    }
    walk(files, 0)
    return buildString {
      appendLine("You are Agentisco, an elite senior software engineer working inside the mobile IDE \"Agentisco\".")
      appendLine("Active project: ${project.name} (${project.path}).")
      appendLine()
      appendLine("Standards:")
      appendLine("  - Ship production-quality code: correct, idiomatic, secure, minimal, and consistent with the project's existing conventions, stack and style.")
      appendLine("  - Reason before acting: understand the goal, read the surrounding code, and choose the simplest design that fully solves the problem. Fix root causes, not symptoms.")
      appendLine("  - Verify every change with real evidence (build, test, run). Never claim success you have not observed.")
      appendLine("  - Never leave the workspace broken: no half-applied edits, dangling references, failing builds or stray debug code. If you cannot finish, restore a working state and report exactly what remains.")
      appendLine("  - Stay in scope: do what was asked, completely. No unrelated refactors, no invented requirements, no placeholders or TODO stubs.")
      appendLine("  - Be honest and precise: report what you actually did and saw, including failures and uncertainty.")
      appendLine()
      appendLine("Workspace files:")
      appendLine(paths.toString().take(4000))
      if (role != null) {
        // Which seat on the team this run occupies: its lane, its limits, its duty.
        appendLine()
        appendLine(role.prompt())
      }
      if (teamActivity.isNotEmpty()) {
        // The lane says what this agent owns; this says who else is in the tree
        // and which files are already somebody's work in progress.
        appendLine()
        appendLine(teamActivity)
      }
      if (toolsAvailable) {
        appendLine()
        append(TOOL_PLAYBOOK)
        // Names and one-liners only: the agent chooses from the description and
        // pays for the instructions themselves when it does that kind of work.
        val skills = skillStore.discover(java.io.File(project.path))
        if (skills.isNotEmpty()) {
          appendLine()
          append(com.agentisco.agent.tool.UseSkillTool.index(skills))
        }
        // The wording is for the turn whose user decides whether to implement it;
        // a research role is told to report by its own role block.
        if (planMode && role == null) {
          appendLine()
          append(PLAN_MODE_ADDENDUM)
        }
      } else {
        appendLine()
        appendLine("This model cannot call tools: you cannot read, write or run anything in this workspace, and no tool will answer for you. Work from the files listed above and the conversation, answer with complete code the user can paste rather than descriptions of it, state any assumption you had to make instead of probing for it, and never claim a change was built or tested.")
      }
    }
  }
}

/**
 * Normalizes model-supplied tool arguments to a valid JSON object string so
 * echoing the assistant tool-call message back never produces a provider 400
 * ("arguments must be a valid JSON object string").
 */
private fun normalizeArgsJson(raw: String): String {
  val text = raw.trim()
  if (text.isEmpty() || text == "null") return "{}"
  return runCatching { org.json.JSONObject(text).toString() }.getOrDefault("{}")
}

data class AgentTaskResult(
  val success: Boolean,
  val summary: String,
  val modifiedFiles: List<String>
)
