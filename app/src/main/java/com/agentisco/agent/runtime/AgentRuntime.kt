package com.agentisco.agent.runtime

import com.agentisco.agent.llm.LlmErrorKind
import com.agentisco.agent.llm.LlmException
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.AgentStepStatus
import com.agentisco.agent.model.AgentTaskStep
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.ToolContext
import com.agentisco.agent.tool.ToolResult
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives the agent loop: LLM request → (streamed) response → model-requested
 * tool calls → validation → permission policy → real tool execution → tool
 * results back to the model → repeat, until a final answer or the iteration cap.
 *
 * The model never executes tools itself and never sees provider credentials.
 */
class AgentRuntime(
  private val fileSystem: com.agentisco.workspace.filesystem.ProjectFileSystem,
  private val terminalManager: com.agentisco.workspace.terminal.TerminalProcessManager,
  private val gitManager: com.agentisco.workspace.git.GitRepositoryManager,
  private val llmService: LlmService,
  private val toolRegistry: AgentToolRegistry
) {

  private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null

  fun resolvePendingApproval(allowed: Boolean) {
    val deferred = pendingApprovalDeferred
    pendingApprovalDeferred = null
    deferred?.complete(allowed)
  }

  suspend fun executeTask(
    prompt: String,
    project: Project,
    provider: AIProvider,
    model: AIModel,
    apiKey: String,
    permissions: AgentPermissions,
    terminalSession: TerminalSession,
    onStatus: (String) -> Unit,
    onStepUpdate: (List<AgentTaskStep>) -> Unit,
    onToolExecuted: (ToolExecution) -> Unit,
    onRequestApproval: (PendingApproval) -> Unit,
    onToken: (String) -> Unit
  ): AgentTaskResult = withContext(Dispatchers.IO) {
    val steps = mutableListOf(
      AgentTaskStep("s1", "Understand task & workspace", AgentStepStatus.RUNNING, "Building workspace context"),
      AgentTaskStep("s2", "Reason & act with tools", AgentStepStatus.PENDING, "Model-driven tool loop"),
      AgentTaskStep("s3", "Summarize results", AgentStepStatus.PENDING, "Collecting final answer and changes")
    )
    onStepUpdate(steps)

    // Pre-flight capability validation: never silently send tool calls a model can't honor.
    val useTools = model.capabilities.tools
    if (!useTools) {
      onStatus("⚠ Selected model does not support tool calling — running in text-only mode.")
    }

    val systemPrompt = buildSystemPrompt(project, useTools)
    val messages = mutableListOf(LlmMessage(LlmRole.SYSTEM, systemPrompt), LlmMessage(LlmRole.USER, prompt))
    val modifiedFiles = linkedSetOf<String>()
    val maxIterations = permissions.maxToolIterations.coerceAtLeast(1)

    try {
      var finalText = ""
      for (iteration in 1..maxIterations) {
        if (iteration == 2) {
          steps[1] = steps[1].copy(status = AgentStepStatus.RUNNING)
          onStepUpdate(steps)
        }
        val assistantText = StringBuilder()
        var completedMessage: LlmMessage? = null
        var failure: LlmException? = null

        llmService.streamChat(
          provider = provider,
          model = model,
          apiKey = apiKey,
          request = LlmRequest(
            messages = messages,
            tools = if (useTools) toolRegistry.specs() else emptyList(),
            maxOutputTokens = model.maxOutputTokens
          )
        ) { event ->
          when (event) {
            is LlmStreamEvent.Started -> if (iteration == 1) onStatus("Contacting ${provider.name} · ${model.displayName}…")
            is LlmStreamEvent.Token -> {
              assistantText.append(event.text)
              onToken(event.text)
            }
            is LlmStreamEvent.ReasoningToken -> Unit // reasoning traces are not surfaced
            is LlmStreamEvent.ToolCallRequested -> onStatus("Model requested tool: ${event.call.name}")
            is LlmStreamEvent.Completed -> completedMessage = event.message
            is LlmStreamEvent.Interrupted -> failure = LlmException("Response stream was interrupted.", LlmErrorKind.CANCELLED)
            is LlmStreamEvent.Failed -> failure = event.error
          }
        }

        failure?.let { throw it }
        val message = completedMessage ?: LlmMessage(LlmRole.ASSISTANT, assistantText.toString())

        if (message.toolCalls.isEmpty() || !useTools) {
          finalText = message.content.ifBlank { assistantText.toString() }
          break
        }

        // Model requested tools: validate, enforce permissions, execute for real,
        // then feed structured results back to the model.
        messages.add(message)
        for (call in message.toolCalls) {
          val tool = toolRegistry.get(call.name)
          if (tool == null) {
            messages.add(LlmMessage(LlmRole.TOOL, "Error: unknown tool \"${call.name}\".", toolCallId = call.id, toolName = call.name))
            continue
          }
          onStatus("Running ${call.name}…")
          val result: ToolResult = try {
            tool.execute(call.argumentsJson, buildToolContext(project, permissions, terminalSession, onRequestApproval, onToolExecuted))
          } catch (e: com.agentisco.agent.tool.ToolArgumentError) {
            ToolResult(success = false, error = e.message ?: "Invalid tool arguments")
          }
          result.metadata["file"]?.let { modifiedFiles.add(it) }
          toolRegistry.recordExecution(call.name, call.argumentsJson.take(80), result, buildToolContext(project, permissions, terminalSession, onRequestApproval, onToolExecuted))
          messages.add(
            LlmMessage(
              LlmRole.TOOL,
              listOfNotNull(
                result.output.takeIf { it.isNotBlank() },
                result.error?.let { "ERROR: $it" },
                result.exitCode?.let { "exitCode: $it" }
              ).joinToString("\n").ifBlank { "(no output)" },
              toolCallId = call.id,
              toolName = call.name
            )
          )
        }
        if (iteration == maxIterations) {
          finalText = message.content.ifBlank { "Stopped after $maxIterations tool iterations." }
        }
      }

      steps[0] = steps[0].copy(status = AgentStepStatus.COMPLETED, details = "Workspace context: ${project.name}")
      steps[1] = steps[1].copy(status = AgentStepStatus.COMPLETED, details = "${modifiedFiles.size} file(s) changed via tools")
      steps[2] = steps[2].copy(status = AgentStepStatus.COMPLETED, details = "Final response received")
      onStepUpdate(steps)
      onStatus("Task completed")

      AgentTaskResult(
        success = true,
        summary = finalText.take(500).ifBlank { "Task completed." },
        modifiedFiles = modifiedFiles.toList()
      )
    } catch (e: LlmException) {
      markFailed(steps, onStepUpdate, e.message ?: "LLM request failed", onStatus)
      AgentTaskResult(success = false, summary = e.message, modifiedFiles = modifiedFiles.toList())
    } catch (e: Exception) {
      markFailed(steps, onStepUpdate, "Agent failed: ${e.message ?: e.javaClass.simpleName}", onStatus)
      AgentTaskResult(success = false, summary = "Agent failed: ${e.message ?: e.javaClass.simpleName}", modifiedFiles = modifiedFiles.toList())
    }
  }

  private fun markFailed(
    steps: MutableList<AgentTaskStep>,
    onStepUpdate: (List<AgentTaskStep>) -> Unit,
    reason: String,
    onStatus: (String) -> Unit
  ) {
    steps.forEachIndexed { i, s ->
      if (s.status == AgentStepStatus.RUNNING || s.status == AgentStepStatus.PENDING) {
        steps[i] = s.copy(status = AgentStepStatus.FAILED, details = reason.take(160))
      }
    }
    onStepUpdate(steps)
    onStatus(reason)
  }

  private fun buildToolContext(
    project: Project,
    permissions: AgentPermissions,
    terminalSession: TerminalSession,
    onRequestApproval: (PendingApproval) -> Unit,
    onToolExecuted: (ToolExecution) -> Unit
  ): ToolContext = ToolContext(
    project = project,
    permissions = permissions,
    terminalSession = terminalSession,
    requestApproval = { approval ->
      val deferred = CompletableDeferred<Boolean>()
      pendingApprovalDeferred = deferred
      onRequestApproval(approval)
      deferred.await()
    },
    onToolExecuted = onToolExecuted,
    activeSessions = { listOf(terminalSession) }
  )

  private fun buildSystemPrompt(project: Project, toolsAvailable: Boolean): String {
    val files = fileSystem.getFileTree(project)
    val paths = StringBuilder()
    fun walk(items: List<com.agentisco.data.model.ProjectFile>, depth: Int) {
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
      } else {
        appendLine()
        appendLine("This model cannot call tools. Answer with descriptions/snippets only.")
      }
    }
  }
}

data class AgentTaskResult(
  val success: Boolean,
  val summary: String,
  val modifiedFiles: List<String>
)
