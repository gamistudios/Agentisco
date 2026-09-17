import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectFile

// Simple test to debug the save issue
fun main() {
    val viewModel = WorkspaceViewModel()
    println("Initial state:")
    println("  Active file: ${viewModel.activeFile.value}")
    println("  Editor content: '${viewModel.editorContent.value}'")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    
    // Get initial file
    val initialFile = viewModel.activeFile.value
    println("\nInitial file path: ${initialFile.path}")
    println("Initial file content: '${initialFile.content}'")
    
    // Update content
    val newContent = viewModel.editorContent.value + "\n// modified"
    println("\nUpdating content to: '$newContent'")
    viewModel.updateEditorContent(newContent)
    
    println("After update:")
    println("  Editor content: '${viewModel.editorContent.value}'")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    
    // Save the file
    println("\nSaving file...")
    viewModel.saveActiveFile()
    
    println("After save:")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    
    // Read saved content
    println("\nReading saved content...")
    val saved = viewModel.repository.fileSystem.readFile(
        viewModel.repository.activeProject.value, initialFile.path
    )
    println("Saved content: '$saved'")
    
    println("\nTest result: ${saved.contains("// modified")}")
}