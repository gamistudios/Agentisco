package com.agentisco

import com.agentisco.core.model.AppDestination
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.model.DiffLineType
import com.agentisco.workspace.git.DiffEngine
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.ui.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Agentisco", appName)
  }

  @Test
  fun `workspace view model initial state`() {
    val viewModel = WorkspaceViewModel()
    // Fresh installs start with no projects (static demo data was removed)
    assertEquals("No project", viewModel.activeProject.value.name)
    assertEquals(com.agentisco.core.model.AppDestination.AGENT, viewModel.currentDestination.value)
    // Fresh installs start with an empty provider catalog and no selected model
    assertNull(viewModel.selectedModel.value)
    assertTrue(viewModel.providers.value.isEmpty())
  }

  @Test
  fun `diff engine calculates additions and deletions accurately`() {
    val original = "line1\nline2\nline3"
    val modified = "line1\nline2 modified\nline3\nline4"
    val diff = DiffEngine.computeDiff("test.txt", original, modified)

    assertNotNull(diff)
    assertEquals("test.txt", diff?.filePath)
    assertTrue(diff!!.additionsCount >= 2)
    assertTrue(diff.deletionsCount >= 1)

    val addedLines = diff.lines.filter { it.type == DiffLineType.ADDED }
    val removedLines = diff.lines.filter { it.type == DiffLineType.REMOVED }
    assertTrue(addedLines.any { it.text.contains("line2 modified") })
    assertTrue(removedLines.any { it.text.contains("line2") })
  }

  @Test
  fun `project file system performs real file operations`() {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pfs_${System.currentTimeMillis()}")
    tempDir.deleteOnExit()
    val fs = ProjectFileSystem(tempDir)
    val project = fs.createProject("TestProj", "Test Description")

    assertEquals("TestProj", project.name)
    assertTrue(fs.getProjects().any { it.name == "TestProj" })

    // Create new file
    val created = fs.createFile(project, "src/components/Button.tsx", "export const Button = () => null;")
    assertTrue(created)

    // Read file
    val content = fs.readFile(project, "src/components/Button.tsx")
    assertEquals("export const Button = () => null;", content)

    // Write file
    val updated = fs.writeFile(project, "src/components/Button.tsx", "export const Button = () => 123;")
    assertTrue(updated)
    assertEquals("export const Button = () => 123;", fs.readFile(project, "src/components/Button.tsx"))

    // Directory tree
    val tree = fs.getFileTree(project)
    assertTrue(tree.any { it.name == "src" && it.isDirectory })

    // Delete file
    val deleted = fs.deleteFile(project, "src/components/Button.tsx")
    assertTrue(deleted)
    assertFalse(fs.exists(project, "src/components/Button.tsx"))
  }

  /** Locates a usable git binary (PATH or common Windows install dirs). */
  private fun resolveGitBinary(): String? {
    val candidates = listOf(
      "git",
      "C:\\Program Files\\Git\\cmd\\git.exe",
      "C:\\Program Files\\Git\\bin\\git.exe"
    )
    return candidates.firstOrNull { exe ->
      runCatching { ProcessBuilder(exe, "--version").start().waitFor() == 0 }.getOrDefault(false)
    }
  }

  /**
   * Splits a command line POSIX-shell style: quotes are stripped and the text
   * they enclosed stays one argument (`--pretty=format:"%h|%s"` -> `%h|%s`).
   * A naive whitespace tokenizer would leave the quotes inside the argument,
   * which real shells remove but ProcessBuilder (Linux CI) passes verbatim.
   */
  private fun shellSplit(args: String): List<String> {
    val tokens = mutableListOf<String>()
    val cur = StringBuilder()
    var started = false
    var i = 0
    while (i < args.length) {
      when (val c = args[i]) {
        ' ', '\t' -> {
          if (started) { tokens.add(cur.toString()); cur.setLength(0); started = false }
          i++
        }
        '\'', '"' -> {
          started = true
          i++
          while (i < args.length && args[i] != c) { cur.append(args[i]); i++ }
          if (i < args.length) i++
        }
        '\\' -> {
          started = true
          if (i + 1 < args.length) { cur.append(args[i + 1]); i += 2 } else { cur.append(c); i++ }
        }
        else -> { started = true; cur.append(c); i++ }
      }
    }
    if (cur.isNotEmpty()) tokens.add(cur.toString())
    return tokens
  }

  /** A real POSIX shell, used to run git commands exactly like the app's guest shell does. */
  private val testShell: String? by lazy {
    listOf("bash", "sh").firstOrNull { sh ->
      runCatching { ProcessBuilder(sh, "-c", "true").start().waitFor() == 0 }.getOrDefault(false)
    }
  }

  /** Runs a git command for tests using the real git binary. */
  private fun runGitForTest(gitExe: String, projectPath: String, args: String): com.agentisco.workspace.git.GitRunResult {
    val shell = testShell
    if (shell != null) {
      // Go through a real shell so quoting and shell metacharacters (|, ;, >)
      // behave exactly as they do on-device — token splitting would mask bugs
      // like an unquoted '|' in a pretty-format string becoming a shell pipe.
      val quotedGit = "'" + gitExe.replace('\\', '/').replace("'", "'\\''") + "'"
      val cmd = if (args == "git" || args.startsWith("git ")) quotedGit + args.removePrefix("git") else args
      val process = ProcessBuilder(shell, "-c", cmd)
        .directory(File(projectPath))
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      return com.agentisco.workspace.git.GitRunResult(process.waitFor(), output)
    }
    val tokens = shellSplit(args).filter { it != "2>/dev/null" && it != "true" }.toMutableList()
    if (tokens.firstOrNull() == "git") tokens[0] = gitExe
    val process = ProcessBuilder(tokens)
      .directory(File(projectPath))
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    return com.agentisco.workspace.git.GitRunResult(process.waitFor(), output)
  }

  @Test
  fun `git repository manager tracks changes reverts files and commits`() {
    kotlinx.coroutines.runBlocking {
      val gitExe = resolveGitBinary()
      org.junit.Assume.assumeTrue("git binary not available on this machine - skipping real-git test", gitExe != null)
      val tempDir = File(System.getProperty("java.io.tmpdir"), "test_git_${System.currentTimeMillis()}")
      tempDir.deleteOnExit()
      val fs = ProjectFileSystem(tempDir)
      val project = fs.createProject("GitTest", "Git testing")
      val git = GitRepositoryManager(fs) { path, args -> runGitForTest(gitExe!!, path, args) }

      // Real git repository initialization
      assertTrue(git.initRepository(project))
      // Local identity so commits work in the test environment
      runGitForTest(gitExe!!, project.path, "git config user.name Test")
      runGitForTest(gitExe!!, project.path, "git config user.email test@test")
      // Keep bytes identical across write/checkout on Windows (autocrlf).
      runGitForTest(gitExe!!, project.path, "git config core.autocrlf false")
      assertTrue(git.isGitRepository(project))

      // Scaffold files are untracked on a fresh repo — create the initial commit.
      val scaffold = git.getChangedFiles(project).toSet()
      assertTrue(scaffold.isNotEmpty())
      assertNotNull(git.commit(project, scaffold, "chore: initial commit").commit)
      // With everything committed, the working tree is clean.
      assertTrue(git.getChangedFiles(project).isEmpty())

      // Modify a file and verify it is detected
      fs.writeFile(project, "package.json", "{\n  \"name\": \"gittest-modified\"\n}")
      val changedDiffs = git.computeAllDiffs(project)
      assertEquals(1, changedDiffs.size)
      assertEquals("package.json", changedDiffs.first().filePath)

      // Commit changes
      val commit = git.commit(project, setOf("package.json"), "Update package name").commit
      assertNotNull(commit)
      assertEquals("Update package name", commit?.message)
      assertTrue(git.getCommitHistory(project)?.any { it.message == "Update package name" } == true)

      // After commit, should be clean
      assertTrue(git.computeAllDiffs(project).isEmpty())

      // Modify again, then revert to the committed content
      fs.writeFile(project, "package.json", "corrupted")
      assertTrue(git.computeAllDiffs(project).isNotEmpty())
      val reverted = git.revertFile(project, "package.json")
      assertTrue(reverted)
      assertEquals("{\n  \"name\": \"gittest-modified\"\n}", fs.readFile(project, "package.json"))
    }
  }

  @Test
  fun `git reset soft mixed and hard move HEAD to the chosen commit`() {
    kotlinx.coroutines.runBlocking {
      val gitExe = resolveGitBinary()
      org.junit.Assume.assumeTrue("git binary not available on this machine - skipping real-git test", gitExe != null)
      val tempDir = File(System.getProperty("java.io.tmpdir"), "test_reset_${System.currentTimeMillis()}")
      tempDir.deleteOnExit()
      val fs = ProjectFileSystem(tempDir)
      val project = fs.createProject("ResetTest", "reset testing")
      val git = GitRepositoryManager(fs) { path, args -> runGitForTest(gitExe!!, path, args) }

      assertTrue(git.initRepository(project))
      runGitForTest(gitExe!!, project.path, "git config user.name Test")
      runGitForTest(gitExe!!, project.path, "git config user.email test@test")
      runGitForTest(gitExe!!, project.path, "git config core.autocrlf false")

      git.commit(project, git.getChangedFiles(project).toSet(), "chore: first commit")
      fs.writeFile(project, "package.json", "{\n  \"name\": \"v2\"\n}")
      git.commit(project, setOf("package.json"), "second commit")

      val history = git.getCommitHistory(project)!!
      val first = history.last { it.message == "chore: first commit" }
      val headBefore = runGitForTest(gitExe!!, project.path, "git rev-parse HEAD").output.trim()
      assertEquals("second commit", history.first().message)

      // --hard: HEAD moves back and the working tree change disappears.
      val hard = git.resetToCommit(project, first.hash, com.agentisco.workspace.git.ResetMode.HARD)
      assertTrue("reset --hard failed: ${hard.output}", hard.success)
      val headAfterHard = runGitForTest(gitExe!!, project.path, "git rev-parse HEAD").output.trim()
      assertEquals(first.fullHash.ifBlank { first.hash }, headAfterHard)
      assertEquals("", runGitForTest(gitExe!!, project.path, "git status --porcelain").output.trim())

      // Recommit so soft/mixed have something to move back from.
      fs.writeFile(project, "package.json", "{\n  \"name\": \"v3\"\n}")
      git.commit(project, setOf("package.json"), "third commit")

      val soft = git.resetToCommit(project, first.hash, com.agentisco.workspace.git.ResetMode.SOFT)
      assertTrue("reset --soft failed: ${soft.output}", soft.success)
      assertEquals(
        first.fullHash.ifBlank { first.hash },
        runGitForTest(gitExe!!, project.path, "git rev-parse HEAD").output.trim()
      )
      // --soft leaves the changes staged.
      assertTrue(runGitForTest(gitExe!!, project.path, "git status --porcelain").output.trim().isNotEmpty())

      val mixed = git.resetToCommit(project, first.hash, com.agentisco.workspace.git.ResetMode.MIXED)
      assertTrue("reset --mixed failed: ${mixed.output}", mixed.success)
      assertEquals(
        first.fullHash.ifBlank { first.hash },
        runGitForTest(gitExe!!, project.path, "git rev-parse HEAD").output.trim()
      )
    }
  }

  @Test
  fun `workspace view model saves active file and detects dirty state`() {
    val viewModel = WorkspaceViewModel()
    
    // Create a temporary project directory for testing
    val tempDir = File(System.getProperty("java.io.tmpdir"), "test_project_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    
    // Create a test project
    val testProject = viewModel.repository.createProject("TestProject", "Test project for saving files", tempDir.absolutePath)
    assertNotNull("Failed to create test project", testProject)
    
    // Set the test project as active
    viewModel.repository.selectProject(testProject!!)
    
    val initialFile = viewModel.activeFile.value
    assertFalse(viewModel.isEditorDirty.value)

    viewModel.updateEditorContent(viewModel.editorContent.value + "\n// modified")
    assertTrue(viewModel.isEditorDirty.value)

    viewModel.saveActiveFile()
    assertFalse(viewModel.isEditorDirty.value)
    // Saving must persist to the real project folder on disk.
    val saved = viewModel.repository.fileSystem.readFile(
      viewModel.repository.activeProject.value, initialFile.path
    )
    assertTrue(saved.contains("// modified"))
    
    // Cleanup
    tempDir.deleteRecursively()
  }
}
