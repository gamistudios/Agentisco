package com.agentisco

import com.agentisco.agent.llm.LlmErrorKind
import com.agentisco.agent.llm.LlmException
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
import com.agentisco.agent.runtime.AgentTeamBoard
import com.agentisco.agent.runtime.AgentWorkStatus
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.SubagentLauncher
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live team board: who is in this workspace, on what, holding which files.
 *
 * Several agents sharing one directory is only safe while each one knows the
 * others exist, so this is the piece that turns delegation into a team. What is
 * proven here is that the knowledge is real rather than boilerplate: an agent is
 * shown the files another run has actually changed (the claim is that run's own
 * live set, so it grows as it works), is never shown its own seat, and is taken
 * off the board as soon as its run ends - by every way a run can end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTeamBoardTest {

  private val ws = newWorkspace("team")

  @After
  fun cleanUp() {
    ws.dispose()
  }

  private fun liveSet(vararg paths: String) =
    java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(paths.toList()) }

  private fun AgentTeamBoard.seatLine(prefix: String) =
    promptBlock(excludingId = null).lines().firstOrNull { it.startsWith("- $prefix") }

  // ---- What a seat shows ----

  @Test
  fun `a seat is labelled by its role, its task and the files it holds`() {
    val board = AgentTeamBoard()
    val seat = board.join(
      role = AgentRoles.FRONTEND,
      task = "rebuild   the\ncomposer",
      delegated = true,
      files = liveSet("src/api/client.ts", "src/App.tsx")
    )

    val line = board.seatLine("Frontend Engineer")
    assertNotNull(line)
    assertEquals("- Frontend Engineer (frontend): rebuild the composer — files: src/App.tsx, src/api/client.ts", line)
    // One bullet per agent, however the brief that produced it was written.
    assertEquals(board.promptBlock(excludingId = null), 1, board.promptBlock(excludingId = null).lines().count { it.startsWith("-") })
    assertEquals("agent-1", seat.id)

    val view = board.snapshot().single()
    assertEquals(AgentRoles.FRONTEND.id, view.roleId)
    assertEquals(AgentRoles.FRONTEND.name, view.roleName)
    assertTrue(view.delegated)
    assertEquals(AgentWorkStatus.RUNNING, view.status)
    assertEquals(listOf("src/App.tsx", "src/api/client.ts"), view.files)
  }

  /** The claim is the other run's live set, not a copy: work shows up as it lands. */
  @Test
  fun `a file appears in the team block the moment the other agent changes it`() {
    val board = AgentTeamBoard()
    val files = liveSet()
    board.join(role = AgentRoles.BACKEND, task = "add the sessions table", delegated = true, files = files)

    assertTrue(board.promptBlock(excludingId = null), board.promptBlock(excludingId = null).contains("files: none yet"))

    files.add("server/db/schema.ts")
    val block = board.promptBlock(excludingId = null)
    assertTrue(block, block.contains("server/db/schema.ts"))
    assertFalse(block, block.contains("none yet"))
  }

  @Test
  fun `an agent is never told to keep out of its own files`() {
    val board = AgentTeamBoard()
    val mine = board.join(role = AgentRoles.QA, task = "cover the parser", delegated = true, files = liveSet("src/parse.ts"))
    board.join(role = AgentRoles.SECURITY, task = "audit the auth path", delegated = true, files = liveSet("src/auth.ts"))

    val block = board.promptBlock(excludingId = mine.id)
    assertFalse(block, block.contains("QA / Test Engineer"))
    assertFalse(block, block.contains("src/parse.ts"))
    assertTrue(block, block.contains("Security Engineer"))
    assertTrue(block, block.contains("src/auth.ts"))
  }

  @Test
  fun `the rule that comes with the list is a rule about not clobbering`() {
    val board = AgentTeamBoard()
    board.join(role = AgentRoles.UIUX, task = "theme the settings screen", delegated = true, files = liveSet("src/theme.ts"))
    val block = board.promptBlock(excludingId = null)
    assertTrue(block, block.contains("UI / UX Designer (uiux)"))
    // It says who is working, and what to do instead of overwriting them.
    assertTrue(block, block.contains("Never revert, reformat or"))
    assertTrue(block, block.contains("re-read a listed file before touching it"))
    // The delegating agent's own changes are the state to continue from, not a wall.
    assertTrue(block, block.contains("The agent that delegated your work is in this list too"))
    // And it says when it was true, because a stale list would be trusted as current.
    assertTrue(block, block.contains("when you started"))
  }

  @Test
  fun `an empty board says the workspace is unclaimed`() {
    val alone = AgentTeamBoard().promptBlock(excludingId = null)
    assertTrue(alone, alone.contains("no other agent was working"))
    assertFalse(alone, alone.lines().any { it.startsWith("- ") })
  }

  @Test
  fun `the main agent is listed as the one that delegated the work`() {
    val board = AgentTeamBoard()
    board.join(role = null, task = "ship the composer rewrite", delegated = false, files = liveSet("src/Composer.tsx"))
    val view = board.snapshot().single()
    assertEquals("main", view.roleId)
    assertEquals("Main agent", view.roleName)
    assertFalse(view.delegated)
    val line = board.seatLine("Main agent")
    assertNotNull(line)
    assertTrue(line!!, line.contains("ship the composer rewrite"))
  }

  // ---- Size, because a system prompt is not a dashboard ----

  @Test
  fun `a long task and a long file list are cut short`() {
    val board = AgentTeamBoard()
    val files = (1..15).map { "src/m${it.toString().padStart(2, '0')}.ts" }
    board.join(role = AgentRoles.GENERAL, task = "x".repeat(400), delegated = true, files = liveSet(*files.toTypedArray()))

    val line = board.seatLine("General")!!
    assertTrue(line, line.contains("src/m01.ts"))
    assertTrue(line, line.contains("(+5 more)"))
    assertFalse(line, line.contains("src/m11.ts"))
    assertTrue(line, line.contains("…"))
    assertFalse(line, line.contains("x".repeat(200)))
  }

  @Test
  fun `more agents than the block can show are counted, not dropped silently`() {
    val board = AgentTeamBoard()
    val self = board.join(role = AgentRoles.EXPLORE, task = "map the repo", delegated = true, files = liveSet())
    repeat(10) { i -> board.join(role = AgentRoles.FRONTEND, task = "task $i", delegated = true, files = liveSet("f$i.ts")) }

    val block = board.promptBlock(excludingId = self.id)
    assertEquals(block, 8, block.lines().count { it.startsWith("- Frontend Engineer") })
    assertTrue(block, block.contains("(2 more agents are running"))
  }

  // ---- Closing a seat ----

  @Test
  fun `a finished agent stops claiming files`() {
    val board = AgentTeamBoard()
    val seat = board.join(role = AgentRoles.DEBUGGER, task = "fix the flaky test", delegated = true, files = liveSet("test/app.spec.ts"))
    seat.finish(AgentWorkStatus.DONE)

    val block = board.promptBlock(excludingId = null)
    assertFalse(block, block.contains("Debugger"))
    // The user just watched it, so the board still remembers; the agents do not.
    assertEquals(AgentWorkStatus.DONE, board.snapshot().single().status)
  }

  @Test
  fun `the board remembers only recent finished agents`() {
    val board = AgentTeamBoard()
    val open = board.join(role = AgentRoles.GENERAL, task = "still working", delegated = true, files = liveSet())
    repeat(10) { i -> board.join(role = AgentRoles.QA, task = "run $i", delegated = true, files = liveSet()).finish(AgentWorkStatus.FAILED) }

    val views = board.snapshot()
    assertEquals(views.toString(), 7, views.size)
    assertTrue(views.toString(), views.any { it.id == open.id && it.status == AgentWorkStatus.RUNNING })
    assertEquals(views.toString(), 6, views.count { it.status == AgentWorkStatus.FAILED })
  }

  @Test
  fun `ids count up per board, so two workspaces never name the same seat`() {
    val a = AgentTeamBoard()
    val b = AgentTeamBoard()
    fun AgentTeamBoard.joinOne(task: String) = join(role = AgentRoles.GENERAL, task = task, delegated = true, files = liveSet())
    assertEquals("agent-1", a.joinOne("one").id)
    assertEquals("agent-2", a.joinOne("two").id)
    assertEquals("agent-1", b.joinOne("other workspace").id)
  }

  // ---- Through two real runtimes ----

  private val provider =
    AIProvider("prov", "Local", "https://example.com/v1", LLMProtocol.OPENAI_CHAT_COMPLETIONS)
  private val model = AIModel(
    id = "m", providerId = "prov", modelId = "test", displayName = "Test",
    contextWindow = 60_000, capabilities = ModelCapabilities(tools = true)
  )

  private sealed class Turn {
    data class Call(val name: String, val args: String) : Turn()
    data class Answer(val text: String) : Turn()
    data class Break(val kind: LlmErrorKind) : Turn()
  }

  /** Parent and child share one service, so one script drives both runs. */
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
        is Turn.Break -> onEvent(LlmStreamEvent.Failed(LlmException("provider said no", turn.kind)))
      }
      index++
    }
  }

  private class TeamRun(val dir: File, val board: AgentTeamBoard, val service: ScriptedService) {
    /** The system prompt of the nth model request - the parent's or the child's. */
    fun systemOf(requestIndex: Int): String =
      service.requests[requestIndex].messages.first { it.role == LlmRole.SYSTEM }.content

    fun seat(roleName: String) = board.snapshot().first { it.roleName == roleName }
  }

  /**
   * A run that edits one file and then delegates work in another: the shape of a
   * real turn, so the board is observed through the runtime's own bookkeeping
   * rather than a hand-built seat.
   *
   * Requests land in this order: parent, parent (with its edit result), child,
   * child (with its own result), parent (with the report).
   */
  private fun teamRun(role: AgentRole, childTurns: List<Turn>): TeamRun {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    File(dir, "src/App.tsx").writeText("export const value = 1\n")

    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath)
    val terminal = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath)
    val board = AgentTeamBoard()

    val service = ScriptedService(
      listOf(
        Turn.Call("edit_file", """{"path":"src/App.tsx","old_string":"value = 1","new_string":"value = 2"}"""),
        Turn.Call("delegate", """{"role":"${role.id}","description":"split the server","prompt":"Add server/db/schema.ts."}""")
      ) + childTurns + Turn.Answer("Both changes are in.")
    )

    val events = mutableListOf<AgentStreamEvent>()
    // The launcher needs the runtime it belongs to, so it resolves it lazily.
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
          onEvent = { event -> events.add(event) }
        )
      }
    )
    val parent = AgentRuntime(fileSystem, terminals, git, service, registry, board) { null }
    runtime = parent

    runBlocking {
      runCatching {
        parent.executeTask(
          prompt = "rework\nthe   composer",
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
    }
    return TeamRun(dir, board, service)
  }

  private val childEdits = listOf(
    Turn.Call("write_file", """{"path":"server/db/schema.ts","content":"export const table = 1\n"}"""),
    Turn.Answer("Report: schema added.")
  )

  @Test
  fun `the specialist is shown what the agent that delegated to it has changed`() {
    val run = teamRun(AgentRoles.BACKEND, childEdits)

    val childPrompt = run.systemOf(2)
    assertTrue(childPrompt, childPrompt.contains("The workspace team as it was when you started"))
    val mainLine = childPrompt.lines().first { it.startsWith("- Main agent") }
    // The file the parent really edited, read off the parent's own bookkeeping.
    assertTrue(mainLine, mainLine.contains("src/App.tsx"))
    assertTrue(mainLine, mainLine.contains("rework the composer"))

    // Both changes are real work on disk, not just claims.
    assertEquals("export const value = 2\n", File(run.dir, "src/App.tsx").readText())
    assertEquals("export const table = 1\n", File(run.dir, "server/db/schema.ts").readText())
    // Nothing is left holding either file once the turn is over.
    assertTrue(run.board.promptBlock(excludingId = null), run.board.promptBlock(excludingId = null).contains("no other agent was working"))
  }

  @Test
  fun `a specialist is not shown its own seat while it works`() {
    val run = teamRun(AgentRoles.BACKEND, childEdits)
    val childPrompt = run.systemOf(2)
    // Its identity is in the role block; it must not also be listed as a teammate.
    assertTrue(childPrompt, childPrompt.contains("You are the Backend Engineer agent"))
    assertFalse(childPrompt, childPrompt.lines().any { it.startsWith("- Backend Engineer") })
    assertFalse(childPrompt, childPrompt.contains("server/db/schema.ts —"))
  }

  @Test
  fun `the first agent of a workspace is told nothing is claimed yet`() {
    val run = teamRun(AgentRoles.BACKEND, childEdits)
    val parentPrompt = run.systemOf(0)
    assertTrue(parentPrompt, parentPrompt.contains("no other agent was working"))
    // The playbook is still the orchestrator's own, with its delegation guidance.
    assertTrue(parentPrompt, parentPrompt.contains("Tools available:"))
    assertTrue(parentPrompt, parentPrompt.contains("does not overlap another agent's"))
  }

  /** Every way a run can end has to hand its seat back, or it claims files forever. */
  @Test
  fun `a finished or failed run closes its seat`() {
    val clean = teamRun(AgentRoles.QA, childEdits)
    assertEquals(AgentWorkStatus.DONE, clean.seat("Main agent").status)
    assertEquals(AgentWorkStatus.DONE, clean.seat("QA / Test Engineer").status)
    assertEquals(clean.board.snapshot().toString(), 2, clean.board.snapshot().size)
    assertEquals(listOf("server/db/schema.ts"), clean.seat("QA / Test Engineer").files)

    val childBroke = teamRun(AgentRoles.SECURITY, listOf(Turn.Break(LlmErrorKind.AUTH)))
    assertEquals(
      "a broken delegated run must not stay on the board as if it were still editing",
      AgentWorkStatus.FAILED,
      childBroke.seat("Security Engineer").status
    )
    assertEquals(AgentWorkStatus.DONE, childBroke.seat("Main agent").status)
  }

  @Test
  fun `a cancelled run closes its seat`() {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val board = AgentTeamBoard()
    // A provider that never answers, so the only way this run ends is being stopped.
    val service = object : LlmService() {
      override suspend fun streamChat(
        provider: AIProvider,
        model: AIModel,
        apiKey: String,
        request: LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit
      ) {
        onEvent(LlmStreamEvent.Started)
        delay(60_000)
      }
    }
    val registry = AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = git,
      terminalManager = terminals,
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {}
    )
    val runtime = AgentRuntime(fileSystem, terminals, git, service, registry, board) { null }

    runBlocking {
      val job = launch {
        runtime.executeTask(
          prompt = "wait forever",
          project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath),
          provider = provider,
          model = model,
          apiKey = "k",
          permissions = { AgentPermissions() },
          terminalSession = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath),
          onRequestApproval = { },
          onEvent = { }
        )
      }
      repeat(200) { if (board.snapshot().isEmpty()) delay(25) }
      assertTrue(board.snapshot().toString(), board.snapshot().isNotEmpty())

      job.cancelAndJoin()
    }
    val views = board.snapshot()
    assertEquals(views.toString(), 1, views.size)
    assertEquals(AgentWorkStatus.CANCELLED, views.single().status)
    assertTrue(board.promptBlock(excludingId = null), board.promptBlock(excludingId = null).contains("no other agent was working"))
  }

  /** A runtime built without a board behaves exactly as it did before teams existed. */
  @Test
  fun `no board means no team block in the prompt`() {
    val dir = File(ws.root, "project_${System.nanoTime()}")
    File(dir, "src").mkdirs()
    val fileSystem = ProjectFileSystem(dir)
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    val service = ScriptedService(listOf(Turn.Answer("done")))
    val registry = AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = git,
      terminalManager = terminals,
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {}
    )
    val runtime = AgentRuntime(fileSystem, terminals, git, service, registry) { null }
    runBlocking {
      runtime.executeTask(
        prompt = "just answer",
        project = Project(id = "p", name = "T", branch = "main", lastActivity = "now", path = dir.absolutePath),
        provider = provider,
        model = model,
        apiKey = "k",
        permissions = { AgentPermissions() },
        terminalSession = TerminalSession(id = "t", name = "main", currentDir = dir.absolutePath),
        onRequestApproval = { },
        onEvent = { }
      )
    }
    val prompt = service.requests.single().messages.first { it.role == LlmRole.SYSTEM }.content
    assertFalse(prompt, prompt.contains("Workspace team"))
    assertTrue(prompt, prompt.contains("Tools available:"))
  }

}
