package com.awaki

import com.awaki.agent.tool.CopyFileTool
import com.awaki.agent.tool.CreateDirectoryTool
import com.awaki.agent.tool.DeleteFileTool
import com.awaki.agent.tool.EditFileTool
import com.awaki.agent.tool.EditFilesTool
import com.awaki.agent.tool.GlobPattern
import com.awaki.agent.tool.MoveFileTool
import com.awaki.agent.tool.PermissionGates
import com.awaki.agent.tool.ReadFileTool
import com.awaki.agent.tool.ToolArgumentError
import com.awaki.agent.tool.ToolOutput
import com.awaki.agent.tool.WorkspacePath
import com.awaki.agent.tool.WorkspaceText
import com.awaki.agent.tool.WriteGate
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file tools a model uses to change code — and the failure modes that made
 * the agent "mangle" files: silent truncation, CRLF mismatches, a batch that
 * half-applies, a move that re-encoded a binary, a lost update from two parallel
 * edits. Each test names one of those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkspaceFileToolTest {

  private var workspace: TestWorkspace? = null

  private fun ws(): TestWorkspace = newWorkspace("files").also { workspace = it }

  @After
  fun cleanUp() {
    workspace?.dispose()
    workspace = null
  }

  // ---- Paths never leave the workspace ----

  @Test
  fun `escaping and absolute paths are refused before any file is touched`() {
    assertNull(WorkspacePath.normalize("../outside.txt"))
    assertNull(WorkspacePath.normalize("src/../../outside.txt"))
    assertNull(WorkspacePath.normalize("/etc/passwd"))
    assertNull(WorkspacePath.normalize("~/secrets"))
    assertNull(WorkspacePath.normalize("""C:\Windows\system.ini"""))
    assertEquals("src/app.ts", WorkspacePath.normalize("./src//nested/../app.ts"))
  }

  @Test
  fun `a tool call cannot read through a symlinked path out of the project`() {
    val ws = ws()
    ws.write("keep.txt", "inside")
    val ctx = contextFor(ws)
    val escape = "${ws.root.name}/../../etc/passwd"
    assertFalse(
      runBlocking { ReadFileTool().execute(args("""{"path": "$escape"}"""), ctx) }.success
    )
  }

  @Test
  fun `the workspace root itself is never a writable target`() {
    val ws = ws()
    assertNull(WorkspacePath.resolve(ws.project, "."))
    assertNull(WorkspacePath.resolve(ws.project, ""))
  }

  // ---- Output is never shrunk without saying so ----

  @Test
  fun `truncation is always announced with the real character counts`() {
    val short = ToolOutput.limit("hello", 100)
    assertEquals("hello", short)

    val long = ToolOutput.limit("x".repeat(500), 100, "use start_line")
    assertTrue(long.startsWith("x".repeat(100)))
    assertTrue(long.contains("showing 100 of 500 characters"))
    assertTrue(long.contains("use start_line"))
    assertTrue(long.length < 500)
  }

  // ---- Reads report what they left out ----

  @Test
  fun `a sliced read names the exact lines that are missing`() {
    val ws = ws()
    ws.write("rows.txt", (1..10).joinToString("\n") { "line $it" } + "\n")
    val result = runBlocking {
      ReadFileTool().execute(args("""{"path": "rows.txt", "max_lines": 3}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertEquals("rows.txt — 10 lines", result.output.lineSequence().first())
    assertTrue(result.output.contains("line 3"))
    assertFalse(result.output.contains("line 9"))
    assertTrue(result.output.contains("4..10 (7 lines) not shown"))
    assertTrue(result.output.contains("start_line=4"))
    // A read must never register as a modified file.
    assertNull(result.metadata["file"])
    assertEquals("rows.txt", result.metadata["path"])
  }

  @Test
  fun `start_line continues exactly where the previous slice stopped`() {
    val ws = ws()
    ws.write("rows.txt", (1..10).joinToString("\n") { "line $it" } + "\n")
    val second = runBlocking {
      ReadFileTool().execute(args("""{"path": "rows.txt", "start_line": 4, "max_lines": 3}"""), contextFor(ws))
    }
    assertTrue(second.output.contains("line 4"))
    assertTrue(second.output.contains("line 6"))
    assertFalse(second.output.contains("line 7"))
    assertTrue(second.output.contains("from line 4"))
  }

  @Test
  fun `binary files are refused instead of decoded into garbage`() {
    val ws = ws()
    File(ws.root, "blob.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3, 0, 65, 66))
    val result = runBlocking { ReadFileTool().execute(args("""{"path": "blob.bin"}"""), contextFor(ws)) }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("binary"))
  }

  @Test
  fun `a folder is named as a folder rather than read as an empty file`() {
    val ws = ws()
    File(ws.root, "src").mkdirs()
    val result = runBlocking { ReadFileTool().execute(args("""{"path": "src"}"""), contextFor(ws)) }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("folder"))
  }

  // ---- Writing keeps the file's own conventions ----

  @Test
  fun `write_file refuses to blank a file that has content`() {
    val ws = ws()
    ws.write("src/app.ts", "export const value = 1;\nexport const other = 2;\n")
    val result = runBlocking {
      com.awaki.agent.tool.WriteFileTool(allowWrite)
        .execute(args("""{"path": "src/app.ts", "content": ""}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Refused"))
    assertEquals("export const value = 1;\nexport const other = 2;\n", ws.read("src/app.ts"))
  }

  @Test
  fun `write_file keeps CRLF endings when the model sends LF`() {
    val ws = ws()
    ws.write("win.txt", "one\r\ntwo\r\n")
    val result = runBlocking {
      com.awaki.agent.tool.WriteFileTool(allowWrite)
        .execute(args("""{"path": "win.txt", "content": "three\nfour\n"}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertEquals("three\r\nfour\r\n", ws.read("win.txt"))
    assertTrue(result.output.contains("Replaced 2 lines with 2"))
  }

  @Test
  fun `a write that cannot be verified back is reported as a failure`() {
    val ws = ws()
    // A directory in the place of the file: writing fails, and the tool must not
    // claim success just because no exception escaped.
    File(ws.root, "blocked").mkdirs()
    val failure = WorkspaceText.write(File(ws.root, "blocked"), "content")
    assertNotNull(failure)
  }

  // ---- Editing ----

  @Test
  fun `edit_file requires new_string instead of deleting the snippet`() {
    val ws = ws()
    ws.write("a.txt", "keep this line\n")
    val result = runBlocking {
      EditFileTool(allowWrite).execute(args("""{"path": "a.txt", "old_string": "keep this line"}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("new_string is required"))
    assertEquals("keep this line\n", ws.read("a.txt"))
  }

  @Test
  fun `omitting new_string fails schema validation before execution`() {
    val error = assertThrows(ToolArgumentError::class.java) {
      EditFileTool(allowWrite).parseAndValidate("""{"path": "a.txt", "old_string": "x"}""")
    }
    assertTrue(error.message!!.contains("new_string"))
  }

  @Test
  fun `an empty new_string deletes the matched snippet`() {
    val ws = ws()
    ws.write("a.txt", "one\ntwo\nthree\n")
    val result = runBlocking {
      EditFileTool(allowWrite).execute(args("""{"path": "a.txt", "old_string": "two\n", "new_string": ""}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertEquals("one\nthree\n", ws.read("a.txt"))
  }

  @Test
  fun `a CRLF file is edited with an LF snippet and stays CRLF`() {
    val ws = ws()
    ws.write("win.txt", "fun main() {\r\n  println(1)\r\n  println(2)\r\n}\r\n")
    val result = runBlocking {
      EditFileTool(allowWrite).execute(
        args("""{"path": "win.txt", "old_string": "  println(1)\n  println(2)", "new_string": "  log(1)\n  log(2)"}"""),
        contextFor(ws)
      )
    }
    assertTrue(result.success)
    val content = ws.read("win.txt")
    assertEquals("fun main() {\r\n  log(1)\r\n  log(2)\r\n}\r\n", content)
    assertFalse(result.output.contains("not found"))
  }

  @Test
  fun `an ambiguous snippet is refused with the count and the way out`() {
    val ws = ws()
    ws.write("dup.txt", "value = 1\nvalue = 1\nvalue = 1\n")
    val result = runBlocking {
      EditFileTool(allowWrite).execute(args("""{"path": "dup.txt", "old_string": "value = 1", "new_string": "value = 2"}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("appears 3 times"))
    assertTrue(result.error!!.contains("replace_all"))
    assertEquals("value = 1\nvalue = 1\nvalue = 1\n", ws.read("dup.txt"))

    val all = runBlocking {
      EditFileTool(allowWrite).execute(
        args("""{"path": "dup.txt", "old_string": "value = 1", "new_string": "value = 2", "replace_all": true}"""),
        contextFor(ws)
      )
    }
    assertTrue(all.success)
    assertTrue(all.output.contains("replaced 3 occurrences"))
    assertEquals("value = 2\nvalue = 2\nvalue = 2\n", ws.read("dup.txt"))
  }

  @Test
  fun `a missed snippet explains why instead of pushing a full file rewrite`() {
    val ws = ws()
    ws.write("indent.txt", "class A {\n    fun b() {\n        println()\n    }\n}\n")
    val result = runBlocking {
      EditFileTool(allowWrite).execute(
        args("""{"path": "indent.txt", "old_string": "fun b() {\nprintln()", "new_string": "fun c()"}"""),
        contextFor(ws)
      )
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("once whitespace is ignored"))
    assertTrue(result.error!!.contains("line 2"))
  }

  @Test
  fun `editing a file that does not exist fails without creating it`() {
    val ws = ws()
    val result = runBlocking {
      EditFileTool(allowWrite).execute(args("""{"path": "ghost.txt", "old_string": "a", "new_string": "b"}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("Not found"))
    assertFalse(ws.exists("ghost.txt"))
  }

  @Test
  fun `file editing blocked by policy never writes`() {
    val ws = ws()
    ws.write("a.txt", "one\n")
    val log = ApprovalLog()
    val ctx = contextFor(ws, fileEditing = com.awaki.agent.model.PermissionMode.NEVER_ALLOW)
    val result = runBlocking {
      EditFileTool(PermissionGates::fileWrite)
        .execute(args("""{"path": "a.txt", "old_string": "one", "new_string": "two"}"""), ctx)
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("permission policy"))
    assertEquals("one\n", ws.read("a.txt"))
    assertTrue(log.requests.isEmpty())
  }

  @Test
  fun `a policy that always asks puts the edit in front of the user`() {
    val ws = ws()
    ws.write("a.txt", "one\n")
    val log = ApprovalLog()
    val ctx = contextFor(ws, fileEditing = com.awaki.agent.model.PermissionMode.ALWAYS_ASK, log = log)
    val denied = runBlocking {
      log.approve = false
      EditFileTool(PermissionGates::fileWrite)
        .execute(args("""{"path": "a.txt", "old_string": "one", "new_string": "two"}"""), ctx)
    }
    assertFalse(denied.success)
    assertEquals(1, log.requests.size)
    assertEquals("one\n", ws.read("a.txt"))

    val allowed = runBlocking {
      log.approve = true
      EditFileTool(PermissionGates::fileWrite)
        .execute(args("""{"path": "a.txt", "old_string": "one", "new_string": "two"}"""), ctx)
    }
    assertTrue(allowed.success)
    assertEquals("two\n", ws.read("a.txt"))
  }

  // ---- Batched edits ----

  @Test
  fun `edit_files applies every edit of a batch across files`() {
    val ws = ws()
    ws.write("a.ts", "const a = 1;\nconst b = 2;\n")
    ws.write("b.ts", "export a\n")
    val batch = """
      {"edits": [
        {"path": "a.ts", "old_string": "const a = 1;", "new_string": "const a = 10;"},
        {"path": "a.ts", "old_string": "const b = 2;", "new_string": "const b = 20;\nconst c = 3;"},
        {"path": "b.ts", "old_string": "export a", "new_string": "export a10"}
      ], "note": "widen the constants"}
    """.trimIndent()
    val result = runBlocking { EditFilesTool(allowWrite).execute(args(batch), contextFor(ws)) }
    assertTrue(result.error ?: "", result.success)
    assertEquals("const a = 10;\nconst b = 20;\nconst c = 3;\n", ws.read("a.ts"))
    assertEquals("export a10\n", ws.read("b.ts"))
    assertTrue(result.output.contains("3 edit(s) in 2 file(s)"))
    assertTrue(result.output.contains("a.ts:1"))
  }

  @Test
  fun `one bad edit leaves the whole batch unwritten`() {
    val ws = ws()
    ws.write("a.ts", "alpha\n")
    ws.write("b.ts", "beta\n")
    val batch = """
      {"edits": [
        {"path": "a.ts", "old_string": "alpha", "new_string": "gamma"},
        {"path": "b.ts", "old_string": "this line is not in the file", "new_string": "delta"}
      ]}
    """.trimIndent()
    val result = runBlocking { EditFilesTool(allowWrite).execute(args(batch), contextFor(ws)) }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("edit #2"))
    assertEquals("alpha\n", ws.read("a.ts"))
    assertEquals("beta\n", ws.read("b.ts"))
  }

  @Test
  fun `a batch reports the edit number and file for a path outside the workspace`() {
    val ws = ws()
    ws.write("a.ts", "alpha\n")
    val result = runBlocking {
      EditFilesTool(allowWrite).execute(
        args("""{"edits": [{"path": "../escape.ts", "old_string": "a", "new_string": "b"}]}"""),
        contextFor(ws)
      )
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("edit #1"))
    assertTrue(result.error!!.contains("inside the workspace"))
    assertFalse(ws.outside("escape.ts").exists())
  }

  @Test
  fun `two parallel edits to one file both survive`() {
    val ws = ws()
    ws.write("shared.txt", "line1\nline2\nline3\n")
    val ctx = contextFor(ws)
    runBlocking {
      coroutineScope {
        launch {
          EditFileTool(allowWrite).execute(
            args("""{"path": "shared.txt", "old_string": "line1", "new_string": "LINE ONE"}"""), ctx
          )
        }
        launch {
          EditFileTool(allowWrite).execute(
            args("""{"path": "shared.txt", "old_string": "line3", "new_string": "LINE THREE"}"""), ctx
          )
        }
      }
    }
    val content = ws.read("shared.txt")
    assertTrue("lost update: $content", content.contains("LINE ONE"))
    assertTrue("lost update: $content", content.contains("LINE THREE"))
  }

  // ---- Moving, copying, deleting ----

  @Test
  fun `move_file preserves bytes a text round trip would destroy`() {
    val ws = ws()
    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 1, 2, 0, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte())
    File(ws.root, "assets/icon.png").apply { parentFile!!.mkdirs() }.writeBytes(png)
    val result = runBlocking {
      MoveFileTool(allowWrite).execute(
        args("""{"path": "assets/icon.png", "new_path": "assets/copy.png"}"""), contextFor(ws)
      )
    }
    assertTrue(result.error ?: "", result.success)
    assertArrayEquals(png, ws.bytes("assets/copy.png"))
    assertFalse(ws.exists("assets/icon.png"))
  }

  @Test
  fun `copy_file leaves the original in place`() {
    val ws = ws()
    ws.write("src/a.txt", "content\n")
    val result = runBlocking {
      CopyFileTool(allowWrite).execute(args("""{"path": "src/a.txt", "new_path": "src/b.txt"}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertEquals("content\n", ws.read("src/a.txt"))
    assertEquals("content\n", ws.read("src/b.txt"))
  }

  @Test
  fun `a move never overwrites an existing destination`() {
    val ws = ws()
    ws.write("a.txt", "one\n")
    ws.write("b.txt", "two\n")
    val result = runBlocking {
      MoveFileTool(allowWrite).execute(args("""{"path": "a.txt", "new_path": "b.txt"}"""), contextFor(ws))
    }
    assertFalse(result.success)
    assertTrue(result.error!!.contains("already exists"))
    assertTrue(ws.exists("a.txt"))
    assertEquals("two\n", ws.read("b.txt"))
  }

  @Test
  fun `delete_file needs recursive for a folder and never deletes the workspace`() {
    val ws = ws()
    ws.write("dir/inner.txt", "x\n")
    val ctx = contextFor(ws)
    val folder = runBlocking {
      DeleteFileTool(allowWrite).execute(args("""{"path": "dir"}"""), ctx)
    }
    assertFalse(folder.success)
    assertTrue(folder.error!!.contains("recursive"))
    assertTrue(ws.exists("dir/inner.txt"))

    val root = runBlocking { DeleteFileTool(allowWrite).execute(args("""{"path": "."}"""), ctx) }
    assertFalse(root.success)
    assertTrue(ws.root.isDirectory)

    val recursive = runBlocking { DeleteFileTool(allowWrite).execute(args("""{"path": "dir", "recursive": true}"""), ctx) }
    assertTrue(recursive.success)
    assertFalse(ws.exists("dir/inner.txt"))
  }

  @Test
  fun `a refused edit is not a stopped turn, and a stopped turn is not a refusal`() {
    val ws = ws()
    ws.write("a.txt", "one\n")
    ws.write("b.txt", "one\n")

    val refused = runBlocking {
      EditFileTool(PermissionGates::fileWrite).execute(
        args("""{"path": "a.txt", "old_string": "one", "new_string": "two"}"""),
        contextFor(ws, fileEditing = com.awaki.agent.model.PermissionMode.ALWAYS_ASK,
          log = ApprovalLog().apply { approve = false; terminated = false })
      )
    }
    assertFalse(refused.success)
    assertTrue(refused.error!!, refused.error!!.contains("denied permission"))
    assertEquals("one\n", ws.read("a.txt"))

    val stopped = runBlocking {
      EditFileTool(PermissionGates::fileWrite).execute(
        args("""{"path": "b.txt", "old_string": "one", "new_string": "two"}"""),
        contextFor(ws, fileEditing = com.awaki.agent.model.PermissionMode.ALWAYS_ASK,
          log = ApprovalLog().apply { approve = false; terminated = true })
      )
    }
    assertFalse(stopped.success)
    assertFalse(stopped.error!!, stopped.error!!.contains("denied permission"))
    assertTrue(stopped.error!!, stopped.error!!.contains("stopped"))
    assertEquals("one\n", ws.read("b.txt"))
  }

  @Test
  fun `deletion refused by policy leaves the file`() {
    val ws = ws()
    ws.write("gone.txt", "x\n")
    val log = ApprovalLog().apply { approve = false }
    val ctx = contextFor(ws, deleteFiles = true, log = log)
    val gate: WriteGate = { toolCtx, path ->
      val ok = toolCtx.requestApproval(
        com.awaki.agent.model.PendingApproval(
          id = "t", command = "delete $path", title = "delete", impactDescription = "delete $path", isDestructive = true
        )
      )
      if (ok) null else com.awaki.agent.tool.ToolResult(false, error = "The user did not allow the deletion.")
    }
    val result = runBlocking { DeleteFileTool(gate).execute(args("""{"path": "gone.txt"}"""), ctx) }
    assertFalse(result.success)
    assertEquals(1, log.requests.size)
    assertTrue(log.requests.single().isDestructive)
    assertTrue(ws.exists("gone.txt"))
  }

  @Test
  fun `create_directory makes the parents and reports the folder`() {
    val ws = ws()
    val result = runBlocking {
      CreateDirectoryTool().execute(args("""{"path": "deep/nested/folder"}"""), contextFor(ws))
    }
    assertTrue(result.success)
    assertTrue(File(ws.root, "deep/nested/folder").isDirectory)
  }

  // ---- Glob compiler ----

  @Test
  fun `glob double star crosses directories and single star does not`() {
    assertTrue(GlobPattern.matches("**/*.kt", "a/b/C.kt"))
    assertTrue(GlobPattern.matches("**/*.kt", "C.kt"))
    assertFalse(GlobPattern.matches("src/*.kt", "src/a/b/C.kt"))
    assertTrue(GlobPattern.matches("src/*.kt", "src/C.kt"))
    assertTrue(GlobPattern.matches("src/**/*.test.ts", "src/x/y/z.test.ts"))
    assertFalse(GlobPattern.matches("**/*.kt", "a/b/C.java"))
  }

  @Test
  fun `glob alternates and single character wildcards work`() {
    assertTrue(GlobPattern.matches("**/*.{js,ts}", "pkg/main.ts"))
    assertTrue(GlobPattern.matches("**/*.{js,ts}", "pkg/main.js"))
    assertFalse(GlobPattern.matches("**/*.{js,ts}", "pkg/main.css"))
    assertTrue(GlobPattern.matches("src/?.kt", "src/a.kt"))
    assertFalse(GlobPattern.matches("src/?.kt", "src/ab.kt"))
  }

  @Test
  fun `glob dots and plus stay literal instead of becoming regex`() {
    assertTrue(GlobPattern.matches("**/*.config.ts", "a/b.config.ts"))
    assertFalse(GlobPattern.matches("**/a.ba.ts", "a/b-ca.ts"))
  }
}
