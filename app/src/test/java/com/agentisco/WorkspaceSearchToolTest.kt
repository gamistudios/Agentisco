package com.agentisco

import com.agentisco.agent.compact.CompactPolicyConfig
import com.agentisco.agent.model.ToolType
import com.agentisco.agent.tool.AgentToolRegistry
import com.agentisco.agent.tool.DirectoryTreeTool
import com.agentisco.agent.tool.FileInfoTool
import com.agentisco.agent.tool.GlobFilesTool
import com.agentisco.agent.tool.ReadFilesTool
import com.agentisco.agent.tool.SearchFilesTool
import com.agentisco.agent.tool.RegexSearchTool
import com.agentisco.agent.tool.TaskPlanTool
import com.agentisco.agent.tool.toolTypeFor
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.git.GitRunResult
import com.agentisco.workspace.terminal.TerminalProcessManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tools that give the model its picture of the workspace, plus the registry
 * contract every tool must satisfy to be callable at all. A capped search that
 * reports no cap is what makes an agent delete a live code path, so the counters
 * get tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceSearchToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("search").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  // ---- Content search reports its own limits ----

  @Test
  fun `a capped search states how many matches it withheld`() {
    val ws = ws()
    ws.write("big.txt", (1..100).joinToString("\n") { "call target() // $it" })
    val result = runBlocking {
      SearchFilesTool().execute(
        args("""{"query": "target()", "max_matches": 5}"""), contextFor(ws)
      )
    }
    assertTrue(result.success)
    assertEquals(5, result.output.lineSequence().count { it.startsWith("big.txt:") })
    assertTrue(result.output.contains("showing 5 of 100 matches"))
    assertEquals("100", result.metadata["matches"])
  }

  @Test
  fun `a search with no hits says how many files it actually looked at`() {
    val ws = ws()
    ws.write("a.txt", "alpha\n")
    ws.write("b.txt", "beta\n")
    val result = runBlocking { SearchFilesTool().execute(args("""{"query": "gamma"}"""), contextFor(ws)) }
    assertTrue(result.success)
    assertTrue(result.output.contains("No matches for \"gamma\""))
    assertTrue(result.output.contains("searched 2 files"))
  }

  @Test
  fun `a glob that matches nothing is called out instead of looking like no hits`() {
    val ws = ws()
    ws.write("a.ts", "alpha\n")
    val result = runBlocking {
      SearchFilesTool().execute(args("""{"query": "alpha", "glob": "**/*.kt"}"""), contextFor(ws))
    }
    assertTrue(result.output.contains("No files match the glob"))
    assertTrue(result.output.contains("glob_files"))
  }

  @Test
  fun `matches are file line numbered and case handling follows the argument`() {
    val ws = ws()
    ws.write("src/App.kt", "import One\nimport two\n")
    val exact = runBlocking {
      SearchFilesTool().execute(args("""{"query": "import two", "case_sensitive": true}"""), contextFor(ws))
    }
    assertTrue(exact.output.startsWith("src/App.kt:2: import two"))

    val caseMiss = runBlocking {
      SearchFilesTool().execute(args("""{"query": "Import two", "case_sensitive": true}"""), contextFor(ws))
    }
    assertTrue(caseMiss.output.contains("No matches"))

    val loose = runBlocking {
      SearchFilesTool().execute(args("""{"query": "IMPORT"}"""), contextFor(ws))
    }
    assertEquals(2, loose.output.lineSequence().count { it.startsWith("src/App.kt:") })
    assertEquals("2", loose.metadata["matches"])
    assertEquals("1", loose.metadata["files"])
  }

  @Test
  fun `regex search matches patterns and rejects a broken one`() {
    val ws = ws()
    ws.write("src/S.kt", "fun alpha() {\nval x = 1\n  fun beta()\n}\n")
    val result = runBlocking {
      RegexSearchTool().execute(args("""{"pattern": "fun \\w+\\("}"""), contextFor(ws))
    }
    assertEquals(2, result.output.lineSequence().count { it.startsWith("src/S.kt:") })

    val broken = runBlocking { RegexSearchTool().execute(args("""{"pattern": "fun ("}"""), contextFor(ws)) }
    assertFalse(broken.success)
    assertTrue(broken.error!!.contains("Invalid regular expression"))
  }

  @Test
  fun `search skips generated folders the project excludes`() {
    val ws = ws()
    ws.write("src/app.ts", "needle here\n")
    ws.write("node_modules/pkg/index.js", "needle here\n")
    val result = runBlocking { SearchFilesTool().execute(args("""{"query": "needle"}"""), contextFor(ws)) }
    assertTrue(result.output.contains("src/app.ts"))
    assertFalse(result.output.contains("node_modules"))
  }

  // ---- Finding and reading many files at once ----

  @Test
  fun `glob files lists matches and says when the limit cut the list`() {
    val ws = ws()
    (1..5).forEach { ws.write("src/f$it.kt", "fun f$it()\n") }
    ws.write("readme.md", "not kotlin\n")
    val all = runBlocking { GlobFilesTool().execute(args("""{"pattern": "**/*.kt"}"""), contextFor(ws)) }
    assertEquals(5, all.output.lineSequence().count { it.endsWith(".kt") })
    assertFalse(all.output.contains("showing the first"))

    val capped = runBlocking { GlobFilesTool().execute(args("""{"pattern": "**/*.kt", "limit": 2}"""), contextFor(ws)) }
    assertEquals(2, capped.output.lineSequence().count { it.endsWith(".kt") })
    assertTrue(capped.output.contains("5 files match, showing the first 2"))
    assertEquals("5", capped.metadata["matches"])
  }

  @Test
  fun `read_files returns every readable file and counts the ones it could not`() {
    val ws = ws()
    ws.write("a.txt", "alpha\n")
    ws.write("b.txt", "beta\n")
    val result = runBlocking {
      ReadFilesTool().execute(args("""{"paths": ["a.txt", "missing.txt"]}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertTrue(result.output.contains("===== a.txt ====="))
    assertTrue(result.output.contains("alpha"))
    assertTrue(result.output.contains("Not found: missing.txt"))
    assertEquals("1", result.metadata["files"])
    assertEquals("1", result.metadata["unreadable"])
  }

  @Test
  fun `read_files refuses paths that leave the workspace instead of reading them`() {
    val ws = ws()
    ws.write("a.txt", "alpha\n")
    val result = runBlocking {
      ReadFilesTool().execute(args("""{"paths": ["../../etc/passwd", "a.txt"]}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertTrue(result.output.contains("outside the workspace"))
    assertEquals("1", result.metadata["unreadable"])
  }

  @Test
  fun `read_files fails when none of the requested files exist`() {
    val ws = ws()
    val result = runBlocking { ReadFilesTool().execute(args("""{"paths": ["nope.txt"]}"""), contextFor(ws)) }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("None of the requested files"))
  }

  // ---- Inspection ----

  @Test
  fun `file_info exposes line endings and line count so an edit can be written correctly`() {
    val ws = ws()
    ws.write("win.txt", "one\r\ntwo\r\nthree\r\n")
    val result = runBlocking { FileInfoTool().execute(args("""{"path": "win.txt"}"""), contextFor(ws)) }
    assertTrue(result.success)
    assertTrue(result.output.contains("lineEndings: CRLF"))
    assertTrue(result.output.contains("lines: 3"))
    assertTrue(result.output.contains("text: yes"))
  }

  @Test
  fun `directory_tree counts the entries its limits and rules hide`() {
    val ws = ws()
    (1..3).forEach { ws.write("src/f$it.txt", "x\n") }
    File(ws.root, "src/.cache").mkdirs()
    File(ws.root, "src/node_modules").mkdirs()
    val result = runBlocking {
      DirectoryTreeTool().execute(args("""{"path": "src", "depth": 1}"""), contextFor(ws))
    }
    assertTrue(result.output.contains("f1.txt"))
    assertFalse(result.output.contains(".cache"))
    assertFalse(result.output.contains("node_modules"))
    assertTrue(result.output.contains("2 further entries hidden"))
  }

  // ---- Plan visibility ----

  @Test
  fun `task_plan renders step status markers the user can read`() {
    val ws = ws()
    val result = runBlocking {
      TaskPlanTool().execute(
        args("""{"steps": ["inspect config", {"content": "apply fix", "status": "done"}, {"content": "verify build", "status": "in_progress"}], "note": "step 1 of 3"}"""),
        contextFor(ws)
      )
    }
    assertTrue(result.success)
    assertTrue(result.output.contains("1. [ ] inspect config"))
    assertTrue(result.output.contains("2. [x] apply fix"))
    assertTrue(result.output.contains("3. [>] verify build"))
    assertEquals("3", result.metadata["steps"])
    assertEquals("1", result.metadata["done"])

    val empty = runBlocking { TaskPlanTool().execute(args("""{"steps": []}"""), contextFor(ws)) }
    assertFalse(empty.success)
    assertTrue(empty.error!!.contains("at least one step"))
  }

  @Test
  fun `task_plan carries a title and per-step progress into its output`() {
    val result = runBlocking {
      TaskPlanTool().execute(
        args(
          """{"title":"Needle Retrieval Benchmark","note":"step 2 of 3","steps": [""" +
            """{"content":"Download corpus","status":"done","detail":"1.2 GB"},""" +
            """{"content":"Verify passages","status":"in_progress","detail":"24 / 86"},""" +
            """{"content":"Report findings","status":"failed"}]}"""
        ),
        contextFor(ws())
      )
    }
    assertTrue(result.success)
    assertTrue(result.output.startsWith("Needle Retrieval Benchmark"))
    assertTrue(result.output.contains("1. [x] Download corpus — 1.2 GB"))
    assertTrue(result.output.contains("2. [>] Verify passages — 24 / 86"))
    assertTrue(result.output.contains("3. [!] Report findings"))
    assertEquals("1", result.metadata["done"])
  }

  // ---- Registry contract ----

  private fun registryFor(ws: TestWorkspace): AgentToolRegistry {
    val fileSystem = ProjectFileSystem(File(ws.root.parentFile, "fsbase_${System.nanoTime()}"))
    val git = GitRepositoryManager(fileSystem) { _, _ -> GitRunResult(0, "") }
    val terminals = TerminalProcessManager { null }
    return AgentToolRegistry(
      fileSystem = fileSystem,
      gitManager = git,
      terminalManager = terminals,
      stagedFilesProvider = { emptySet() },
      onStageFile = {},
      onStageAll = {},
      onUnstageAll = {}
    )
  }

  @Test
  fun `every registered tool has a unique name and a valid parameter schema`() {
    val ws = ws()
    val registry = registryFor(ws)
    val names = registry.tools.map { it.name }
    assertEquals(names.size, names.toSet().size)

    registry.tools.forEach { tool ->
      val schema = JSONObject(tool.parametersJsonSchema())
      assertEquals("$tool schema type", "object", schema.getString("type"))
      val required = schema.optJSONArray("required")
      if (required != null) {
        val declared = tool.params.filter { it.required }.map { it.name }.toSet()
        (0 until required.length()).forEach { i ->
          assertTrue("${tool.name} requires unknown ${required.getString(i)}", required.getString(i) in declared)
        }
      }
    }
  }

  @Test
  fun `the tools the model needs for zcode level work are all registered`() {
    val ws = ws()
    val names = registryFor(ws).tools.map { it.name }.toSet()
    listOf(
      "read_file", "read_files", "search_files", "regex_search", "glob_files", "file_info", "directory_tree",
      "edit_file", "edit_files", "write_file", "create_file", "create_directory", "move_file", "copy_file",
      "delete_file", "task_plan", "run_command", "terminal_output", "write_terminal_input",
      "interrupt_terminal",
      "git_status", "git_diff", "git_log", "git_show", "build", "test", "web_fetch", "web_search", "ask_user"
    ).forEach { assertTrue("missing tool: $it", it in names) }
  }

  @Test
  fun `registry specs expose each tool to the provider`() {
    val ws = ws()
    val specs = registryFor(ws).specs()
    assertEquals(specs.size, specs.map { it.name }.toSet().size)
    assertTrue(specs.all { it.description.isNotBlank() })
    assertTrue(specs.all { JSONObject(it.parametersJsonSchema).getString("type") == "object" })
  }

  @Test
  fun `tool categories drive the icons and the permission gates`() {
    assertEquals(ToolType.EDIT_FILE, toolTypeFor("edit_files"))
    assertEquals(ToolType.EDIT_FILE, toolTypeFor("copy_file"))
    assertEquals(ToolType.EDIT_FILE, toolTypeFor("create_directory"))
    assertEquals(ToolType.SEARCH, toolTypeFor("glob_files"))
    assertEquals(ToolType.TERMINAL, toolTypeFor("terminal_output"))
    assertEquals(ToolType.TERMINAL, toolTypeFor("write_terminal_input"))
    assertEquals(ToolType.WEB, toolTypeFor("web_fetch"))
    assertEquals(ToolType.WEB, toolTypeFor("web_search"))
    assertEquals(ToolType.QUESTION, toolTypeFor("ask_user"))
    // A read must never be classified as an edit: the runtime records edits.
    assertEquals(ToolType.READ_FILE, toolTypeFor("read_file"))
  }

  @Test
  fun `old read heavy results are eligible for microcompaction`() {
    val tools = CompactPolicyConfig.DEFAULT_MICROCOMPACT_TOOLS
    listOf("read_file", "search_files", "terminal_output", "web_fetch", "web_search", "git_log", "git_show").forEach {
      assertTrue("$it should be compactable", it in tools)
    }
    assertFalse("an edit result must never be cleared", "edit_file" in tools)
  }
}
