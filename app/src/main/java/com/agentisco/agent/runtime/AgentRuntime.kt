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
import com.agentisco.agent.llm.LlmUsage
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.tool.AgentToolRegistry
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
  }

  /** Two-tier compaction over the in-memory transcript of the current run. */
  private val compactor = CompactCoordinator(llmService) { activePolicy }
  private var activePolicy: CompactPolicyConfig = CompactPolicyConfig()
  private var usageListener: ((ContextTokenUsage) -> Unit)? = null
  private var usageCharsSinceEmit = 0

  /** Serializes approval requests when tools run concurrently. */
  private val approvalMutex = Mutex()
  private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
  private val toolParallelism = Semaphore(MAX_PARALLEL_TOOLS)

  /** Tool calls the user SIGKILLed; keyed by the model's call id. */
  private val userCancelledCalls: MutableSet<String> =
    java.util.Collections.synchronizedSet(HashSet())
  /** Awaits the user's retry/continue decision for a cancelled tool call. */
  private val toolCancelDecisions =
    java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

  fun resolvePendingApproval(allowed: Boolean) {
    val deferred = pendingApprovalDeferred
    pendingApprovalDeferred = null
    deferred?.complete(allowed)
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
     * Compaction budget for this run: the selected model's real context window
     * plus the user's Settings choices. Null derives it from the model.
     */
    compactPolicy: CompactPolicyConfig? = null,
    /** Fresh context-usage snapshots, for the composer's percent chip. */
    onTokenUsage: ((ContextTokenUsage) -> Unit)? = null,
    onRequestApproval: (PendingApproval) -> Unit,
    onEvent: (AgentStreamEvent) -> Unit
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
    val meter = CompactTokenMeter(activePolicy.contextWindow, activePolicy)

    val transcript = buildTranscript(
      project = project,
      useTools = useTools,
      sessionId = sessionId,
      history = history,
      sink = sink,
      prompt = prompt,
      resume = resume
    )
    val messages = transcript.messages
    val rowIds = transcript.rowIds
    val compactedThrough = transcript.compactedThrough
    var lastUsage: LlmUsage? = null

    /**
     * Replaces the transcript with a compressed one, keeping [rowIds] aligned.
     */
    fun replaceAll(newMessages: List<LlmMessage>, newRowIds: List<Long>) {
      messages.clear(); messages.addAll(newMessages)
      rowIds.clear(); rowIds.addAll(newRowIds)
    }

    meter.setBase(messages)
    emitUsage(meter, force = true)

    val modifiedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val maxIterations = permissions().maxToolIterations.coerceAtLeast(1)

    /**
     * Compresses the transcript before the next request.
     *
     * 1. Local tier: old tool-result payloads are replaced with a placeholder —
     *    no model call. The medata in SQLite is untouched, so the chat keeps
     *    showing the real output.
     * 2. Model tier: when the transcript is still above the threshold, the
     *    older assistant-started rounds are summarized and replaced by that
     *    summary, keeping the newest rounds verbatim.
     */
    suspend fun compactTranscript(): Unit {
      lastUsage?.let { meter.onResponseCompleted(it) }
      lastUsage = null

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

      val plan = ManualCompact.plan(messages, activePolicy)
      if (plan.summarizedRoundCount == 0) return
      val systemCount = messages.count { it.role == LlmRole.SYSTEM }
      // The plan keeps the trailing non-system messages of [messages] as-is.
      val keptTailCount = plan.keepRounds.sumOf { it.messages.size }
      val keepStart = (messages.size - keptTailCount).coerceIn(systemCount, messages.size)
      val summarizedThrough = rowIds.subList(0, keepStart).filter { it > 0L }.maxOrNull() ?: compactedThrough
      val keptFrom = rowIds.getOrNull(keepStart)?.takeIf { it > 0L } ?: summarizedThrough
      if (summarizedThrough <= compactedThrough) return // nothing new to summarize

      val result = compactor.compactWithModel(
        plan = plan,
        provider = provider,
        model = model,
        apiKey = apiKey,
        tokensBefore = meter.snapshot().usedTokens,
        clearedToolResults = meter.snapshot().clearedToolResults,
        trigger = decision.reason,
        onProgress = { onEvent(AgentStreamEvent.Status(it)) }
      ) ?: run {
        emitUsage(meter, force = true)
        return
      }

      // Rebuild the parallel row map: the preserved tail still points at the
      // rows it was built from (system prompt and summary were never rows).
      val newRowIds = MutableList(result.messages.size) { 0L }
      for (offset in 0 until keptTailCount) {
        val source = keepStart + offset
        val target = systemCount + 1 + offset
        if (source < rowIds.size && target < newRowIds.size) newRowIds[target] = rowIds[source]
      }
      replaceAll(result.messages, newRowIds)
      meter.onCompacted(
        ContextTokenUsage(
          usedTokens = estimateMessageTokens(result.messages),
          contextWindow = activePolicy.contextWindow,
          thresholdTokens = activePolicy.thresholdTokens,
          clearedToolResults = result.boundary.clearedToolResults
        )
      )
      onEvent(AgentStreamEvent.ContextCompacted(result.boundary, summarizedThrough, result.summary))
      sink.onCompacted(sessionId, result.summary, result.boundary, summarizedThrough, keptFrom)
      emitUsage(meter, force = true)
    }

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
                  lastUsage = event.message.usage
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
                    call to executeToolCall(call, project, permissions(), terminalSession, onRequestApproval, onEvent, modifiedFiles)
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
      onEvent(AgentStreamEvent.Cancelled())
      throw e
    } catch (e: LlmException) {
      if (e.kind == LlmErrorKind.CANCELLED) {
        onEvent(AgentStreamEvent.Cancelled("Generation stopped"))
        AgentTaskResult(success = false, summary = "Cancelled", modifiedFiles = modifiedFiles.toList())
      } else {
        onEvent(AgentStreamEvent.Failed(e.message ?: "LLM request failed"))
        AgentTaskResult(success = false, summary = e.message ?: "LLM request failed", modifiedFiles = modifiedFiles.toList())
      }
    } catch (e: Exception) {
      val reason = "Agent failed: ${e.message ?: e.javaClass.simpleName}"
      onEvent(AgentStreamEvent.Failed(reason))
      AgentTaskResult(success = false, summary = reason, modifiedFiles = modifiedFiles.toList())
    }
  }

  /**
   * Builds the transcript the provider will receive: the static system prompt,
   * a summary of any earlier compaction, the persisted rows that compaction
   * did not fold in, and finally the new user prompt.
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
    resume: Boolean
  ): Transcript {
    val messages = mutableListOf<LlmMessage>()
    // Persisted rowId each message came from (0 = produced by this run), so a
    // compaction can record exactly which stored messages it folded in.
    val rowIds = mutableListOf<Long>()
    fun add(message: LlmMessage, rowId: Long = 0L) {
      messages.add(message)
      rowIds.add(rowId)
    }

    add(buildSystemPrompt(project, useTools))

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

  /** Pushes the meter's current snapshot to the UI, throttled by the caller. */
  private fun emitUsage(meter: CompactTokenMeter, force: Boolean = false) {    val listener = usageListener ?: return
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
    onRequestApproval: (PendingApproval) -> Unit,
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

    val toolContext = buildToolContext(call.id, project, permissions, terminalSession, onRequestApproval, onEvent)
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
    onRequestApproval: (PendingApproval) -> Unit,
    onEvent: (AgentStreamEvent) -> Unit
  ): ToolContext = ToolContext(
    project = project,
    toolCallId = callId,
    permissions = { permissions }, // Dynamic: reads current permissions at tool execution time
    terminalSession = terminalSession,
    requestApproval = { approval ->
      // Concurrent tools queue here; approvals resolve strictly one at a time.
      approvalMutex.withLock {
        onEvent(
          AgentStreamEvent.ApprovalRequested(
            approvalId = approval.id,
            command = approval.command,
            title = approval.title,
            impact = approval.impactDescription
          )
        )
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovalDeferred = deferred
        onRequestApproval(approval)
        val allowed = deferred.await()
        onEvent(AgentStreamEvent.ApprovalResolved(approval.id, allowed))
        allowed
      }
    },
    activeSessions = { listOf(terminalSession) }
  )

  private fun buildSystemPrompt(project: Project, toolsAvailable: Boolean): String {
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
      appendLine("You are the Agentisco coding agent operating inside the mobile IDE \"Agentisco\".")
      appendLine("Active project: ${project.name} (${project.path}).")
      appendLine()
      appendLine("Workspace files:")
      appendLine(paths.toString().take(4000))
      if (toolsAvailable) {
        appendLine()
        appendLine("You can request tools (read_file, write_file, run_command, git_*, ...) to inspect and modify this workspace.")
        appendLine("Use tools to do real work instead of describing changes. The user must approve protected operations.")
        appendLine()
        appendLine("IMPORTANT - batching: request ALL independent tool calls together in ONE response")
        appendLine("(e.g. every file you need to read, several searches, multiple commands).")
        appendLine("All results are returned together in a single round trip; do not request one tool per response.")
        appendLine("Only wait for a result when a later call depends on it.")
        appendLine()
        appendLine("IMPORTANT - finishing: once the work is done, ALWAYS end with a final plain-text")
        appendLine("response (no tool calls): a concise summary of what you did, the files you changed,")
        appendLine("and the results/outcome. The user reads that summary as the answer.")
      } else {
        appendLine()
        appendLine("This model cannot call tools. Answer with descriptions/snippets only.")
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
