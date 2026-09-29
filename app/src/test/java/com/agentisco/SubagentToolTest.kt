package com.agentisco

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmToolCall
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentRole
import com.agentisco.agent.model.AgentRoles
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.PlanMode
import com.agentisco.agent.tool.SubagentLauncher
import com.agentisco.agent.tool.SubagentOutcome
import com.agentisco.agent.tool.SubagentTool
import com.agentisco.agent.tool.ToolArgumentError
import com.agentisco.data.model.Project
import com.agentisco.data.model.TerminalSession
import com.agentisco.settings.model.AIModel
import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.LLMProtocol
import com.agentisco.settings.model.ModelCapabilities
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.git.GitRunResult
import com.agentisco.workspace.terminal.TerminalProcessManager
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
 * The agent team: one engine, several seats.
 *
 * A role changes what an agent is told to care about and how far its reach goes.
 * Two properties are proven rather than assumed: research cannot write even when
 * the agent that delegated to it could, and a specialist does real work under
 * exactly the user's own permissions - never more. Every run is contained by
 * construction (tool list, gate, no approval channel of its own, no delegation),
 * which is what lets several of them work in one workspace at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubagentToolTest {

  private val ws = newWorkspace("subagent")

  @After
  fun cleanUp() {
    ws.dispose()
  }

  /** What the tool handed the launcher, and what it answers back. */
  private class RecordingLauncher(
    var outcome: SubagentOutcome = SubagentOutcome(success = true, summary = "Report body")
  ) : SubagentLauncher {
    var role: AgentRole? = null
    var description = ""
    var prompt = ""
    var project: Project? = null
    var terminal: TerminalSession? = null
    var calls = 0

    override suspend fun launch(
      role: AgentRole,
      description: String,
      prompt: String,
      project: Project,
      terminal: TerminalSession
    ): SubagentOutcome {
      calls++
      this.role = role
      this.description = description
      this.prompt = prompt
      this.project = project
      this.terminal = terminal
      return outcome
    }
  }

  private fun registryWith(
    launcher: SubagentLauncher?,
    roles: () -> List<AgentRole> = { AgentRoles.builtIn }
  ): AgentToolRegistry {
    val fileSystem = ProjectFileSystem(File(ws.root, "fsbase_${System.nanoTime()}"))
    return AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      subagentLauncher = launcher,
      subagentRoles = roles
    )
  }

  private fun delegate(launcher: SubagentLauncher, json: String): com.agentisco.agent.tool.ToolResult {
    val tool = SubagentTool(launcher)
    return runBlocking { tool.execute(tool.parseAndValidate(json), contextFor(ws)) }
  }

  private fun delegation(role: String, description: String = "trace the value", prompt: String = "do it") =
    """{"role":"$role","description":"$description","prompt":"$prompt"}"""

  // ---- The tool ----

  @Test
  fun `the role, the brief and the workspace reach the launcher`() {
    val launcher = RecordingLauncher()
    val result = delegate(
      launcher,
      delegation("backend", "implement the refund endpoint", "Add refund handling to OrderService.")
    )
    assertTrue(result.output, result.success)
    assertEquals(1, launcher.calls)
    assertEquals(AgentRoles.BACKEND, launcher.role)
    assertEquals("implement the refund endpoint", launcher.description)
    assertEquals("Add refund handling to OrderService.", launcher.prompt)
    assertEquals(ws.project, launcher.project)
    assertEquals("term-1", launcher.terminal?.id)
    assertEquals("backend", result.metadata["role"])
    assertTrue(result.output, result.output.contains("Report from the Backend Engineer (implement the refund endpoint):"))
    assertTrue(result.output, result.output.contains("Report body"))
  }

  @Test
  fun `an unknown role is refused with the roster instead of a silent fallback`() {
    val launcher = RecordingLauncher()
    val result = delegate(launcher, delegation("devops", "deploy it"))
    assertFalse(result.success)
    assertEquals("nothing launches for a role the team does not have", 0, launcher.calls)
    assertTrue(result.error!!, result.error!!.contains("no agent role \"devops\""))
    // The list it needs to correct itself.
    assertTrue(result.error!!, result.error!!.contains("backend"))
    assertTrue(result.error!!, result.error!!.contains("explore"))
  }

  @Test
  fun `a custom role the user defines is delegable at once`() {
    val custom = AgentRole(
      id = "embedded",
      name = "Firmware Engineer",
      purpose = "Own the register-level drivers.",
      responsibilities = listOf("SPI and I2C drivers"),
      handsOff = listOf("the UI"),
      craft = listOf("mind the watchdog"),
      builtIn = false
    )
    val launcher = RecordingLauncher()
    val registry = registryWith(launcher) { AgentRoles.builtIn + custom }
    val tool = registry.get("delegate") as SubagentTool
    assertTrue(tool.description, tool.description.contains("embedded"))

    val result = runBlocking {
      tool.execute(tool.parseAndValidate(delegation("embedded", "bit read")), contextFor(ws))
    }
    assertTrue(result.output, result.success)
    assertEquals(custom, launcher.role)
  }

  @Test
  fun `the report is the only thing that comes back, and a long one says so`() {
    val huge = buildString { repeat(60_000) { append("x") } }
    val result = delegate(
      RecordingLauncher(SubagentOutcome(success = true, summary = huge)),
      delegation("explore", "sweep")
    )
    assertTrue(result.success)
    assertTrue("must be capped: ${result.output.length}", result.output.length < huge.length)
    assertTrue(result.output, result.output.contains("showing 40000 of 60000 characters"))
    assertTrue(result.output, result.output.contains("narrower follow-up delegation"))
  }

  /** The parent must know what the run dirtied, without the child describing its own diff. */
  @Test
  fun `the files a specialist changed are counted by the runtime`() {
    val result = delegate(
      RecordingLauncher(
        SubagentOutcome(success = true, summary = "done", modifiedFiles = listOf("src/App.tsx", "src/api.kt"))
      ),
      delegation("frontend", "wire the form")
    )
    assertTrue(result.output, result.output.contains("Files it changed: src/App.tsx, src/api.kt"))
    assertEquals("2", result.metadata["files"])

    val research = delegate(
      RecordingLauncher(SubagentOutcome(success = true, summary = "found nothing", modifiedFiles = emptyList())),
      delegation("explore", "sweep")
    )
    assertFalse(research.output, research.output.contains("Files it changed"))
    assertEquals("0", research.metadata["files"])
  }

  @Test
  fun `a failed delegation tells the agent to do the work itself`() {
    val result = delegate(
      RecordingLauncher(SubagentOutcome(success = false, error = "No selected model with a usable API key.")),
      delegation("general", "trace", "find it")
    )
    assertFalse(result.success)
    assertTrue(result.error!!, result.error!!.contains("No selected model with a usable API key."))
    assertTrue(result.error!!, result.error!!.contains("Do the work yourself"))
    assertEquals("", result.output)
  }

  @Test
  fun `arguments are schema-checked before anything is launched`() {
    val launcher = RecordingLauncher()
    for (json in listOf(
      "{}",
      """{"role":"explore"}""",
      """{"role":"explore","description":"only this"}""",
      """{"role":"explore","prompt":"only this","description":""}"""
    )) {
      val failure = runCatching { SubagentTool(launcher).parseAndValidate(json) }.exceptionOrNull()
      assertTrue("$json must be rejected", failure is ToolArgumentError)
    }
    assertEquals("nothing reaches the launcher with bad args", 0, launcher.calls)
  }

  @Test
  fun `delegate is offered only when a runtime can run it`() {
    assertNull(registryWith(null).get("delegate"))
    assertNotNull(registryWith(RecordingLauncher()).get("delegate"))
  }

  @Test
  fun `the tool says what a delegated agent cannot do`() {
    val tool = SubagentTool(RecordingLauncher())
    assertEquals("delegate", tool.name)
    assertEquals(listOf("role", "description", "prompt"), tool.params.map { it.name })
    assertTrue(tool.description, tool.description.contains("explore is read-only"))
    assertTrue(tool.description, tool.description.contains("cannot ask the user"))
    assertTrue(tool.description, tool.description.contains("beside each other"))
    assertTrue(tool.description, tool.description.contains("does not overlap another agent's files"))
  }

  // ---- What each role may use ----

  @Test
  fun `research is offered nothing that can change the workspace`() {
    val names = registryWith(RecordingLauncher()).forDelegation(AgentRoles.EXPLORE).tools.map { it.name }.toSet()

    assertTrue("offered beyond the planning set: ${names - PlanMode.delegatedToolNames}",
      names.all { it in PlanMode.delegatedToolNames })
    assertFalse("delegate", "delegate" in names)
    for (mutating in listOf(
      "edit_file", "edit_files", "write_file", "create_file", "delete_file", "move_file",
      "copy_file", "create_directory", "git_stage", "git_commit", "build", "test",
      "write_terminal_input"
    )) {
      assertFalse("must not be offered: $mutating", mutating in names)
    }
    for (reading in listOf("read_file", "read_files", "search_files", "glob_files", "list_files", "git_log", "web_search")) {
      assertTrue("must be offered: $reading", reading in names)
    }
    // run_command is offered because the gate keeps it read-only, not because it is safe.
    assertTrue(names.contains("run_command"))
  }

  @Test
  fun `a specialist is offered the working tools and never another delegation`() {
    val parent = registryWith(RecordingLauncher())
    assertTrue(parent.tools.map { it.name }.contains("delegate"))

    for (role in AgentRoles.builtIn.filter { !it.readOnly }) {
      val names = parent.forDelegation(role).tools.map { it.name }.toSet()
      for (working in listOf("edit_file", "edit_files", "write_file", "run_command", "build", "test", "git_commit")) {
        assertTrue("${role.id} must be able to $working", working in names)
      }
      assertFalse("${role.id} must not delegate again", "delegate" in names)
    }
  }

  @Test
  fun `research never writes and a specialist works under the user's own limits`() {
    val parent = AgentPermissions(
      planMode = false,
      fileEditing = PermissionMode.ALLOW_ALL,
      terminalCommands = PermissionMode.ALLOW_ALL,
      deleteFiles = true,
      gitPush = true,
      maxToolIterations = 200
    )
    val research = SubagentTool.childPermissions(AgentRoles.EXPLORE, parent)
    assertTrue(research.planMode)
    assertEquals(PermissionMode.NEVER_ALLOW, research.fileEditing)
    assertFalse(research.deleteFiles)
    assertEquals(PermissionMode.ALLOW_SAFE, research.terminalCommands)
    assertEquals(AgentRoles.EXPLORE.maxToolIterations, research.maxToolIterations)

    val engineer = SubagentTool.childPermissions(AgentRoles.BACKEND, parent)
    assertFalse(engineer.planMode)
    // It does real work, but gains no permission the user did not already give.
    assertEquals(PermissionMode.ALLOW_ALL, engineer.fileEditing)
    assertEquals(PermissionMode.ALLOW_ALL, engineer.terminalCommands)
    assertTrue(engineer.deleteFiles)
    assertEquals(AgentRoles.BACKEND.maxToolIterations, engineer.maxToolIterations)

    // Nothing on the team pushes, whatever the user's own setting.
    assertFalse(engineer.gitPush)
    assertFalse(research.gitPush)

    // A user who forbade commands is not overridden by delegation.
    val strict = SubagentTool.childPermissions(
      AgentRoles.QA,
      AgentPermissions(terminalCommands = PermissionMode.NEVER_ALLOW, fileEditing = PermissionMode.NEVER_ALLOW)
    )
    assertEquals(PermissionMode.NEVER_ALLOW, strict.terminalCommands)
    assertEquals(PermissionMode.NEVER_ALLOW, strict.fileEditing)
  }

  // ---- The roster ----

  @Test
  fun `the built-in team is eight distinct seats`() {
    val ids = AgentRoles.builtIn.map { it.id }
    assertEquals(listOf("general", "explore", "uiux", "frontend", "backend", "debugger", "qa", "security"), ids)
    assertEquals(ids.size, ids.toSet().size)
    assertEquals(ids.size, AgentRoles.builtIn.map { it.name }.toSet().size)

    // Only research is read-only.
    assertEquals(listOf("explore"), AgentRoles.builtIn.filter { it.readOnly }.map { it.id })

    AgentRoles.builtIn.forEach { role ->
      assertTrue("${role.id} needs a purpose", role.purpose.isNotBlank())
      assertTrue("${role.id} has a lane", role.responsibilities.size >= 2)
      assertTrue("${role.id} names what it stays out of", role.handsOff.isNotEmpty())
      assertTrue("${role.id} has standards", role.craft.isNotEmpty())
      assertTrue("${role.id} is capped", role.maxToolIterations in 8..60)
      assertTrue(role.prompt(), role.prompt().contains("You are the ${role.name} agent"))
    }
  }

  @Test
  fun `every role prompt teaches the lane, the concurrency and the report`() {
    val uiux = AgentRoles.UIUX.prompt()
    assertTrue(uiux, uiux.contains("implement, not just recommend"))
    assertTrue(uiux, uiux.contains("Other agents may be working in this same workspace"))
    assertTrue(uiux, uiux.contains("Verify only what is yours"))
    assertTrue(uiux, uiux.contains("Hand-offs"))
    // A designer is told to build, not to hand back advice.
    assertTrue(uiux, uiux.contains("Inspect the screens that exist"))

    val research = AgentRoles.EXPLORE.prompt()
    assertTrue(research, research.contains("You are read-only"))
    assertFalse(research, research.contains("implement, not just recommend"))
    // Research answers with evidence; an engineer answers with a diff and a test run.
    assertTrue(research, research.contains("The evidence"))
    assertTrue(uiux, uiux.contains("Files you modified"))
  }

  // ---- Through two real runtimes ----

  private val provider =
    AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 60_000, capabilities = ModelCapabilities(tools = true)
  )

  /** One model turn: a tool call, or a final answer. */
  private sealed class Turn {
    data class Call(val name: String, val args: String) : Turn()
    data class Answer(val text: String) : Turn()
  }

  /** Answers a scripted turn per call and records every request, parent's and child's. */
  private class ScriptedService(private val turns: List<Turn>) : LlmService() {
    val requests = mutableListOf<LlmRequest>()
    private var index = 0

    override suspend fun streamChat(
      provider: AIProvider,
      model: AIModel,
      apiKey: String,
      request: LlmRequest,
      onEvent: (LlmStreamEvent) -> Unit
    ) {
      // The runtime hands over a live transcript it keeps appending to, so what
      // this records must be the state at the moment the model was asked.
      requests += request.copy(messages = request.messages.toList())
      onEvent(LlmStreamEvent.Started)
      when (val turn = turns.getOrElse(index) { Turn.Answer("SCRIPT EXHAUSTED") }) {
        is Turn.Call -> {
          val call = LlmToolCall("call_$index", turn.name, turn.args)
          onEvent(LlmStreamEvent.ToolCallRequested(call))
          onEvent(LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, "", toolCalls = listOf(call))))
        }
        is Turn.Answer -> {
          onEvent(LlmStreamEvent.Token(turn.text))
          onEvent(LlmStreamEvent.Completed(LlmMessage(LlmRole.ASSISTANT, turn.text)))
        }
      }
      index++
    }
  }

  private class PairingRun(val dir: File, val service: ScriptedService, val events: List<AgentStreamEvent>) {
    val file get() = File(dir, "src/App.tsx")
    /** The child's requests: everything between the parent's first call and its last. */
    val childFirst get() = service.requests[1]
    val parentFinal get() = service.requests.last()
  }

  /**
   * Parent and child share one [LlmService], so the script is the whole
   * conversation of both: the parent delegates, the child works, the parent
   * answers.
   */
  private fun delegationRun(role: AgentRole, childTurns: List<Turn>): PairingRun {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    File(dir, "src/App.tsx").writeText("export const value = 1\n")

    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath)
    val terminal = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath)

    val service = ScriptedService(
      listOf(
        Turn.Call("delegate", delegation(role.id, "trace the value", "Find where value is read and set it to 2."))
      ) + childTurns + Turn.Answer("The report is in.")
    )

    // The launcher needs the runtime it belongs to, so it resolves it lazily.
    val events = mutableListOf<AgentStreamEvent>()
    var runtime: AgentRuntime? = null
    val registry = AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = git,
      terminalManager = terminals,
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      subagentLauncher = SubagentLauncher { childRole, description, prompt, childProject, childTerminal ->
        runtime!!.runSubagent(
          role = childRole,
          description = description,
          prompt = prompt,
          project = childProject,
          provider = provider,
          model = model,
          apiKey = "k",
          parentPermissions = AgentPermissions(
            planMode = false,
            fileEditing = PermissionMode.ALLOW_ALL,
            terminalCommands = PermissionMode.ALLOW_ALL,
            deleteFiles = true
          ),
          terminalSession = childTerminal,
          // The relay inside runSubagent turns these into status lines, so this is
          // the same stream the parent reports on - exactly how the repository wires it.
          onEvent = { event -> events.add(event) }
        )
      }
    )
    val parent = AgentRuntime(fileSystem, terminals, git, service, registry) { null }
    runtime = parent

    runBlocking {
      parent.executeTask(
        prompt = "find who reads value",
        project = project,
        provider = provider,
        model = model,
        apiKey = "k",
        permissions = {
          AgentPermissions(
            planMode = false,
            fileEditing = PermissionMode.ALLOW_ALL,
            terminalCommands = PermissionMode.ALLOW_ALL,
            deleteFiles = true
          )
        },
        terminalSession = terminal,
        onRequestApproval = { },
        onEvent = { events.add(it) }
      )
    }
    return PairingRun(dir, service, events)
  }

  private val editTurn = Turn.Call(
    "edit_file",
    """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""
  )

  @Test
  fun `research cannot edit even when the agent that delegated to it could`() {
    val run = delegationRun(
      AgentRoles.EXPLORE,
      listOf(
        // Told to be read-only, it reaches for a write anyway: not even offered.
        editTurn,
        // So it tries the one command that is offered and still forbidden.
        Turn.Call("run_command", """{"command":"npm install left-pad"}"""),
        Turn.Answer("Report: value is read at src/App.tsx:1.")
      )
    )
    assertEquals("export const value = 1\n", run.file.readText())
    assertEquals(5, run.service.requests.size)

    val unknown = run.service.requests[2].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(unknown, unknown.contains("is not an available tool"))
    assertFalse(unknown, unknown.contains("write_file,"))

    val refused = run.service.requests[3].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(refused, refused.contains("only read-only shell commands"))

    val report = run.parentFinal.messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(report, report.contains("Report from the Explore / Research Engineer (trace the value):"))
    assertTrue(report, report.contains("value is read at src/App.tsx:1"))
  }

  /** The point of the team: a specialist does the edit and says what it verified. */
  @Test
  fun `a specialist implements the change the parent delegated`() {
    val run = delegationRun(
      AgentRoles.BACKEND,
      listOf(
        editTurn,
        Turn.Answer("Changed value to 2 in src/App.tsx and re-read the file to confirm.")
      )
    )
    assertEquals("export const value = 2\n", run.file.readText())

    val report = run.parentFinal.messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(report, report.contains("Report from the Backend Engineer"))
    assertTrue(report, report.contains("Files it changed: src/App.tsx"))
  }

  @Test
  fun `the child starts from its role and none of the parents history`() {
    val run = delegationRun(AgentRoles.UIUX, listOf(Turn.Answer("Styled the header.")))
    val child = run.childFirst
    assertEquals(2, child.messages.size)

    val system = child.messages.first { it.role == LlmRole.SYSTEM }.content
    assertTrue(system, system.contains("You are the UI / UX Designer agent working on this project"))
    assertTrue(system, system.contains("Other agents may be working in this same workspace"))
    assertTrue(system, system.contains("Finish with a report, not a conversation"))
    // The wording aimed at a user-facing planning turn would send it to task_plan
    // and ask_user, neither of which a delegated run can use.
    assertFalse(system, system.contains("Plan mode is ON"))
    // and the shared tool method still applies to a specialist.
    assertTrue(system, system.contains("old_string must be copied verbatim"))

    val task = child.messages.first { it.role == LlmRole.USER }.content
    assertTrue(task, task.contains("Find where value is read and set it to 2."))
    assertTrue(task, task.startsWith("UI / UX Designer task (trace the value)"))

    val offered = child.tools.map { it.name }.toSet()
    assertTrue(offered.toString(), "edit_file" in offered)
    assertFalse(offered.toString(), "delegate" in offered)

    val parentOffered = run.service.requests[0].tools.map { it.name }.toSet()
    assertTrue(parentOffered.toString(), "delegate" in parentOffered)
  }

  @Test
  fun `research is told it is read-only and given the planning gate`() {
    val run = delegationRun(AgentRoles.EXPLORE, listOf(Turn.Answer("Nothing to change here.")))
    val system = run.childFirst.messages.first { it.role == LlmRole.SYSTEM }.content
    assertTrue(system, system.contains("You are read-only"))
    assertTrue(system, system.contains("The evidence"))
    assertFalse(system, system.contains("Plan mode is ON for this turn"))
  }

  @Test
  fun `a delegated agent works in the background instead of talking over the answer`() {
    val run = delegationRun(
      AgentRoles.DEBUGGER,
      listOf(Turn.Answer("Root cause was the missing null check."))
    )
    val status = run.events.filterIsInstance<AgentStreamEvent.Status>().map { it.text }
    assertTrue(status.toString(), status.any { it.startsWith("Debugger:") })

    val streamed = run.events.filterIsInstance<AgentStreamEvent.Token>().map { it.text }
    assertFalse(streamed.toString(), streamed.any { it.contains("Root cause was") })
    assertTrue(streamed.toString(), streamed.contains("The report is in."))

    val delegation = run.events.filterIsInstance<AgentStreamEvent.ToolFinished>().single { it.name == "delegate" }
    assertTrue(delegation.summary, delegation.success)
  }

  @Test
  fun `a delegated agent never gets the user's approval dialog`() {
    val run = delegationRun(
      AgentRoles.SECURITY,
      listOf(
        // A specialist may try to pull the user into a decision - and must be
        // answered without a dialog the user never saw, so it cannot hang.
        Turn.Call("ask_user", """{"question":"rotate the signing key now?","options":["now","after release"]}"""),
        Turn.Answer("Assumed after release and reported it as a hand-off.")
      )
    )
    assertTrue(
      "no approval may reach the parent from a delegated run",
      run.events.none { it is AgentStreamEvent.ApprovalRequested }
    )
    val asked = run.service.requests[2].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(asked, asked.contains("did not answer"))
  }
}
