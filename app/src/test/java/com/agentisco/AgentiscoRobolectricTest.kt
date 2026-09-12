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
    assertEquals("Agentisco", viewModel.activeProject.value.name)
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

  /** Runs a git command for tests using the real git binary on PATH. */
  private fun runGitForTest(projectPath: String, args: String): com.agentisco.workspace.git.GitRunResult {
    val tokens = Regex("\"[^\"]*\"|'[^']*'|\\S+").findAll(args).map { m ->
      val t = m.value
      if (t.startsWith("\"") || t.startsWith("'")) t.substring(1, t.length - 1)
      else t.replace("HEAD:", "HEAD:")
    }.filter { it != "2>/dev/null" }.toList()
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
      val tempDir = File(System.getProperty("java.io.tmpdir"), "test_git_${System.currentTimeMillis()}")
      tempDir.deleteOnExit()
      val fs = ProjectFileSystem(tempDir)
      val project = fs.createProject("GitTest", "Git testing")
      val git = GitRepositoryManager(fs) { path, args -> runGitForTest(path, args) }

      // Real git repository initialization
      assertTrue(git.initRepository(project))
      // Local identity so commits work in the test environment
      runGitForTest(project.path, "git config user.name Test")
      runGitForTest(project.path, "git config user.email test@test")
      assertTrue(git.isGitRepository(project))

      // Initially clean (no commits yet, but also no changes vs empty index)
      assertTrue(git.getChangedFiles(project).isEmpty())

      // Modify a file and verify it is detected
      fs.writeFile(project, "package.json", "{\n  \"name\": \"gittest-modified\"\n}")
      val changedDiffs = git.computeAllDiffs(project)
      assertEquals(1, changedDiffs.size)
      assertEquals("package.json", changedDiffs.first().filePath)

      // Commit changes
      val commit = git.commit(project, setOf("package.json"), "Update package name")
      assertNotNull(commit)
      assertEquals("Update package name", commit?.message)
      assertTrue(git.getCommitHistory(project).any { it.message == "Update package name" })

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
  fun `workspace view model saves active file and detects dirty state`() {
    val viewModel = WorkspaceViewModel()
    val initialFile = viewModel.activeFile.value
    assertFalse(viewModel.isEditorDirty.value)

    viewModel.updateEditorContent(viewModel.editorContent.value + "\n// modified")
    assertTrue(viewModel.isEditorDirty.value)

    viewModel.saveActiveFile()
    assertFalse(viewModel.isEditorDirty.value)
    assertTrue(viewModel.fileDiffs.value.any { it.filePath == initialFile.path })
  }
}
