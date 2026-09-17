import org.junit.Test
import org.junit.Assert.*
import com.agentisco.ui.WorkspaceViewModel

class VerifyFix {
    
    @Test
    fun `test that active file content matches disk content after initialization`() {
        val viewModel = WorkspaceViewModel()
        
        // Get initial file
        val initialFile = viewModel.activeFile.value
        
        // Read content directly from disk
        val diskContent = viewModel.repository.fileSystem.readFile(
            viewModel.repository.activeProject.value, initialFile.path
        )
        
        // The activeFile content should match the disk content
        assertEquals("Active file content should match disk content", 
            diskContent, initialFile.content)
        
        // Test the save functionality
        assertFalse("Should not be dirty initially", viewModel.isEditorDirty.value)
        
        val newContent = diskContent + "\n// modified"
        viewModel.updateEditorContent(newContent)
        assertTrue("Should be dirty after modification", viewModel.isEditorDirty.value)
        
        viewModel.saveActiveFile()
        assertFalse("Should not be dirty after save", viewModel.isEditorDirty.value)
        
        // Verify the content was saved
        val saved = viewModel.repository.fileSystem.readFile(
            viewModel.repository.activeProject.value, initialFile.path
        )
        assertTrue("Saved content should contain modification", saved.contains("// modified"))
    }
}