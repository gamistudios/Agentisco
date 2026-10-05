package com.awaki

import com.awaki.agent.llm.LlmFinishReason
import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmStreamEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What happens when a provider stops an answer at its output-token budget
 * instead of letting the model finish. The runtime must hand over the whole
 * report, not half of it dressed up as the whole thing - and it must stop asking
 * after a few tries rather than spend the run on a model that never yields.
 *
 * Robolectric for the same reason as the other runtime tests: tool schemas and
 * system prompts are built with org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerContinuationTest {

  private val workDir: File =
    File(System.getProperty("java.io.tmpdir"), "awaki_resume_${System.currentTimeMillis()}").apply { mkdirs() }
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

  /** One text per request, and whether the provider cut that text off. */
  private class ScriptedLlm(private val script: List<Pair<String, Boolean>>) : LlmService() {
    val requests = mutableListOf<LlmRequest>()

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request.copy(messages = request.messages.toList())
      val (text, cut) = script.getOrElse(requests.lastIndex) { "" to false }
      onEvent(LlmStreamEvent.Started)
      if (text.isNotEmpty()) onEvent(LlmStreamEvent.Token(text))
      onEvent(
        LlmStreamEvent.Completed(
          LlmMessage(
            LlmRole.ASSISTANT, text,
            finishReason = if (cut) LlmFinishReason.LENGTH else LlmFinishReason.STOP
          )
        )
      )
    }
  }

  private fun run(script: List<Pair<String, Boolean>>): Pair<AgentTaskResult, ScriptedLlm> = runBlocking {
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
      prompt = "write the long report",
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
    result to service
  }

  @Test
  fun `an answer the provider cut off is resumed from exactly where it stopped`() {
    val (result, service) = run(listOf("Part one. " to true, "Part two." to false))

    assertEquals("Part one. Part two.", result.summary)
    assertEquals(2, service.requests.size)
    val resume = service.requests[1].messages
    // The model is shown its own last words as the assistant turn it really was,
    // so it picks up mid-sentence instead of starting the report over.
    assertEquals(LlmRole.ASSISTANT, resume[resume.size - 2].role)
    assertEquals("Part one. ", resume[resume.size - 2].content)
    assertEquals(LlmRole.USER, resume.last().role)
    assertTrue(resume.last().content.contains("output token limit"))
    // A resumed answer is prose: no tool is on offer for it to wander into.
    assertTrue(service.requests[1].tools.isEmpty())
  }

  @Test
  fun `a finished answer is never resumed`() {
    val (result, service) = run(listOf("All done." to false))

    assertEquals("All done.", result.summary)
    assertEquals(1, service.requests.size)
  }

  @Test
  fun `the asking stops after a few tries and the report says it is incomplete`() {
    val (result, service) = run(List(10) { "chunk " to true })

    // One answer plus a bounded number of resumes - not one request per chunk.
    assertEquals(4, service.requests.size)
    assertTrue(result.summary, result.summary.startsWith("chunk chunk chunk chunk "))
    assertTrue(result.summary, result.summary.contains("output-token limit"))
  }

  @Test
  fun `a resume that brings nothing back keeps the half that did arrive`() {
    val (result, service) = run(listOf("Part one. " to true, "" to false))

    assertEquals(2, service.requests.size)
    assertTrue(result.summary, result.summary.startsWith("Part one. "))
    assertTrue(result.summary, result.summary.contains("output-token limit"))
  }

  @Test
  fun `a stop reason of its own never triggers a resume`() {
    val (result, service) = run(listOf("Filtered." to false))

    assertEquals(1, service.requests.size)
    assertFalse(result.summary.contains("output-token limit"))
  }
}
