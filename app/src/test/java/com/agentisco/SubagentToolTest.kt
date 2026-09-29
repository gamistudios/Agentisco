package com.agentisco

import com.agentisco.agent.llm.LlmMessage
import com.agentisco.agent.llm.LlmRequest
import com.agentisco.agent.llm.LlmRole
import com.agentisco.agent.llm.LlmService
import com.agentisco.agent.llm.LlmStreamEvent
import com.agentisco.agent.llm.LlmToolCall
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.AgentStreamEvent
import com.agentisco.agent.model.PermissionMode
import com.agentisco.agent.runtime.AgentRuntime
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.PlanMode
import com.agentisco.agent.tool.SubagentLauncher
import com.agentisco.agent.tool.SubagentOutcome
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
 * Delegation: one agent asking another to read the repository so that the
 * reading does not cost the asking agent its own context.
 *
 * The contract proven here is the containment, not the cleverness: a delegated
 * run gets a tool list with nothing in it that can change the workspace, it runs
 * under the plan-mode gate as a backstop, it cannot delegate again, and its
 * private reasoning never leaks into the parent's answer.
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
    var description = ""
    var prompt = ""
    var project: Project? = null
    var terminal: TerminalSession? = null
    var calls = 0

    override suspend fun launch(
      description: String,
      prompt: String,
      project: Project,
      terminal: TerminalSession
    ): SubagentOutcome {
      calls++
      this.description = description
      this.prompt = prompt
      this.project = project
      this.terminal = terminal
      return outcome
    }
  }

  private fun registryWith(launcher: SubagentLauncher?): AgentToolRegistry {
    val fileSystem = ProjectFileSystem(File(ws.root, "fsbase_${System.nanoTime()}"))
    return AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      subagentLauncher = launcher
    )
  }

  private fun delegate(launcher: SubagentLauncher, json: String): com.agentisco.agent.tool.ToolResult {
    val tool = SubagentTool(launcher)
    return runBlocking { tool.execute(tool.parseAndValidate(json), contextFor(ws)) }
  }

  // ---- The tool ----

  @Test
  fun `the brief and the workspace reach the launcher`() {
    val launcher = RecordingLauncher()
    val result = delegate(
      launcher,
      """{"description":"trace auth references","prompt":"List every file that reads the old auth helper."}"""
    )
    assertTrue(result.output, result.success)
    assertEquals(1, launcher.calls)
    assertEquals("trace auth references", launcher.description)
    assertEquals("List every file that reads the old auth helper.", launcher.prompt)
    assertEquals(ws.project, launcher.project)
    assertEquals("term-1", launcher.terminal?.id)
    assertEquals("trace auth references", result.metadata["delegation"])
    assertTrue(result.output, result.output.contains("Report body"))
    assertTrue(result.output, result.output.contains("Delegated task (trace auth references) finished"))
  }

  @Test
  fun `the report is the only thing that comes back, and a long one says so`() {
    val huge = buildString { repeat(60_000) { append("x") } }
    val result = delegate(
      RecordingLauncher(SubagentOutcome(success = true, summary = huge)),
      """{"description":"sweep","prompt":"read everything"}"""
    )
    assertTrue(result.success)
    assertTrue("must be capped: ${result.output.length}", result.output.length < huge.length)
    // Honest truncation: name the budget and how to get the rest.
    assertTrue(result.output, result.output.contains("showing 40000 of 60000 characters"))
    assertTrue(result.output, result.output.contains("narrower follow-up delegation"))
  }

  @Test
  fun `a failed delegation tells the agent to do the work itself`() {
    val result = delegate(
      RecordingLauncher(SubagentOutcome(success = false, error = "No selected model with a usable API key.")),
      """{"description":"trace","prompt":"find it"}"""
    )
    assertFalse(result.success)
    assertTrue(result.error!!, result.error!!.contains("No selected model with a usable API key."))
    assertTrue(result.error!!, result.error!!.contains("Do the work yourself"))
    // A failure is not a report: nothing pretends the sub-agent answered.
    assertEquals("", result.output)
  }

  @Test
  fun `an empty report is returned as empty, not invented`() {
    val result = delegate(
      RecordingLauncher(SubagentOutcome(success = true, summary = "")),
      """{"description":"quick look","prompt":"count the files"}"""
    )
    assertTrue(result.success)
    assertTrue(result.output, result.output.endsWith("Report:\n"))
  }

  @Test
  fun `arguments are schema-checked before anything is launched`() {
    val launcher = RecordingLauncher()
    for (json in listOf("{}", """{"description":"only this"}""", """{"prompt":"only this"}""")) {
      val failure = runCatching {
        runBlocking {
          SubagentTool(launcher).parseAndValidate(json)
        }
      }.exceptionOrNull()
      assertTrue("$json must be rejected", failure is ToolArgumentError)
    }
    assertEquals("nothing reaches the launcher with bad args", 0, launcher.calls)
  }

  @Test
  fun `the tool is named for what it does and says what it cannot do`() {
    val tool = SubagentTool(RecordingLauncher())
    assertEquals("delegate", tool.name)
    assertEquals(listOf("description", "prompt"), tool.params.map { it.name })
    assertTrue(tool.description, tool.description.contains("cannot change anything"))
    assertTrue(tool.description, tool.description.contains("none of this conversation"))
    // Delegation is not implementation.
    assertTrue(tool.description, tool.description.contains("Do not use it for a change"))
  }

  // ---- What the child may use ----

  @Test
  fun `delegate is offered only when a runtime can run it`() {
    assertNull(registryWith(null).get("delegate"))
    assertNotNull(registryWith(RecordingLauncher()).get("delegate"))
  }

  @Test
  fun `a delegated run is offered nothing that can change the workspace`() {
    val childRegistry = registryWith(RecordingLauncher()).forDelegation()
    val names = childRegistry.tools.map { it.name }.toSet()

    // Every offered tool is one the planning gate allows...
    assertTrue("offered beyond the planning set: ${names - PlanMode.delegatedToolNames}",
      names.all { it in PlanMode.delegatedToolNames })
    // ...and a sub-agent cannot fork itself.
    assertFalse("delegate", "delegate" in names)
    for (mutating in listOf(
      "edit_file", "edit_files", "write_file", "create_file", "delete_file", "move_file",
      "copy_file", "create_directory", "git_stage", "git_commit", "build", "test",
      "write_terminal_input"
    )) {
      assertFalse("must not be offered: $mutating", mutating in names)
      assertNull(childRegistry.get(mutating))
    }
    // Research stays available, which is the whole point of delegating.
    for (reading in listOf("read_file", "read_files", "search_files", "glob_files", "list_files", "git_log", "web_search")) {
      assertTrue("must be offered: $reading", reading in names)
    }
    // run_command is offered because the gate keeps it read-only, not because it is safe.
    assertTrue(names.contains("run_command"))
  }

  @Test
  fun `the child policy researches and never writes`() {
    val child = SubagentTool.childPermissions(
      AgentPermissions(
        planMode = false,
        fileEditing = PermissionMode.ALLOW_ALL,
        terminalCommands = PermissionMode.ALLOW_ALL,
        deleteFiles = true
      )
    )
    assertTrue(child.planMode)
    assertEquals(PermissionMode.NEVER_ALLOW, child.fileEditing)
    assertFalse(child.deleteFiles)
    assertEquals(SubagentTool.MAX_CHILD_ITERATIONS, child.maxToolIterations)
    assertEquals(PermissionMode.ALLOW_SAFE, child.terminalCommands)

    // A user who forbade commands is not overridden by delegation.
    val strict = SubagentTool.childPermissions(AgentPermissions(terminalCommands = PermissionMode.NEVER_ALLOW))
    assertEquals(PermissionMode.NEVER_ALLOW, strict.terminalCommands)
  }

  @Test
  fun `the brief tells the sub-agent to report instead of converse`() {
    val brief = SubagentTool.brief("Find every caller of legacyAuth().")
    assertTrue(brief, brief.startsWith("Find every caller of legacyAuth()."))
    assertTrue(brief, brief.contains("You are a sub-agent"))
    assertTrue(brief, brief.contains("You cannot change anything"))
    // The report has to stand on its own for the agent that asked.
    assertTrue(brief, brief.contains("file paths"))
    assertTrue(brief, brief.contains("under about 400 words"))
    assertTrue(brief, brief.contains("Do not narrate your steps"))
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

  private class PairingRun(val dir: File, val service: ScriptedService, val events: List<AgentStreamEvent>)

  /**
   * Parent and child share one [LlmService], so the script below is the whole
   * conversation of both: the parent delegates, the child tries to edit and
   * reports, the parent answers.
   */
  private fun delegationRun(): PairingRun {
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
        Turn.Call("delegate", """{"description":"trace the value","prompt":"Find where value is read."}"""),
        // The sub-agent, told to report, reaches for a write anyway.
        Turn.Call("edit_file", """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""),
        // So it tries the one command that is offered to it and still forbidden.
        Turn.Call("run_command", """{"command":"npm install left-pad"}"""),
        // And it tries to pull the user into a run that owns no dialog.
        Turn.Call("ask_user", """{"question":"which package?","options":["left-pad","center-pad"]}"""),
        Turn.Answer("Report: value is read at src/App.tsx:1."),
        Turn.Answer("The report is in.")
      )
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
      subagentLauncher = SubagentLauncher { _, prompt, childProject, childTerminal ->
        runtime!!.runSubagent(
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
          // the same stream the parent reports on — exactly how the repository wires it.
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

  @Test
  fun `a sub-agent cannot edit even when the parent could`() {
    val run = delegationRun()
    // The delegated turn wanted to write; the workspace stayed as it was.
    assertEquals("export const value = 1\n", File(run.dir, "src/App.tsx").readText())
    assertEquals(6, run.service.requests.size)

    // First defence: the tool is not even in the child's list.
    val unknown = run.service.requests[2].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(unknown, unknown.contains("is not an available tool"))
    assertFalse(unknown, unknown.contains("write_file,"))

    // Second defence, for what is offered: the plan-mode gate keeps run_command read-only.
    val refused = run.service.requests[3].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(refused, refused.contains("only read-only shell commands"))

    // The parent still got its report, so the run was not wasted.
    val report = run.service.requests.last().messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(report, report.contains("Delegated task (trace the value) finished"))
    assertTrue(report, report.contains("value is read at src/App.tsx:1"))
  }

  @Test
  fun `the sub-agent starts from the brief and no history`() {
    val run = delegationRun()
    val childFirst = run.service.requests[1]
    val briefMessage = childFirst.messages.first { it.role == LlmRole.USER }.content
    assertTrue(briefMessage, briefMessage.contains("Find where value is read."))
    assertTrue(briefMessage, briefMessage.contains("You are a sub-agent"))
    // Only its own system prompt and brief: the parent's conversation is not resent.
    assertEquals(2, childFirst.messages.size)

    // The child is offered the reading tools, never the writing ones.
    val offered = childFirst.tools.map { it.name }.toSet()
    assertTrue(offered.toString(), "read_file" in offered)
    assertFalse(offered.toString(), "edit_file" in offered)
    assertFalse(offered.toString(), "delegate" in offered)

    // And the parent's own tool list still has delegation.
    val parentOffered = run.service.requests[0].tools.map { it.name }.toSet()
    assertTrue(parentOffered.toString(), "delegate" in parentOffered)
    assertTrue(parentOffered.toString(), "edit_file" in parentOffered)
  }

  @Test
  fun `the sub-agent works in the background instead of talking over the answer`() {
    val run = delegationRun()
    val status = run.events.filterIsInstance<AgentStreamEvent.Status>().map { it.text }
    assertTrue(status.toString(), status.any { it.startsWith("Sub-agent") })

    // The child's text is a tool result for the parent, never streamed as the answer.
    val streamed = run.events.filterIsInstance<AgentStreamEvent.Token>().map { it.text }
    assertFalse(streamed.toString(), streamed.any { it.contains("Report: value is read") })
    assertTrue(streamed.toString(), streamed.contains("The report is in."))

    // The delegation shows up as one tool of the parent's own turn.
    val delegation = run.events.filterIsInstance<AgentStreamEvent.ToolFinished>().single { it.name == "delegate" }
    assertTrue(delegation.summary, delegation.success)
  }

  @Test
  fun `a sub-agent never gets the user's approval dialog`() {
    val run = delegationRun()
    // The child did ask; the ask was answered without a dialog, so the turn could
    // not hang waiting for a user who is watching the parent.
    val askOutcome = run.service.requests[4].messages.last { it.role == LlmRole.TOOL }.content
    assertTrue(askOutcome, askOutcome.contains("did not answer") || askOutcome.contains("not approved"))
    assertTrue(
      "no approval may reach the parent from a delegated run",
      run.events.none { it is AgentStreamEvent.ApprovalRequested }
    )
    // and the run still finished with a report.
    assertTrue(run.service.requests.last().messages.last { it.role == LlmRole.TOOL }.content.contains("Report:"))
  }
}
