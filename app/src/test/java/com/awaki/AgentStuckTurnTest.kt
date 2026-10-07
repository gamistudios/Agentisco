package com.awaki

import com.awaki.agent.llm.LlmFinishReason
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.agent.llm.LlmToolCall
import com.awaki.agent.llm.LlmUsage
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.runtime.AgentRuntime
import com.awaki.agent.runtime.AgentTaskResult
import com.awaki.agent.tool.AgentToolRegistry
import com.awaki.data.model.Project
import com.awaki.data.model.TerminalSession
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import com.awaki.workspace.filesystem.ProjectFileSystem
import com.awaki.workspace.git.GitRepositoryManager
import com.awaki.workspace.git.GitRunResult
import com.awaki.workspace.terminal.TerminalProcessManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The two ways a small on-device model used to spend a run without ending it.
 *
 * Both come from the same place — a stream that reports a tool call before, or without, the tokens
 * that name the tool. Handled as if they were requests for work, the runtime answers them with a
 * refusal the model then asks for again, on a phone each attempt being a full prefill: the user
 * watches the same sentence until they stop it by hand.
 *
 * Robolectric for the same reason as the other runtime tests: tool schemas and system prompts are
 * built with org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentStuckTurnTest {

  private val workDir: File =
    File(System.getProperty("java.io.tmpdir"), "awaki_stuck_${System.currentTimeMillis()}").apply { mkdirs() }
  private val fileSystem = ProjectFileSystem(workDir)
  private val gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
  private val terminalManager = TerminalProcessManager { null }
  private val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = workDir.absolutePath)
  private val terminal = TerminalSession(id = "t", name = "main", currentDir = workDir.absolutePath)
  private val provider = AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 40_000, capabilities = ModelCapabilities(tools = true)
  )

  @After
  fun cleanWorkDir() {
    workDir.deleteRecursively()
  }

  /** What one request answers with: prose, or a call the runtime will try to run. */
  private sealed class Reply {
    class Text(val text: String) : Reply()
    class Call(val call: LlmToolCall, val text: String = "") : Reply()
  }

  private class ScriptedLlm(private val script: List<Reply>) : LlmService() {
    val requests = mutableListOf<LlmRequest>()
    private val usage = LlmUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request.copy(messages = request.messages.toList())
      onEvent(LlmStreamEvent.Started)
      when (val reply = script.getOrElse(requests.lastIndex) { Reply.Text("finished") }) {
        is Reply.Text -> {
          if (reply.text.isNotEmpty()) onEvent(LlmStreamEvent.Token(reply.text))
          onEvent(
            LlmStreamEvent.Completed(
              LlmMessage(LlmRole.ASSISTANT, reply.text, finishReason = LlmFinishReason.STOP, usage = usage)
            )
          )
        }
        is Reply.Call -> {
          onEvent(LlmStreamEvent.ToolCallRequested(reply.call))
          if (reply.text.isNotEmpty()) onEvent(LlmStreamEvent.Token(reply.text))
          onEvent(
            LlmStreamEvent.Completed(
              LlmMessage(
                LlmRole.ASSISTANT, reply.text,
                toolCalls = listOf(reply.call),
                finishReason = LlmFinishReason.TOOL_CALLS,
                usage = usage
              )
            )
          )
        }
      }
    }
  }

  private class Run(val result: AgentTaskResult, val service: ScriptedLlm) {
    /** What the model was told back after its own calls, per request. */
    val toolResponses get() = service.requests.map { request -> request.messages.count { it.role == LlmRole.TOOL } }
  }

  private fun run(script: List<Reply>): Run = runBlocking {
    val service = ScriptedLlm(script)
    val runtime = AgentRuntime(
      fileSystem, terminalManager, gitManager, service,
      AgentToolRegistry(
        fileSystem = fileSystem, gitManager = gitManager, terminalManager = terminalManager,
        stagedFilesProvider = { emptySet() }, onStageFile = {}, onStageAll = {}, onUnstageAll = {},
        extraTools = emptyList()
      )
    ) { null }
    val result = runtime.executeTask(
      prompt = "look it up",
      project = project,
      provider = provider,
      model = model,
      apiKey = "k",
      permissions = { AgentPermissions() },
      terminalSession = terminal,
      sessionId = "s1",
      onRequestApproval = {},
      onEvent = {}
    )
    Run(result, service)
  }

  /**
   * A call that never got a name is not a request for work: there is nothing to run, and the
   * refusal it would earn is exactly the message a small model reads as permission to try again.
   * The turn ends as text instead of entering the loop.
   */
  @Test
  fun `a call with no name in it ends the turn instead of being refused`() {
    val run = run(
      listOf(
        Reply.Call(LlmToolCall(id = "c1", name = "", argumentsJson = """{"path":"src/App.tsx"}""")),
        Reply.Text("should never be reached")
      )
    )

    assertEquals("nothing was offered to run, so the model is asked once", 1, run.service.requests.size)
    assertEquals("no tool result goes back for a tool nobody named", listOf(0), run.toolResponses)
    assertTrue(run.result.summary, run.result.summary.contains("without naming one"))
  }

  /** A provider that spells the name as the word null has named nothing either. */
  @Test
  fun `a call named null is not a call`() {
    val run = run(listOf(Reply.Call(LlmToolCall(id = "c1", name = "null", argumentsJson = "{}"))))

    assertEquals(1, run.service.requests.size)
    assertTrue(run.result.summary, run.result.summary.contains("without naming one"))
  }

  /**
   * The looping case the user could only stop by hand: the same step, failing the same way, once
   * per prefill. The second identical failure is the proof the model has no other move.
   */
  @Test
  fun `a step that fails the same way twice stops the run`() {
    val failing = Reply.Call(LlmToolCall(id = "c1", name = "lookup_files", argumentsJson = """{"query":"x"}"""))

    val run = run(List(6) { failing })

    assertEquals("one retry of the same failure, then out", 2, run.service.requests.size)
    assertTrue(run.result.summary, run.result.summary.contains("repeated the same failing step"))
    assertTrue(run.result.summary, run.result.summary.contains("lookup_files"))
  }

  /**
   * The break belongs to a round where everything failed: a run that got one call right is a run
   * making progress, and cutting it off would answer the user's question with a complaint.
   */
  @Test
  fun `a round that succeeds is not read as stuck`() {
    val run = run(
      listOf(
        Reply.Call(LlmToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":"missing.txt"}""")),
        Reply.Call(LlmToolCall(id = "c2", name = "read_file", argumentsJson = """{"path":"other.txt"}""")),
        Reply.Text("done")
      )
    )

    assertEquals(3, run.service.requests.size)
    assertTrue(run.result.summary, run.result.summary.contains("done"))
  }
}
