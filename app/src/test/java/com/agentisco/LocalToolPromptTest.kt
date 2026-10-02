package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.agent.llm.LlmFinishReason
import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmToolCall
import com.agentisco.agent.llm.LlmUsage
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.OnDeviceTools
import com.agentisco.agent.tool.SubagentLauncher
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import com.agentisco.data.local.LocalModelStore
import com.agentisco.local.model.LocalModel
import com.agentisco.local.model.LocalModelConfiguration
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.git.GitRunResult
import com.agentisco.workspace.terminal.TerminalProcessManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an on-device model is actually handed.
 *
 * A phone prefills the whole prompt before its first word, so the two costs that decide
 * whether a turn finishes inside a timeout — the briefing and the tool list — are measured
 * here rather than assumed. The cloud path is checked alongside them because it must not
 * change: a short prompt is a property of the model record, not of the runtime's mood.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalToolPromptTest {

  private val ws = newWorkspace("localprompt")

  @After
  fun cleanUp() {
    ws.dispose()
  }

  private val provider = AIProvider(
    "local-ai", "On-device", "http://127.0.0.1:43128/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS, hasApiKey = true
  )

  private fun registryWith(launcher: SubagentLauncher? = null): AgentToolRegistry {
    val fs = ProjectFileSystem(File(ws.root, "fs_${System.nanoTime()}"))
    return AgentToolRegistry(
      fileSystem = fs,
      gitManager = GitRepositoryManager(fs) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      subagentLauncher = launcher
    )
  }

  /** Answers with text, or with one tool call the runtime will try to run. */
  private class ScriptedLlm(private val call: LlmToolCall?) : LlmService() {
    val requests = mutableListOf<LlmRequest>()
    private val turns = AtomicInteger()

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request
      val usage = LlmUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
      val call = this.call
      onEvent(LlmStreamEvent.Started)
      if (turns.incrementAndGet() == 1 && call != null) {
        onEvent(LlmStreamEvent.ToolCallRequested(call))
        onEvent(
          LlmStreamEvent.Completed(
            LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(call), finishReason = LlmFinishReason.TOOL_CALLS, usage = usage)
          )
        )
      } else {
        onEvent(LlmStreamEvent.Token("finished"))
        onEvent(LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "finished", usage = usage)))
      }
    }
  }

  private class Run(val dir: File, val service: ScriptedLlm, val events: List<AgentStreamEvent>) {
    val request get() = service.requests.first()
    val systemPrompt get() = request.messages.first { it.role == LlmRole.SYSTEM }.content
    val toolNames get() = request.tools.map { it.name }
    val statuses get() = events.filterIsInstance<AgentStreamEvent.Status>().map { it.text }
  }

  /** One turn against a fresh workspace, with [allowed] as the model's own tool set. */
  private fun runTurn(allowed: Set<String>?, call: LlmToolCall? = null, contextWindow: Int? = 4096): Run {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    File(dir, "src/App.tsx").writeText("export const value = 1\n")
    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val service = ScriptedLlm(call)
    val events = mutableListOf<AgentStreamEvent>()
    val runtime = AgentRuntime(fileSystem, terminals, git, service, registryWith()) { null }
    runBlocking {
      runtime.executeTask(
        prompt = "say hello",
        project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath),
        provider = provider,
        model = AIModel(
          id = "local:qwen",
          providerId = "local-ai",
          modelId = "qwen",
          displayName = "Qwen",
          contextWindow = contextWindow,
          maxOutputTokens = 200,
          capabilities = ModelCapabilities(tools = true),
          allowedToolNames = allowed
        ),
        apiKey = "k",
        permissions = {
          AgentPermissions(
            fileEditing = PermissionMode.ALLOW_ALL,
            terminalCommands = PermissionMode.ALLOW_ALL,
            deleteFiles = true
          )
        },
        terminalSession = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath),
        onRequestApproval = {},
        onEvent = { events.add(it) }
      )
    }
    return Run(dir, service, events.toList())
  }

  // ---- the request a turn sends ----

  /** A model that chose nothing is a cloud model: everything it can do, and the full method. */
  @Test
  fun `a model with no chosen tool set keeps the whole registry and the playbook`() {
    val run = runTurn(allowed = null)
    assertTrue("git_commit is offered", "git_commit" in run.toolNames)
    assertTrue("the web tools are offered", "web_search" in run.toolNames)
    assertTrue(run.systemPrompt, run.systemPrompt.contains("Tools available:"))
    assertTrue(run.systemPrompt, run.systemPrompt.contains("Verify every change with real evidence"))
    assertEquals(run.statuses.toString(), 0, run.statuses.count { it.contains("This turn needs") })
  }

  @Test
  fun `a model that chooses tools is sent exactly those and no playbook`() {
    val run = runTurn(allowed = setOf("read_file", "edit_file"))
    assertEquals(listOf("read_file", "edit_file"), run.toolNames)
    assertFalse(run.systemPrompt, run.systemPrompt.contains("Tools available:"))
    assertTrue(run.systemPrompt, run.systemPrompt.contains("Tools you may call: read_file, edit_file."))
    assertFalse(run.systemPrompt, run.systemPrompt.contains("task_plan"))
  }

  /** Choosing nothing is an answer: the turn runs as text, and says so. */
  @Test
  fun `a model with no tools offered is told it has none`() {
    val run = runTurn(allowed = emptySet())
    assertEquals(emptyList<String>(), run.toolNames)
    assertTrue(run.systemPrompt, run.systemPrompt.contains("You have no tools"))
    assertTrue(run.statuses.toString(), run.statuses.any { it.contains("No tools are offered") })
  }

  /**
   * The briefing is what the phone pays for before it can answer, so it is capped at a
   * few lines: who the agent is, which project it is in, what it may call. The standards,
   * the file tree and the method are the cloud model's briefing and are not sent here.
   */
  @Test
  fun `the on-device briefing is only an identity and a tool list`() {
    val run = runTurn(allowed = OnDeviceTools.DEFAULT)
    val prompt = run.systemPrompt
    assertTrue(prompt, prompt.contains("You are Agentisco, a helpful assistant"))
    assertTrue(prompt, prompt.contains("Tools you may call:"))
    assertTrue(prompt, prompt.length < 600)
    assertFalse(prompt, prompt.contains("Standards:"))
    assertFalse(prompt, prompt.contains("Workspace files:"))
    assertFalse(prompt, prompt.contains("Method:"))
  }

  /** Narrowing is not cosmetic: a tool the model was never shown cannot be executed. */
  @Test
  fun `a tool outside the chosen set is refused before it runs`() {
    val run = runTurn(
      allowed = setOf("read_file"),
      call = LlmToolCall("c1", "delete_file", """{"path":"src/App.tsx"}""")
    )
    assertEquals("the file survives", "export const value = 1\n", File(run.dir, "src/App.tsx").readText())
    val refusal = run.service.requests.last().messages.last { it.role == LlmRole.TOOL }
    assertTrue(refusal.content, refusal.content.contains("is not an available tool"))
    assertTrue(refusal.content, refusal.content.contains("Available tools: read_file"))
  }

  @Test
  fun `a prompt too large for the window is named before the request goes out`() {
    val run = runTurn(allowed = OnDeviceTools.DEFAULT, contextWindow = 128)
    val warning = run.statuses.first { it.contains("This turn needs") }
    assertTrue(warning, warning.contains("Settings → Local Models"))
  }

  // ---- the registry's own promises ----

  @Test
  fun `narrowing drops names that are not tools and never widens the set`() {
    val registry = registryWith()
    val narrowed = registry.narrowedTo(setOf("read_file", "no_such_tool"))
    assertEquals(listOf("read_file"), narrowed.tools.map { tool -> tool.name })
    assertNull("a tool the run was not offered cannot be looked up", narrowed.get("git_commit"))
    assertTrue("the registry itself is untouched", registry.get("git_commit") != null)
  }

  /** A phone may not fan one request out into a tree of model calls, even by request. */
  @Test
  fun `a narrowed run can never delegate`() {
    val launcher = SubagentLauncher { _, _, _, _, _, _ -> throw AssertionError("must not launch") }
    val registry = registryWith(launcher)
    assertTrue("the parent can delegate", registry.get("delegate") != null)
    assertNull("a narrowed run cannot", registry.narrowedTo(setOf("delegate", "read_file")).get("delegate"))
    assertFalse("and the picker never offers it", registry.offerableTools().any { it.name == "delegate" })
  }

  @Test
  fun `the on-device default is a real set that leaves the window usable`() {
    val offerings = registryWith().offerableTools()
    assertEquals(
      "every default tool is one the picker offers",
      OnDeviceTools.DEFAULT,
      OnDeviceTools.DEFAULT intersect offerings.map { it.name }.toSet()
    )
    val chosen = offerings.filter { OnDeviceTools.DEFAULT.contains(it.name) }.sumOf { it.estimatedTokens }
    assertTrue("the default tool set must fit a phone's window: $chosen tokens", chosen < 1600)
    val everything = offerings.sumOf { it.estimatedTokens }
    assertTrue("and the whole registry must be visibly the expensive choice", everything > chosen * 3)
  }

  // ---- persistence and the derived record ----

  @Test
  fun `a chosen tool set survives a restart and an untouched one does not become a frozen list`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = LocalModelStore(context)
    store.upsert(modelWithTools("chosen", setOf("read_file", "web_search")))
    store.upsert(modelWithTools("untouched", null))
    store.upsert(modelWithTools("none", emptySet()))

    val reloaded = LocalModelStore(context)
    assertEquals(setOf("read_file", "web_search"), reloaded.model("chosen")?.configuration?.allowedTools)
    assertNull("absent means the default set, not an empty one", reloaded.model("untouched")?.configuration?.allowedTools)
    assertEquals(emptySet<String>(), reloaded.model("none")?.configuration?.allowedTools)
  }

  @Test
  fun `a configuration equal to the default is still the default configuration`() {
    assertTrue(LocalModelConfiguration.Defaults.isDefault)
    assertFalse(LocalModelConfiguration(allowedTools = setOf("read_file")).isDefault)
    assertTrue(LocalModelConfiguration(allowedTools = OnDeviceTools.DEFAULT).isDefault)
    assertEquals(OnDeviceTools.DEFAULT, LocalModelConfiguration.Defaults.toolsOrDefault())
  }

  private fun modelWithTools(id: String, tools: Set<String>?) = LocalModel(
    id = id,
    name = id,
    sourceUrl = "https://example.test/$id",
    downloadUrl = "https://example.test/$id.gguf",
    configuration = LocalModelConfiguration(allowedTools = tools)
  )
}
