package com.awaki

import com.awaki.agent.llm.LlmMessage
import com.awaki.agent.llm.LlmRequest
import com.awaki.agent.llm.LlmRole
import com.awaki.agent.llm.LlmService
import com.awaki.agent.llm.LlmStreamEvent
import com.awaki.agent.llm.LlmToolCall
import com.awaki.agent.llm.LlmUsage
import com.awaki.agent.model.AgentPermissions
import com.awaki.agent.model.AgentStreamEvent
import com.awaki.agent.model.PermissionMode
import com.awaki.agent.runtime.AgentRuntime
import com.awaki.agent.tool.AgentToolRegistry
import com.awaki.agent.tool.PlanMode
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan mode: the user wants a plan, so nothing may change.
 *
 * What is proven here is the shape of the gate, not just its behaviour: it is an
 * allowlist evaluated by the runtime before any tool body runs, so a tool the
 * next commit adds is refused while planning until someone decides it is safe to
 * let through — never allowed by forgetting to check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanModeTest {

  private val ws = newWorkspace("plan")

  @After
  fun cleanUp() {
    ws.dispose()
  }

  private fun evaluate(name: String, json: String = "{}", planMode: Boolean = true) =
    PlanMode.evaluate(name, args(json), planMode)

  // ---- The gate ----

  @Test
  fun `a tool that would change the workspace is refused while planning`() {
    for (name in listOf("edit_file", "edit_files", "write_file", "create_file", "delete_file", "move_file", "copy_file", "create_directory", "git_stage", "git_commit", "build", "test", "write_terminal_input")) {
      val refusal = evaluate(name)
      assertNotNull("$name must be refused", refusal)
      assertFalse("$name reports failure to the model", refusal!!.success)
      assertTrue("$name explains plan mode: $name", refusal.error!!.contains("Plan mode is on"))
      assertTrue("$name points at task_plan", refusal.error!!.contains("task_plan"))
    }
  }

  @Test
  fun `the same tools run normally once planning is over`() {
    assertNull(evaluate("edit_file", planMode = false))
    assertNull(evaluate("delete_file", planMode = false))
    assertNull(evaluate("run_command", """{"command": "npm test"}""", planMode = false))
  }

  @Test
  fun `research stays usable while planning`() {
    for (name in listOf(
      "read_file", "read_files", "list_files", "directory_tree", "file_info",
      "glob_files", "search_files", "regex_search", "git_status", "git_diff", "git_log",
      "git_show", "web_fetch", "web_search", "ask_user", "task_plan", "terminal_output",
      "delegate"
    )) {
      assertNull("$name must stay usable", evaluate(name))
    }
  }

  /** A delegated run is read-only by construction, so it may be started while planning. */
  @Test
  fun `delegating research is still allowed while planning`() {
    assertNull(evaluate("delegate"))
    assertFalse("delegate", "delegate" in PlanMode.delegatedToolNames)
  }

  /** The fail-safe property the whole design rests on. */
  @Test
  fun `only the documented tools pass the planning gate`() {
    val registry = AgentToolRegistry(
      fileSystem = ProjectFileSystem(File(ws.root, "fsbase")),
      gitManager = GitRepositoryManager(ProjectFileSystem(File(ws.root, "fsbase"))) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {}
    )
    val allowed = registry.tools
      .filter { it.name != "run_command" }
      .filter { evaluate(it.name) == null }
      .map { it.name }
      .toSet()
    assertEquals(
      setOf(
        "read_file", "read_files", "list_files", "directory_tree", "file_info",
        "glob_files", "search_files", "regex_search", "git_status", "git_diff", "git_log",
        "git_show", "web_fetch", "web_search", "ask_user", "task_plan", "terminal_output",
        "interrupt_terminal", "use_skill"
      ),
      allowed
    )
  }

  // ---- The read-only shell ----

  @Test
  fun `inspection commands pass while planning`() {
    for (command in listOf(
      "ls -la",
      "cat package.json",
      "head -n 40 src/App.tsx",
      "grep -rn \"useState\" src | head -20",
      "find . -name \"*.test.ts\"",
      "wc -l src/*.ts",
      "/bin/ls node_modules",
      "git status",
      "git log --oneline -5",
      "git diff src/App.tsx"
    )) {
      assertTrue("must be read-only: $command", PlanMode.isReadOnlyCommand(command))
      val call = org.json.JSONObject().put("command", command)
      assertNull("must run: $command", PlanMode.evaluate("run_command", call, true))
    }
  }

  @Test
  fun `anything that can change something is refused`() {
    for (command in listOf(
      "npm install",
      "npm run build",
      "rm -rf node_modules",
      "echo note > NOTES.md",
      "cat file | tee copy.txt",
      "find . -delete",
      "find . -name x -exec rm {} ;",
      "sort big.txt -o sorted.txt",
      "git commit -m fix",
      "git checkout main",
      "git stash",
      "ls && rm -rf /",
      "sh -c \"rm -rf /\"",
      "git log --output=report.txt",
      "echo $(whoami)",
      "",
      "   "
    )) {
      assertFalse("must not be read-only: $command", PlanMode.isReadOnlyCommand(command))
    }
    val refusal = evaluate("run_command", """{"command": "npm test"}""")
    assertNotNull(refusal)
    assertTrue(refusal!!.error!!.contains("read-only"))
    assertNull("a refused command never reports an exit code", refusal.exitCode)
  }

  /** A command whose shape the gate cannot recognise is refused, not guessed at. */
  @Test
  fun `an unreadable command shape is refused`() {
    assertFalse(PlanMode.isReadOnlyCommand("weird-binary --flag"))
    assertFalse(PlanMode.isReadOnlyCommand("ls;"))
  }

  // ---- Through the real runtime ----

  private val provider =
    AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 60_000, capabilities = ModelCapabilities(tools = true)
  )

  /** One tool call, then a plain-text answer: exactly what a planning turn looks like. */
  private class ScriptedLlm(private val call: LlmToolCall) : LlmService() {
    val requests = mutableListOf<List<LlmMessage>>()
    val approvals = mutableListOf<com.awaki.agent.model.PendingApproval>()
    private var turns = 0

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      requests += request.messages
      turns++
      onEvent(LlmStreamEvent.Started)
      val usage = LlmUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
      if (turns == 1) {
        onEvent(LlmStreamEvent.ToolCallRequested(call))
        onEvent(
          LlmStreamEvent.Completed(
            LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(call), usage = usage)
          )
        )
      } else {
        onEvent(LlmStreamEvent.Token("Here is the plan."))
        onEvent(LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "Here is the plan.", usage = usage)))
      }
    }
  }

  private class Run(val dir: File, val service: ScriptedLlm, val events: List<AgentStreamEvent>) {
    val file get() = File(dir, "src/App.tsx")
  }

  private fun planningRun(planMode: Boolean, call: LlmToolCall): Run {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    File(dir, "src/App.tsx").writeText("export const value = 1\n")
    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val registry = AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = git,
      terminalManager = terminals,
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {}
    )
    val service = ScriptedLlm(call)
    val events = mutableListOf<AgentStreamEvent>()
    val runtime = AgentRuntime(fileSystem, terminals, git, service, registry) { null }
    runBlocking {
      runtime.executeTask(
        prompt = "make the value 2",
        project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath),
        provider = provider,
        model = model,
        apiKey = "k",
        // Every permission that could allow the change is already allowed, so the
        // only thing that can stop it is the planning gate.
        permissions = {
          AgentPermissions(
            planMode = planMode,
            fileEditing = PermissionMode.ALLOW_ALL,
            terminalCommands = PermissionMode.ALLOW_ALL,
            deleteFiles = true
          )
        },
        terminalSession = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath),
        onRequestApproval = { service.approvals.add(it) },
        onEvent = { events.add(it) }
      )
    }
    return Run(dir, service, events)
  }

  private val editCall = LlmToolCall(
    "call_1",
    "edit_file",
    """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""
  )

  @Test
  fun `the runtime refuses the edit and the file on disk is untouched`() {
    val run = planningRun(true, editCall)
    assertEquals("export const value = 1\n", run.file.readText())
    val refusal = run.service.requests.last().last { it.role == LlmRole.TOOL }
    assertTrue(refusal.content, refusal.content.contains("Plan mode is on"))
    // Refusing is not asking: the user is never prompted to approve a change.
    assertTrue(run.service.approvals.isEmpty())
    assertTrue(
      run.events.toString(),
      run.events.any { it is AgentStreamEvent.ToolFinished && !it.success && it.name == "edit_file" }
    )
  }

  @Test
  fun `the same turn edits normally when planning is off`() {
    val run = planningRun(false, editCall)
    assertEquals("export const value = 2\n", run.file.readText())
  }

  @Test
  fun `planning still lets the agent read the file it is planning to change`() {
    val run = planningRun(true, LlmToolCall("call_1", "read_file", """{"path":"src/App.tsx"}"""))
    val toolMessage = run.service.requests.last().last { it.role == LlmRole.TOOL }
    assertTrue(toolMessage.content, toolMessage.content.contains("export const value = 1"))
  }

  @Test
  fun `planning refuses a build command but allows inspection`() {
    val build = planningRun(true, LlmToolCall("call_1", "run_command", """{"command": "npm test"}"""))
    assertTrue(
      build.service.requests.last().last { it.role == LlmRole.TOOL }.content
        .contains("only read-only shell commands")
    )
    assertTrue(build.service.approvals.isEmpty())

    val inspect = planningRun(true, LlmToolCall("call_1", "run_command", """{"command": "ls -la"}"""))
    val result = inspect.service.requests.last().last { it.role == LlmRole.TOOL }.content
    assertFalse(result, result.contains("Plan mode is on"))
  }

  @Test
  fun `the system prompt states what planning allows`() {
    val planning = planningRun(true, LlmToolCall("call_1", "task_plan", """{"steps": ["read the file"]}"""))
    val system = planning.service.requests.first().first { it.role == LlmRole.SYSTEM }.content
    assertTrue(system, system.contains("Plan mode is ON"))
    // It teaches the method, not just the refusal.
    assertTrue(system, system.contains("do not retry"))
    // and the normal playbook still applies on top of it.
    assertTrue(system, system.contains("old_string must be copied verbatim"))

    val normal = planningRun(false, editCall)
    val plainSystem = normal.service.requests.first().first { it.role == LlmRole.SYSTEM }.content
    assertFalse(plainSystem, plainSystem.contains("Plan mode is ON"))
  }
}
