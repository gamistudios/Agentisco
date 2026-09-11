package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DiffLineType
import com.example.data.repository.DiffEngine
import com.example.data.repository.GitRepositoryManager
import com.example.data.repository.ProjectFileSystem
import com.example.ui.WorkspaceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    assertEquals("ScoOS", appName)
  }

  @Test
  fun `workspace view model initial state`() {
    val viewModel = WorkspaceViewModel()
    assertEquals("ScoSpace", viewModel.activeProject.value.name)
    assertEquals(com.example.data.model.AppDestination.AGENT, viewModel.currentDestination.value)
    assertEquals("GLM 5.3 Free", viewModel.selectedModel.value.name)
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

  @Test
  fun `git repository manager tracks changes reverts files and commits`() {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "test_git_${System.currentTimeMillis()}")
    tempDir.deleteOnExit()
    val fs = ProjectFileSystem(tempDir)
    val project = fs.createProject("GitTest", "Git testing")

    val git = GitRepositoryManager(fs)
    git.initializeProjectBaseline(project)

    // Initially clean
    val initialDiffs = git.computeAllDiffs(project)
    assertTrue(initialDiffs.isEmpty())

    // Modify a file
    fs.writeFile(project, "package.json", "{\n  \"name\": \"gittest-modified\"\n}")
    val changedDiffs = git.computeAllDiffs(project)
    assertEquals(1, changedDiffs.size)
    assertEquals("package.json", changedDiffs.first().filePath)

    // Commit changes
    val commit = git.commit(project, setOf("package.json"), "Update package name")
    assertNotNull(commit)
    assertEquals("Update package name", commit?.message)

    // After commit, should be clean
    val afterCommitDiffs = git.computeAllDiffs(project)
    assertTrue(afterCommitDiffs.isEmpty())

    // Modify again, then revert
    fs.writeFile(project, "package.json", "corrupted")
    assertTrue(git.computeAllDiffs(project).isNotEmpty())
    val reverted = git.revertFile(project, "package.json")
    assertTrue(reverted)
    assertEquals("{\n  \"name\": \"gittest-modified\"\n}", fs.readFile(project, "package.json"))
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
