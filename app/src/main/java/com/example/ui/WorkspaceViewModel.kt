package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WorkspaceViewModel(
  val repository: WorkspaceRepository = WorkspaceRepository()
) : ViewModel() {

  val projects: StateFlow<List<Project>> = repository.projects
  val activeProject: StateFlow<Project> = repository.activeProject
  val currentDestination: StateFlow<AppDestination> = repository.currentDestination
  val projectFiles: StateFlow<List<ProjectFile>> = repository.projectFiles
  val activeFile: StateFlow<ProjectFile> = repository.activeFile
  val editorContent: StateFlow<String> = repository.editorContent
  val isEditorDirty: StateFlow<Boolean> = repository.isEditorDirty

  val isAgentWorking: StateFlow<Boolean> = repository.isAgentWorking
  val agentStatusText: StateFlow<String> = repository.agentStatusText
  val agentSteps: StateFlow<List<AgentTaskStep>> = repository.agentSteps
  val toolExecutions: StateFlow<List<ToolExecution>> = repository.toolExecutions
  val pendingApproval: StateFlow<PendingApproval?> = repository.pendingApproval

  val fileDiffs: StateFlow<List<FileDiff>> = repository.fileDiffs
  val stagedFiles: StateFlow<Set<String>> = repository.stagedFiles
  val commitMessage: StateFlow<String> = repository.commitMessage
  val commitHistory: StateFlow<List<GitCommit>> = repository.commitHistory

  val terminalSessions: StateFlow<List<TerminalSession>> = repository.terminalSessions
  val activeTerminalSessionId: StateFlow<String> = repository.activeTerminalSessionId

  val providers: StateFlow<List<AIProvider>> = repository.providers
  val selectedModel: StateFlow<AIModel> = repository.selectedModel
  val permissions: StateFlow<AgentPermissions> = repository.permissions
  val searchQuery: StateFlow<String> = repository.searchQuery
  val isCommandPaletteOpen: StateFlow<Boolean> = repository.isCommandPaletteOpen
  val isModelSheetOpen: StateFlow<Boolean> = repository.isModelSheetOpen
  val isDevServerRunning: StateFlow<Boolean> = repository.isDevServerRunning

  fun navigateTo(dest: AppDestination) {
    repository.navigateTo(dest)
  }

  fun selectProject(project: Project) {
    repository.selectProject(project)
  }

  fun createProject(name: String, desc: String) {
    repository.createProject(name, desc)
  }

  fun openFile(file: ProjectFile) {
    repository.openFile(file)
  }

  fun updateEditorContent(content: String) {
    repository.updateEditorContent(content)
  }

  fun saveActiveFile() {
    repository.saveActiveFile()
  }

  fun createFile(relativePath: String, content: String = ""): Boolean {
    return repository.createFile(relativePath, content)
  }

  fun createDirectory(relativePath: String): Boolean {
    return repository.createDirectory(relativePath)
  }

  fun deleteFile(relativePath: String): Boolean {
    return repository.deleteFile(relativePath)
  }

  fun renameFile(oldPath: String, newName: String): Boolean {
    return repository.renameFile(oldPath, newName)
  }

  fun refreshFiles() {
    repository.refreshFiles()
  }

  fun toggleCommandPalette(open: Boolean? = null) {
    repository.toggleCommandPalette(open)
  }

  fun toggleModelSheet(open: Boolean? = null) {
    repository.toggleModelSheet(open)
  }

  fun selectModel(model: AIModel) {
    repository.selectModel(model)
  }

  fun updateSearchQuery(query: String) {
    repository.updateSearchQuery(query)
  }

  fun toggleFileStaged(filePath: String) {
    repository.toggleFileStaged(filePath)
  }

  fun stageAll() {
    repository.stageAll()
  }

  fun unstageAll() {
    repository.unstageAll()
  }

  fun updateCommitMessage(msg: String) {
    repository.updateCommitMessage(msg)
  }

  fun generateCommitMessageWithAgent() {
    repository.generateCommitMessageWithAgent()
  }

  fun commitStagedChanges() {
    repository.commitStagedChanges()
  }

  fun acceptAllDiffs() {
    repository.acceptAllDiffs()
  }

  fun rejectAllDiffs() {
    repository.rejectAllDiffs()
  }

  fun rejectDiff(filePath: String) {
    repository.rejectDiff(filePath)
  }

  fun selectTerminalSession(id: String) {
    repository.selectTerminalSession(id)
  }

  fun createTerminalSession(name: String = "bash") {
    repository.createTerminalSession(name)
  }

  fun closeTerminalSession(id: String) {
    repository.closeTerminalSession(id)
  }

  fun executeTerminalCommand(cmd: String) {
    repository.executeTerminalCommand(cmd)
  }

  fun interruptTerminal(sessionId: String = activeTerminalSessionId.value) {
    repository.interruptTerminal(sessionId)
  }

  fun resolveApproval(allowed: Boolean) {
    repository.resolveApproval(allowed)
  }

  fun requestSampleApproval() {
    repository.requestApproval(
      PendingApproval(
        id = "demo-appr",
        command = "npm install @tanstack/react-query",
        title = "Agent wants to run",
        impactDescription = "This will modify package.json and download npm packages over the network."
      )
    )
  }

  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    repository.updatePermissions(transform)
  }

  fun runAgentTask(prompt: String) {
    viewModelScope.launch {
      repository.runAgentTask(prompt)
    }
  }

  fun toggleDevServer() {
    repository.toggleDevServer()
  }
}
