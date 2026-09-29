package com.agentisco

import androidx.test.core.app.ApplicationProvider
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
import com.agentisco.agent.tool.SubagentTool
import com.agentisco.data.local.CustomAgentStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Custom agents: a user's own seat on the team.
 *
 * The claim being tested is that "custom" is not a second-class mode. A saved
 * agent is an [AgentRole] like any built-in one, so it is addressed by id in
 * `delegate`, gets a system prompt of its own, is bounded by whatever tools the
 * user checked, and is still held to the team rules and the reporting contract
 * that keep concurrent agents from trampling each other.
 *
 * Two properties matter most and are proven rather than assumed: a role's tool
 * list can only ever narrow what a delegated run may do - never grant `delegate`
 * or a tool that does not exist - and the restriction is enforced by the tools the
 * run is actually handed, not by wording in its prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomAgentTest {

  private val ws = newWorkspace("custom")

  @Before
  fun clearStoredAgents() {
    val dir = ApplicationProvider.getApplicationContext<android.content.Context>().getDir("agentisco", android.content.Context.MODE_PRIVATE)
    File(dir, "custom_agents.json").delete()
  }

  @After
  fun cleanUp() {
    ws.dispose()
  }

  private fun embedded(
    scope: String = "app/src/main/java/**/termux/",
    toolNames: List<String> = emptyList(),
    readOnly: Boolean = false,
    modelId: String = "",
    purpose: String = "Fix the terminal layer when a command behaves oddly."
  ) = AgentRoles.custom(
    id = "embedded",
    name = "Embedded Specialist",
    purpose = purpose,
    systemPrompt = "You own the proot terminal layer of this app. Change only what a real device would show.",
    scope = scope,
    toolNames = toolNames,
    modelId = modelId,
    readOnly = readOnly
  )

  // ---- The prompt a user writes ----

  @Test
  fun `a custom agent speaks with the user's own words`() {
    val prompt = embedded().prompt()
    // The built-in identity block is replaced, not appended to.
    assertTrue(prompt, prompt.startsWith("You own the proot terminal layer of this app."))
    assertFalse(prompt, prompt.contains("Your responsibility:"))
    assertFalse(prompt, prompt.contains("How you work:"))
    // Its scope is stated to it, as a limit and not as a hint.
    assertTrue(prompt, prompt.contains("Your scope in this project: app/src/main/java/**/termux/"))
    assertTrue(prompt, prompt.contains("Stay inside it."))
  }

  /** The team contract is not optional: a custom agent works beside the others. */
  @Test
  fun `a custom agent still owes the team its discipline and its report`() {
    val prompt = embedded().prompt()
    assertTrue(prompt, prompt.contains("Other agents may be working in this same workspace while you work."))
    assertTrue(prompt, prompt.contains("Verify only what is yours"))
    assertTrue(prompt, prompt.contains("Files you modified"))
    // It is an engineer unless the user says it may not change anything.
    assertTrue(prompt, prompt.contains("You implement, not just recommend"))
    assertFalse(prompt, prompt.contains("You are read-only"))

    val research = embedded(readOnly = true).prompt()
    assertTrue(research, research.contains("You are read-only"))
    assertTrue(research, research.contains("The evidence"))
  }

  @Test
  fun `a role with no system prompt keeps the built-in identity`() {
    val general = AgentRoles.GENERAL
    assertFalse(general.isCustom)
    assertTrue(general.identity(), general.identity().contains("You are the General Engineer agent"))
    assertTrue(general.identity(), general.identity().contains("Your responsibility:"))

    val withBlankBrief = embedded().copy(systemPrompt = "   ")
    assertFalse(withBlankBrief.isCustom)
  }

  // ---- The roster ----

  @Test
  fun `a custom agent joins the team it can be delegated to`() {
    val roster = AgentRoles.roster(listOf(embedded()))
    assertEquals(roster.toString(), AgentRoles.builtIn.size + 1, roster.size)
    assertEquals(roster.take(AgentRoles.builtIn.size), AgentRoles.builtIn)
    assertNotNull(AgentRoles.resolve(roster, "embedded"))
    // Case-insensitive, exactly like a built-in seat.
    assertEquals("embedded", AgentRoles.resolve(roster, "Embedded")!!.id)
  }

  @Test
  fun `a custom agent cannot take a built-in seat's name`() {
    val roster = AgentRoles.roster(listOf(embedded().copy(id = "backend", name = "My Backend")))
    assertEquals(roster.toString(), AgentRoles.builtIn.size, roster.size)
    assertEquals(AgentRoles.BACKEND.name, AgentRoles.resolve(roster, "backend")!!.name)
  }

  @Test
  fun `every seat on the roster is a seat the delegate tool offers`() {
    val roster = AgentRoles.roster(listOf(embedded()))
    val launcher = object : SubagentLauncher {
      override suspend fun launch(
        role: AgentRole,
        description: String,
        prompt: String,
        project: Project,
        terminal: TerminalSession,
        delegationId: String
      ) = com.agentisco.agent.tool.SubagentOutcome(success = true, summary = "done")
    }
    val tool = SubagentTool(launcher, roster = { roster })
    // The one line the delegating agent reads to choose: id, purpose, and where it works.
    assertTrue(tool.description, tool.description.contains("embedded: Fix the terminal layer when a command behaves oddly. (works in: app/src/main/java/**/termux/)"))
    val result = runBlocking {
      tool.execute(tool.parseAndValidate("""{"role":"embedded","description":"terminal hangs","prompt":"investigate"}"""), contextFor(ws))
    }
    assertTrue(result.error ?: result.output, result.success)
  }

  // ---- What the user's tool list may and may not do ----

  private fun registryWith(roles: () -> List<AgentRole>): AgentToolRegistry {
    val fileSystem = ProjectFileSystem(File(ws.root, "fsbase_${System.nanoTime()}"))
    return AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") },
      terminalManager = TerminalProcessManager { null },
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {},
      subagentRoles = roles
    )
  }

  private fun namesFor(role: AgentRole) = registryWith({ listOf(role) }).forDelegation(role).tools.map { it.name }.toSet()

  @Test
  fun `an engineer agent gets the working set when the user names no tools`() {
    val given = namesFor(embedded())
    assertTrue(given.contains("edit_file"))
    assertTrue(given.contains("run_command"))
    assertTrue(given.contains("build"))
    // Never a second level of delegation, however the role is configured.
    assertFalse(given.toString(), given.contains("delegate"))
  }

  /**
   * The settings editor lists exactly [AgentToolRegistry.delegableToolNames], so
   * the round trip is what keeps the two honest: what a user can check is what a
   * run may be handed, and checking nothing gives precisely that list.
   */
  @Test
  fun `the checklist the editor shows is what a delegated run may hold`() {
    val registry = registryWith({ emptyList() })
    val working = registry.delegableToolNames(readOnly = false)
    val research = registry.delegableToolNames(readOnly = true)
    assertEquals(working, namesFor(embedded()))
    assertEquals(research, namesFor(embedded(readOnly = true)))
    assertEquals(PlanMode.delegatedToolNames, research)
    assertFalse(working.toString(), working.contains("delegate"))
  }

  @Test
  fun `the user's tool list narrows the run and nothing else`() {
    val given = namesFor(embedded(toolNames = listOf("read_file", "search_files", "run_command")))
    assertEquals(setOf("read_file", "search_files", "run_command"), given)
  }

  /** The fail-safe direction: a checked list is a ceiling, not a grant. */
  @Test
  fun `a tool list can never name something a sub-agent may not have`() {
    val given = namesFor(embedded(toolNames = listOf("read_file", "delegate", "no_such_tool", "  ", "git_commit")))
    assertEquals(setOf("read_file", "git_commit"), given)
    assertFalse(given.toString(), given.contains("delegate"))
  }

  @Test
  fun `a read-only custom agent is still bounded by the planning gate`() {
    val given = namesFor(embedded(readOnly = true, toolNames = listOf("read_file", "edit_file", "run_command")))
    // edit_file is checked in the UI but the gate is what decides.
    assertEquals(setOf("read_file", "run_command"), given)
    assertTrue(given.toString(), PlanMode.delegatedToolNames.containsAll(given))
  }

  @Test
  fun `a custom agent runs under the user's permissions, never above them`() {
    val parent = AgentPermissions(
      planMode = false,
      fileEditing = PermissionMode.ALWAYS_ASK,
      terminalCommands = PermissionMode.ALWAYS_ASK,
      deleteFiles = false
    )
    val engineer = SubagentTool.childPermissions(embedded(), parent)
    assertFalse(engineer.planMode)
    // Asking is the one thing a delegated run cannot do by itself, so an ask it
    // would have made becomes a go. What the user actually withheld - deleting,
    // pushing - stays withheld.
    assertEquals(PermissionMode.ALLOW_ALL, engineer.fileEditing)
    assertEquals(PermissionMode.ALLOW_ALL, engineer.terminalCommands)
    assertFalse(engineer.deleteFiles)
    assertFalse(engineer.gitPush)

    // A capability forbidden outright is not reinstated by delegation.
    val forbidden = SubagentTool.childPermissions(
      embedded(),
      parent.copy(
        fileEditing = PermissionMode.NEVER_ALLOW,
        terminalCommands = PermissionMode.NEVER_ALLOW
      )
    )
    assertEquals(PermissionMode.NEVER_ALLOW, forbidden.fileEditing)
    assertEquals(PermissionMode.NEVER_ALLOW, forbidden.terminalCommands)

    val researcher = SubagentTool.childPermissions(embedded(readOnly = true), parent)
    assertTrue(researcher.planMode)
    assertEquals(PermissionMode.NEVER_ALLOW, researcher.fileEditing)
  }

  // ---- Persistence ----

  @Test
  fun `a saved agent comes back as the same seat`() {
    val store = CustomAgentStore(ApplicationProvider.getApplicationContext<android.content.Context>())
    val saved = store.save(embedded(toolNames = listOf("read_file", "run_command"), modelId = "m-1"))
    assertEquals(saved.toString(), 1, saved.size)

    val reread = CustomAgentStore(ApplicationProvider.getApplicationContext<android.content.Context>()).get()
    val agent = reread.single()
    assertEquals("embedded", agent.id)
    assertEquals("Embedded Specialist", agent.name)
    assertEquals("You own the proot terminal layer of this app. Change only what a real device would show.", agent.systemPrompt)
    assertEquals(listOf("read_file", "run_command"), agent.toolNames)
    assertEquals("m-1", agent.modelId)
    assertFalse(agent.builtIn)
    assertTrue(agent.isCustom)
  }

  @Test
  fun `saving twice replaces the same agent instead of duplicating it`() {
    val store = CustomAgentStore(ApplicationProvider.getApplicationContext<android.content.Context>())
    store.save(embedded().copy(name = "First Name"))
    val updated = store.save(embedded().copy(name = "Renamed Specialist"))
    assertEquals(updated.toString(), 1, updated.size)
    assertEquals("Renamed Specialist", updated.single().name)
    assertTrue(store.remove("embedded").isEmpty())
    assertTrue(store.get().isEmpty())
  }

  @Test
  fun `an agent the app cannot run is never stored`() {
    val store = CustomAgentStore(ApplicationProvider.getApplicationContext<android.content.Context>())
    val result = store.set(
      listOf(
        embedded(),
        embedded().copy(id = "no-brief", name = "No Brief", purpose = "x", systemPrompt = ""),
        embedded().copy(id = "no-name", name = "  "),
        embedded().copy(id = "backend", name = "Shadow Backend", systemPrompt = "Owns the API.")
      )
    )
    // No brief or no name is not an agent, so it is never stored and never offered.
    assertEquals(result.toString(), 2, result.size)
    assertEquals(listOf("embedded", "backend-2"), result.map { it.id })
  }

  /** A new agent from the editor has no id yet - the name is what it is addressed by. */
  @Test
  fun `a new agent takes its id from its name and keeps it when edited`() {
    val store = CustomAgentStore(null)
    val created = store.save(
      AgentRoles.custom(id = "", name = "DB Reviewer", purpose = "", systemPrompt = "Review migrations before they merge.")
    ).single()
    assertEquals("db-reviewer", created.id)
    // Editing it must not mint a second seat with a new name-for-it.
    val edited = store.save(created.copy(name = "Migration Reviewer")).single()
    assertEquals("db-reviewer", edited.id)
    assertEquals("Migration Reviewer", edited.name)
  }

  @Test
  fun `normalizing makes an agent addressable and sane`() {
    val normalized = CustomAgentStore.normalize(
      listOf(
        AgentRoles.custom(
          id = "  Docs Person!!  ",
          name = " Docs Person ",
          purpose = "  ",
          systemPrompt = "  Update the README. \nNever touch code. ",
          scope = "  docs/ ",
          toolNames = listOf(" read_file ", "read_file", "", "glob_files"),
          maxToolIterations = 999
        )
      )
    ).single()

    assertEquals("docs-person", normalized.id)
    assertEquals("Docs Person", normalized.name)
    // A role the delegating agent cannot read is useless: the brief is the fallback.
    assertEquals("Update the README.", normalized.purpose)
    assertEquals("Update the README. \nNever touch code.", normalized.systemPrompt)
    assertEquals("docs/", normalized.scope)
    assertEquals(listOf("read_file", "glob_files"), normalized.toolNames)
    // A number is a budget the user chose, so it is kept — bounded, rather than
    // letting a mistyped field allow 999 rounds.
    assertEquals(600, normalized.maxToolIterations)
    assertFalse(normalized.builtIn)
  }

  @Test
  fun `an id already on the team is given a suffix instead of a fight`() {
    val normalized = CustomAgentStore.normalize(
      listOf(
        embedded().copy(id = "qa", name = "One"),
        embedded().copy(id = "qa", name = "Two"),
        embedded().copy(id = "My Tests", name = "Three")
      )
    )
    assertEquals(listOf("qa-2", "qa-3", "my-tests"), normalized.map { it.id })
  }

  @Test
  fun `a store with no context still keeps the team for this session`() {
    val store = CustomAgentStore(null)
    assertEquals(store.get().toString(), 1, store.save(embedded()).size)
    assertEquals("embedded", store.get().single().id)
    assertTrue(store.remove("embedded").isEmpty())
  }

  // ---- Through the real runtime ----

  private val provider =
    AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 60_000, capabilities = ModelCapabilities(tools = true)
  )

  private sealed class Turn {
    data class Call(val name: String, val args: String) : Turn()
    data class Answer(val text: String) : Turn()
  }

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

  /** Parent delegates to [role]; the child's turns are [childTurns]. */
  private class CustomRun(val dir: File, val service: ScriptedService, val events: List<AgentStreamEvent>) {
    /** One tool call in the child, then its answer: requests are parent, child, child, parent. */
    val childSystem get() = service.requests[1].messages.first { it.role == LlmRole.SYSTEM }.content
    val childToolReply get() = service.requests[2].messages.last { it.role == LlmRole.TOOL }.content
    val parentReport get() = service.requests[3].messages.last { it.role == LlmRole.TOOL }.content
  }

  private fun customRun(role: AgentRole, childTurns: List<Turn>): CustomRun {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    File(dir, "src/App.tsx").writeText("export const value = 1\n")

    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath)
    val terminal = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath)

    val service = ScriptedService(
      listOf(Turn.Call("delegate", """{"role":"${role.id}","description":"fix it","prompt":"set value to 2"}""")) +
        childTurns + Turn.Answer("Report received.")
    )

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
      subagentRoles = { AgentRoles.roster(listOf(role)) },
      subagentLauncher = SubagentLauncher { childRole, description, prompt, childProject, childTerminal, delegationId ->
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
          delegationId = delegationId,
          onEvent = { event -> events.add(event) }
        )
      }
    )
    val parent = AgentRuntime(fileSystem, terminals, git, service, registry) { null }
    runtime = parent

    runBlocking {
      parent.executeTask(
        prompt = "hand it to the specialist",
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
    return CustomRun(dir, service, events)
  }

  @Test
  fun `a custom agent runs its own brief through the real runtime`() {
    val run = customRun(
      embedded(toolNames = listOf("read_file", "edit_file")),
      listOf(
        Turn.Call("edit_file", """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""),
        Turn.Answer("Report: value is now 2.")
      )
    )
    assertEquals("export const value = 2\n", File(run.dir, "src/App.tsx").readText())
    assertTrue(run.childSystem, run.childSystem.contains("You own the proot terminal layer of this app."))
    assertTrue(run.childSystem, run.childSystem.contains("Your scope in this project:"))
    // The general playbook is still there: a custom brief does not remove the tool rules.
    assertTrue(run.childSystem, run.childSystem.contains("old_string must be copied verbatim"))
  }

  @Test
  fun `a tool the user did not check is not there to be called`() {
    val run = customRun(
      embedded(toolNames = listOf("read_file")),
      listOf(
        Turn.Call("run_command", """{"command":"ls -la"}"""),
        Turn.Answer("Report: I could not run anything.")
      )
    )
    // Refused because it was never offered, which is the point: the run cannot be
    // talked into a tool the user did not check, and the parent still gets a report.
    assertTrue(run.childToolReply, run.childToolReply.contains("run_command\" is not an available tool"))
    assertEquals("export const value = 1\n", File(run.dir, "src/App.tsx").readText())
    assertFalse(run.childSystem, run.childSystem.contains("You are read-only"))
    // The parent is told what happened instead of guessing from a silence.
    assertTrue(run.parentReport, run.parentReport.contains("Report from the Embedded Specialist"))
  }

  @Test
  fun `a custom read-only agent is refused a change it tried to make`() {
    val run = customRun(
      embedded(readOnly = true, toolNames = listOf("read_file", "edit_file")),
      listOf(
        Turn.Call("edit_file", """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""),
        Turn.Answer("Report: nothing was changed.")
      )
    )
    assertTrue(run.childToolReply, run.childToolReply.contains("edit_file\" is not an available tool"))
    assertEquals("export const value = 1\n", File(run.dir, "src/App.tsx").readText())
    assertTrue(run.childSystem, run.childSystem.contains("You are read-only"))
  }
}
