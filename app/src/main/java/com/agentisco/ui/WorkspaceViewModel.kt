package com.agentisco.ui

import com.agentisco.settings.model.AIProvider
import com.agentisco.settings.model.AIModel
import com.agentisco.agent.model.AgentPermissions
import com.agentisco.agent.model.PendingApproval
import com.agentisco.agent.model.ToolExecution
import com.agentisco.agent.model.AgentTaskStep
import com.agentisco.core.model.AppDestination
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentisco.data.model.*
import com.agentisco.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One chronological entry in the live agent stream, rendered by AgentScreen. */
sealed class AgentStreamItem {
  data class Status(val id: Long, val text: String, val running: Boolean) : AgentStreamItem()
  data class AssistantText(val id: Long, val text: String, val running: Boolean) : AgentStreamItem()
  data class ToolCall(
    val id: Long,
    val name: String,
    val argsJson: String,
    val running: Boolean,
    val success: Boolean?,
    val summary: String,
    val detail: String,
    val exitCode: Int?
  ) : AgentStreamItem()

  data class Approval(
    val id: Long,
    val approvalId: String,
    val command: String,
    val title: String,
    val impact: String,
    val resolved: Boolean,
    val allowed: Boolean
  ) : AgentStreamItem()

  data class Final(val id: Long, val text: String, val success: Boolean) : AgentStreamItem()
}

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
  val terminalCommandHistory: StateFlow<List<String>> = repository.terminalCommandHistory

  val providers: StateFlow<List<AIProvider>> = repository.providers
  val aiModels: StateFlow<List<AIModel>> = repository.aiModels
  val selectedModel: StateFlow<AIModel?> = repository.selectedModel
  val connectionTests: StateFlow<Map<String, WorkspaceRepository.ConnectionTestState>> = repository.connectionTests
  val agentResponse: StateFlow<String> = repository.agentResponse

  /**
   * Hierarchical live agent stream, reduced from the runtime's real events:
   * status summaries, streamed text, tool calls with results, approvals, and
   * the final answer — all in chronological order.
   */
  private val _agentStream = MutableStateFlow<List<AgentStreamItem>>(emptyList())
  val agentStream: StateFlow<List<AgentStreamItem>> = _agentStream.asStateFlow()

  private val _isAgentCancelled = MutableStateFlow(false)
  val isAgentCancelled: StateFlow<Boolean> = _isAgentCancelled.asStateFlow()

  private var agentJob: kotlinx.coroutines.Job? = null
  private var streamItemId = 0L

  init {
    viewModelScope.launch {
      repository.agentEvents.collect { event ->
        _agentStream.update { items -> reduceAgentStream(items, event) }
      }
    }
  }

  private fun nextItemId() = ++streamItemId

  private fun reduceAgentStream(items: List<AgentStreamItem>, event: com.agentisco.agent.model.AgentStreamEvent): List<AgentStreamItem> {
    val closed = items.map { item ->
      when {
        item is AgentStreamItem.Status && item.running -> item.copy(running = false)
        item is AgentStreamItem.AssistantText && item.running -> item.copy(running = false)
        else -> item
      }
    }
    return when (event) {
      is com.agentisco.agent.model.AgentStreamEvent.TaskStarted -> emptyList()
      is com.agentisco.agent.model.AgentStreamEvent.Status -> {
        val last = items.lastOrNull()
        if (last is AgentStreamItem.Status && last.running) {
          items.dropLast(1) + last.copy(text = event.text)
        } else {
          closed + AgentStreamItem.Status(nextItemId(), event.text, running = true)
        }
      }
      is com.agentisco.agent.model.AgentStreamEvent.Token -> {
        val last = items.lastOrNull()
        if (last is AgentStreamItem.AssistantText && last.running) {
          items.dropLast(1) + last.copy(text = last.text + event.text)
        } else {
          closed + AgentStreamItem.AssistantText(nextItemId(), event.text, running = true)
        }
      }
      is com.agentisco.agent.model.AgentStreamEvent.ToolStarted ->
        closed + AgentStreamItem.ToolCall(
          id = nextItemId(), name = event.name, argsJson = event.argsJson,
          running = true, success = null, summary = "Running…", detail = "", exitCode = null
        )
      is com.agentisco.agent.model.AgentStreamEvent.ToolFinished -> {
        val index = items.indexOfLast { it is AgentStreamItem.ToolCall && it.name == event.name && it.running }
        val existing = items.getOrNull(index) as? AgentStreamItem.ToolCall
        val updated = AgentStreamItem.ToolCall(
          id = existing?.id ?: nextItemId(),
          name = event.name, argsJson = existing?.argsJson ?: "",
          running = false, success = event.success, summary = event.summary,
          detail = event.detail, exitCode = event.exitCode
        )
        if (index >= 0) {
          items.subList(0, index) + updated + items.subList(index + 1, items.size)
        } else {
          closed + updated
        }
      }
      is com.agentisco.agent.model.AgentStreamEvent.ApprovalRequested ->
        closed + AgentStreamItem.Approval(
          id = nextItemId(), approvalId = event.approvalId, command = event.command,
          title = event.title, impact = event.impact, resolved = false, allowed = false
        )
      is com.agentisco.agent.model.AgentStreamEvent.ApprovalResolved ->
        items.map { item ->
          if (item is AgentStreamItem.Approval && item.approvalId == event.approvalId) {
            item.copy(resolved = true, allowed = event.allowed)
          } else item
        }
      is com.agentisco.agent.model.AgentStreamEvent.Completed ->
        closed + AgentStreamItem.Final(nextItemId(), event.summary, success = true)
      is com.agentisco.agent.model.AgentStreamEvent.Failed ->
        closed + AgentStreamItem.Final(nextItemId(), event.message, success = false)
      is com.agentisco.agent.model.AgentStreamEvent.Cancelled ->
        closed + AgentStreamItem.Final(nextItemId(), event.message, success = false)
    }
  }

  /** Interrupts the running agent task (streamed request cancellation + tool loop stop). */
  fun cancelAgent() {
    _isAgentCancelled.value = true
    agentJob?.cancel()
  }
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
    repository.selectModel(model.id)
  }

  // ---- Provider & model management (Task: AI provider system) ----

  fun saveProvider(name: String, baseUrl: String, protocol: com.agentisco.settings.model.LLMProtocol, apiKey: String?, providerId: String? = null) {
    repository.saveProvider(name, baseUrl, protocol, apiKey, providerId)
  }

  fun deleteProvider(providerId: String) {
    repository.deleteProvider(providerId)
  }

  fun saveModel(
    providerId: String,
    modelId: String,
    displayName: String,
    contextWindow: Int?,
    maxOutputTokens: Int?,
    capabilities: com.agentisco.settings.model.ModelCapabilities,
    reasoning: com.agentisco.settings.model.ReasoningConfig?,
    recordId: String? = null
  ): AIModel? = repository.saveModel(providerId, modelId, displayName, contextWindow, maxOutputTokens, capabilities, reasoning, recordId)

  fun deleteModel(recordId: String) {
    repository.deleteModel(recordId)
  }

  fun testProviderConnection(providerId: String) {
    repository.testProviderConnection(providerId)
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

  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    repository.updatePermissions(transform)
  }

  fun runAgentTask(prompt: String) {
    _isAgentCancelled.value = false
    agentJob = viewModelScope.launch {
      repository.runAgentTask(prompt)
    }
  }

  fun toggleDevServer() {
    repository.toggleDevServer()
  }
}
