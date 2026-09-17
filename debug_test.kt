import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.data.repository.WorkspaceRepository
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.data.model.Project
import java.io.File

// Debug test to understand the save issue
fun main() {
    println("=== Creating WorkspaceViewModel ===")
    val viewModel = WorkspaceViewModel()
    
    println("Initial state:")
    println("  Active file: ${viewModel.activeFile.value}")
    println("  Active file content: '${viewModel.activeFile.value.content}'")
    println("  Editor content: '${viewModel.editorContent.value}'")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    println("  Project path: ${viewModel.repository.activeProject.value.path}")
    
    // Get initial file
    val initialFile = viewModel.activeFile.value
    println("\nInitial file path: ${initialFile.path}")
    
    // Try reading the file directly
    println("\nReading file directly from filesystem:")
    val project = viewModel.repository.activeProject.value
    val directRead = viewModel.repository.fileSystem.readFile(project, initialFile.path)
    println("Direct read: '$directRead'")
    
    // Update content
    val newContent = viewModel.editorContent.value + "\n// modified"
    println("\nUpdating content to: '$newContent'")
    viewModel.updateEditorContent(newContent)
    
    println("After update:")
    println("  Active file content: '${viewModel.activeFile.value.content}'")
    println("  Editor content: '${viewModel.editorContent.value}'")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    
    // Save the file
    println("\nSaving file...")
    viewModel.saveActiveFile()
    
    println("After save:")
    println("  Active file content: '${viewModel.activeFile.value.content}'")
    println("  Editor content: '${viewModel.editorContent.value}'")
    println("  Is dirty: ${viewModel.isEditorDirty.value}")
    
    // Read saved content
    println("\nReading saved content...")
    val saved = viewModel.repository.fileSystem.readFile(
        viewModel.repository.activeProject.value, initialFile.path
    )
    println("Saved content: '$saved'")
    println("Contains 'modified': ${saved.contains("// modified")}")
    println("Contains '\nmodified': ${saved.contains("\n// modified")}")
    
    // Also check if the file exists and list its contents
    val file = File(project.path, initialFile.path)
    println("\nFile info:")
    println("  Exists: ${file.exists()}")
    println("  Absolute path: ${file.absolutePath}")
    println("  Length: ${file.length()}")
}