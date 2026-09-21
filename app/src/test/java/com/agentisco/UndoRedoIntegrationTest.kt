package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.model.ProjectFile
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.editor.model.EditorTab
import com.agentisco.ui.WorkspaceViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoRedoIntegrationTest {

  private lateinit var context: Context
  private lateinit var repository: WorkspaceRepository
  private lateinit var viewModel: WorkspaceViewModel

  @Before
  fun setUp() {
    repository = WorkspaceRepository(context = null)
    viewModel = WorkspaceViewModel(repository)
  }

  @Test
  fun testUndoRedoMultiStepPreservesStacks() {
    val file = ProjectFile(
      path = "src/main.py",
      name = "main.py",
      isDirectory = false,
      content = "step 0",
      sizeBytes = 6
    )

    // Open initial tab
    val initialTab = EditorTab(
      id = "tab_1",
      file = file,
      content = "step 0",
      savedContent = "step 0"
    )
    viewModel.openTab(initialTab)
    val tabIndex = viewModel.activeTabIndex.value

    // Edit 1
    viewModel.updateTabContent(tabIndex, "step 1")
    assertEquals("step 1", viewModel.openTabs.value[tabIndex].content)
    assertTrue("Should be able to undo", viewModel.openTabs.value[tabIndex].undoStack.isNotEmpty())
    assertFalse("Redo stack must be empty after edit", viewModel.openTabs.value[tabIndex].redoStack.isNotEmpty())

    // Edit 2
    viewModel.updateTabContent(tabIndex, "step 2")
    assertEquals("step 2", viewModel.openTabs.value[tabIndex].content)
    assertEquals(2, viewModel.openTabs.value[tabIndex].undoStack.size)

    // Undo 1 -> should revert to step 1
    val undo1 = viewModel.undoTab(tabIndex)
    assertEquals("step 1", undo1)
    assertEquals("step 1", viewModel.openTabs.value[tabIndex].content)
    assertEquals(1, viewModel.openTabs.value[tabIndex].undoStack.size)
    assertEquals(1, viewModel.openTabs.value[tabIndex].redoStack.size)
    assertEquals("step 2", viewModel.openTabs.value[tabIndex].redoStack.last())

    // Undo 2 -> should revert to step 0
    val undo2 = viewModel.undoTab(tabIndex)
    assertEquals("step 0", undo2)
    assertEquals("step 0", viewModel.openTabs.value[tabIndex].content)
    assertEquals(0, viewModel.openTabs.value[tabIndex].undoStack.size)
    assertEquals(2, viewModel.openTabs.value[tabIndex].redoStack.size)

    // Undo 3 -> cannot undo beyond initial
    val undo3 = viewModel.undoTab(tabIndex)
    assertNull(undo3)

    // Redo 1 -> should advance to step 1
    val redo1 = viewModel.redoTab(tabIndex)
    assertEquals("step 1", redo1)
    assertEquals("step 1", viewModel.openTabs.value[tabIndex].content)
    assertEquals(1, viewModel.openTabs.value[tabIndex].undoStack.size)
    assertEquals(1, viewModel.openTabs.value[tabIndex].redoStack.size)

    // Redo 2 -> should advance to step 2
    val redo2 = viewModel.redoTab(tabIndex)
    assertEquals("step 2", redo2)
    assertEquals("step 2", viewModel.openTabs.value[tabIndex].content)
    assertEquals(2, viewModel.openTabs.value[tabIndex].undoStack.size)
    assertEquals(0, viewModel.openTabs.value[tabIndex].redoStack.size)

    // Redo 3 -> cannot redo beyond latest
    val redo3 = viewModel.redoTab(tabIndex)
    assertNull(redo3)
  }

  @Test
  fun testNewEditClearsRedoStack() {
    val file = ProjectFile(
      path = "test.txt",
      name = "test.txt",
      isDirectory = false,
      content = "A",
      sizeBytes = 1
    )
    val tab = EditorTab(id = "tab_t", file = file, content = "A", savedContent = "A")
    viewModel.openTab(tab)
    val tabIndex = viewModel.activeTabIndex.value

    viewModel.updateTabContent(tabIndex, "B")
    viewModel.updateTabContent(tabIndex, "C")

    // Undo back to B
    viewModel.undoTab(tabIndex)
    assertEquals("B", viewModel.openTabs.value[tabIndex].content)
    assertEquals(1, viewModel.openTabs.value[tabIndex].redoStack.size)

    // Typing a new edit "D" must clear the redo stack
    viewModel.updateTabContent(tabIndex, "D")
    assertEquals("D", viewModel.openTabs.value[tabIndex].content)
    assertTrue("Redo stack must be cleared after branching edit", viewModel.openTabs.value[tabIndex].redoStack.isEmpty())
  }
}
