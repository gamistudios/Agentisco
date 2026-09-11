package com.example.data.repository

import com.example.data.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WorkspaceRepository {

  // Current Active Project
  private val _projects = MutableStateFlow<List<Project>>(getSampleProjects())
  val projects: StateFlow<List<Project>> = _projects.asStateFlow()

  private val _activeProject = MutableStateFlow<Project>(_projects.value.first())
  val activeProject: StateFlow<Project> = _activeProject.asStateFlow()

  // App Navigation Destination
  private val _currentDestination = MutableStateFlow(AppDestination.AGENT)
  val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

  // Files in Project
  private val _projectFiles = MutableStateFlow<List<ProjectFile>>(getSampleFileTree())
  val projectFiles: StateFlow<List<ProjectFile>> = _projectFiles.asStateFlow()

  // Currently Active File in Editor
  private val _activeFile = MutableStateFlow<ProjectFile>(getSampleChatFile())
  val activeFile: StateFlow<ProjectFile> = _activeFile.asStateFlow()

  // Editor content & undo stack
  private val _editorContent = MutableStateFlow(getSampleChatFile().content)
  val editorContent: StateFlow<String> = _editorContent.asStateFlow()

  // Agent State
  private val _isAgentWorking = MutableStateFlow(false)
  val isAgentWorking: StateFlow<Boolean> = _isAgentWorking.asStateFlow()

  private val _agentStatusText = MutableStateFlow("Ready for tasks")
  val agentStatusText: StateFlow<String> = _agentStatusText.asStateFlow()

  private val _agentWorkingDurationSeconds = MutableStateFlow(0)
  val agentWorkingDurationSeconds: StateFlow<Int> = _agentWorkingDurationSeconds.asStateFlow()

  private val _agentSteps = MutableStateFlow<List<AgentTaskStep>>(getInitialAgentSteps())
  val agentSteps: StateFlow<List<AgentTaskStep>> = _agentSteps.asStateFlow()

  private val _toolExecutions = MutableStateFlow<List<ToolExecution>>(getInitialToolExecutions())
  val toolExecutions: StateFlow<List<ToolExecution>> = _toolExecutions.asStateFlow()

  private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
  val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

  // Diffs
  private val _fileDiffs = MutableStateFlow<List<FileDiff>>(getSampleDiffs())
  val fileDiffs: StateFlow<List<FileDiff>> = _fileDiffs.asStateFlow()

  // Git State
  private val _stagedFiles = MutableStateFlow<Set<String>>(setOf("src/components/Chat.tsx", "src/store/chatStore.ts"))
  val stagedFiles: StateFlow<Set<String>> = _stagedFiles.asStateFlow()

  private val _commitMessage = MutableStateFlow("Fix chat message lifecycle and store recreation")
  val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

  // Terminal Sessions
  private val _terminalSessions = MutableStateFlow<List<TerminalSession>>(getInitialTerminalSessions())
  val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

  private val _activeTerminalSessionId = MutableStateFlow("term-1")
  val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

  // AI Providers & Models
  private val _providers = MutableStateFlow<List<AIProvider>>(getInitialProviders())
  val providers: StateFlow<List<AIProvider>> = _providers.asStateFlow()

  private val _selectedModel = MutableStateFlow(
    AIModel(
      id = "glm-5.3-free",
      name = "GLM 5.3 Free",
      providerId = "tokenrouter",
      contextWindow = "1000k",
      hasTools = true,
      hasStreaming = true,
      hasReasoning = true,
      isFree = true
    )
  )
  val selectedModel: StateFlow<AIModel> = _selectedModel.asStateFlow()

  // Agent Permissions
  private val _permissions = MutableStateFlow(AgentPermissions())
  val permissions: StateFlow<AgentPermissions> = _permissions.asStateFlow()

  // Search Query & Results
  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  // Command Palette Open
  private val _isCommandPaletteOpen = MutableStateFlow(false)
  val isCommandPaletteOpen: StateFlow<Boolean> = _isCommandPaletteOpen.asStateFlow()

  // Model Selector Sheet Open
  private val _isModelSheetOpen = MutableStateFlow(false)
  val isModelSheetOpen: StateFlow<Boolean> = _isModelSheetOpen.asStateFlow()

  // Dev Server / Preview State
  private val _isDevServerRunning = MutableStateFlow(true)
  val isDevServerRunning: StateFlow<Boolean> = _isDevServerRunning.asStateFlow()

  // Actions
  fun navigateTo(destination: AppDestination) {
    _currentDestination.value = destination
  }

  fun selectProject(project: Project) {
    _activeProject.value = project
  }

  fun createProject(name: String, description: String) {
    val newProj = Project(
      id = "proj-${System.currentTimeMillis()}",
      name = name.ifBlank { "Untitled Project" },
      branch = "main",
      lastActivity = "Just now",
      changedFilesCount = 0,
      isDirty = false,
      description = description,
      path = "~/projects/${name.lowercase().replace(" ", "-")}"
    )
    _projects.update { listOf(newProj) + it }
    _activeProject.value = newProj
  }

  fun openFile(file: ProjectFile) {
    if (!file.isDirectory) {
      _activeFile.value = file
      _editorContent.value = file.content
      _currentDestination.value = AppDestination.EDITOR
    }
  }

  fun updateEditorContent(content: String) {
    _editorContent.value = content
  }

  fun toggleCommandPalette(open: Boolean? = null) {
    _isCommandPaletteOpen.value = open ?: !_isCommandPaletteOpen.value
  }

  fun toggleModelSheet(open: Boolean? = null) {
    _isModelSheetOpen.value = open ?: !_isModelSheetOpen.value
  }

  fun selectModel(model: AIModel) {
    _selectedModel.value = model
    _isModelSheetOpen.value = false
  }

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun toggleFileStaged(filePath: String) {
    _stagedFiles.update { current ->
      if (current.contains(filePath)) current - filePath else current + filePath
    }
  }

  fun updateCommitMessage(msg: String) {
    _commitMessage.value = msg
  }

  fun generateCommitMessageWithAgent() {
    val suggested = listOf(
      "fix(chat): prevent message store reset on conversation component mount",
      "refactor: memoize message listeners and stabilize chat state",
      "fix: resolve race condition in useMessages async loader hook",
      "feat(chat): retain loaded message cache during route transitions"
    ).random()
    _commitMessage.value = suggested
  }

  fun commitStagedChanges() {
    _stagedFiles.value = emptySet()
    _fileDiffs.value = emptyList()
    _activeProject.update { it.copy(changedFilesCount = 0, isDirty = false, lastActivity = "Just committed") }
  }

  fun acceptAllDiffs() {
    _fileDiffs.value = emptyList()
    _activeProject.update { it.copy(changedFilesCount = 0, isDirty = false) }
  }

  fun rejectAllDiffs() {
    _fileDiffs.value = emptyList()
  }

  // Terminal actions
  fun selectTerminalSession(id: String) {
    _activeTerminalSessionId.value = id
  }

  fun createTerminalSession(name: String = "bash") {
    val newId = "term-${System.currentTimeMillis()}"
    val newSession = TerminalSession(
      id = newId,
      name = name,
      currentDir = "~/projects/sco",
      lines = listOf(
        TerminalLine("AgentIDE Terminal Environment v2.4", TerminalLineType.INFO),
        TerminalLine("Type 'help' or commands like 'git status', 'npm test', 'ls'", TerminalLineType.INFO)
      )
    )
    _terminalSessions.update { it + newSession }
    _activeTerminalSessionId.value = newId
  }

  fun executeTerminalCommand(command: String) {
    val cleanCmd = command.trim()
    if (cleanCmd.isEmpty()) return

    val currentId = _activeTerminalSessionId.value
    _terminalSessions.update { list ->
      list.map { session ->
        if (session.id == currentId) {
          val newLines = session.lines.toMutableList()
          newLines.add(TerminalLine("$ $cleanCmd", TerminalLineType.COMMAND))

          when {
            cleanCmd == "clear" -> {
              newLines.clear()
            }
            cleanCmd.startsWith("npm test") -> {
              newLines.add(TerminalLine("Running Jest / Vitest test suite...", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("PASS src/components/__tests__/Chat.test.tsx", TerminalLineType.SUCCESS))
              newLines.add(TerminalLine("PASS src/store/__tests__/chatStore.test.ts", TerminalLineType.SUCCESS))
              newLines.add(TerminalLine("Tests: 42 passed, 42 total", TerminalLineType.SUCCESS))
              newLines.add(TerminalLine("Time:  1.482 s", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("Exit code 0", TerminalLineType.SUCCESS))
            }
            cleanCmd.startsWith("git status") -> {
              newLines.add(TerminalLine("On branch main", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("Your branch is ahead of 'origin/main' by 2 commits.", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("Changes not staged for commit:", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("  modified:   src/components/Chat.tsx", TerminalLineType.STDERR))
              newLines.add(TerminalLine("  modified:   src/store/chatStore.ts", TerminalLineType.STDERR))
              newLines.add(TerminalLine("  modified:   src/hooks/useMessages.ts", TerminalLineType.STDERR))
            }
            cleanCmd == "ls" || cleanCmd == "dir" -> {
              newLines.add(TerminalLine("package.json  README.md  tsconfig.json  vite.config.ts", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("src/          public/    node_modules/   dist/", TerminalLineType.STDOUT))
            }
            cleanCmd.startsWith("echo ") -> {
              newLines.add(TerminalLine(cleanCmd.removePrefix("echo ").trim('"'), TerminalLineType.STDOUT))
            }
            cleanCmd == "pwd" -> {
              newLines.add(TerminalLine("~/projects/sco", TerminalLineType.STDOUT))
            }
            cleanCmd == "help" -> {
              newLines.add(TerminalLine("Available commands: git status, git diff, npm test, npm run dev, ls, pwd, clear, cat <file>, echo <text>", TerminalLineType.INFO))
            }
            cleanCmd.startsWith("npm run dev") -> {
              newLines.add(TerminalLine("> sco-space@1.0.0 dev", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("> vite", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("VITE v5.2.0  ready in 280 ms", TerminalLineType.SUCCESS))
              newLines.add(TerminalLine("➜  Local:   http://localhost:5173/", TerminalLineType.SUCCESS))
            }
            else -> {
              newLines.add(TerminalLine("Executed: $cleanCmd", TerminalLineType.STDOUT))
              newLines.add(TerminalLine("Process completed with exit code 0", TerminalLineType.SUCCESS))
            }
          }
          session.copy(lines = newLines)
        } else session
      }
    }
  }

  // Permission handling
  fun updatePermissions(transform: (AgentPermissions) -> AgentPermissions) {
    _permissions.update(transform)
  }

  fun requestApproval(approval: PendingApproval) {
    _pendingApproval.value = approval
  }

  fun resolveApproval(allowed: Boolean) {
    val current = _pendingApproval.value
    _pendingApproval.value = null
    if (allowed && current != null) {
      // Execute the approved command in terminal
      executeTerminalCommand(current.command)
    }
  }

  // Run simulated or interactive Agent Task Workflow
  suspend fun runAgentTask(prompt: String) {
    if (_isAgentWorking.value) return

    _isAgentWorking.value = true
    _agentStatusText.value = "Analyzing project structure..."
    _agentWorkingDurationSeconds.value = 0

    // Reset steps to fresh state
    _agentSteps.value = listOf(
      AgentTaskStep("s1", "Understand project context", AgentStepStatus.RUNNING, "Inspecting package.json and component tree", listOf("package.json", "tsconfig.json")),
      AgentTaskStep("s2", "Locate relevant source files", AgentStepStatus.PENDING, "Searching for conversation and message handlers"),
      AgentTaskStep("s3", "Trace message store lifecycle", AgentStepStatus.PENDING, "Analyzing hooks and Zustand store mounts"),
      AgentTaskStep("s4", "Identify root state bug", AgentStepStatus.PENDING, "Pinpointing recreation of initial messages array"),
      AgentTaskStep("s5", "Implement code fix", AgentStepStatus.PENDING, "Updating Chat.tsx and chatStore.ts"),
      AgentTaskStep("s6", "Run project test suite", AgentStepStatus.PENDING, "Running unit & integration test suites"),
      AgentTaskStep("s7", "Verify diff & build", AgentStepStatus.PENDING, "Confirming clean compilation")
    )

    delay(900)
    // Step 1 complete, Step 2 running
    _agentSteps.update { list ->
      list.mapIndexed { idx, s ->
        when (idx) {
          0 -> s.copy(status = AgentStepStatus.COMPLETED)
          1 -> s.copy(status = AgentStepStatus.RUNNING)
          else -> s
        }
      }
    }
    _agentStatusText.value = "Searching for chat components..."

    // Add tool execution: Search
    _toolExecutions.update {
      listOf(
        ToolExecution(
          id = "tool-${System.currentTimeMillis()}",
          type = ToolType.SEARCH,
          title = "Search \"messages\"",
          subtitle = "24 matches · 8 files searched",
          details = "Chat.tsx:42, chatStore.ts:18, useMessages.ts:31"
        )
      ) + it
    }

    delay(1100)
    // Step 2 complete, Step 3 running
    _agentSteps.update { list ->
      list.mapIndexed { idx, s ->
        when (idx) {
          1 -> s.copy(status = AgentStepStatus.COMPLETED, filesInspected = listOf("Chat.tsx", "chatStore.ts", "useMessages.ts"))
          2 -> s.copy(status = AgentStepStatus.RUNNING)
          else -> s
        }
      }
    }
    _agentStatusText.value = "Tracing message store mount lifecycle..."

    // Add tool execution: Read file
    _toolExecutions.update {
      listOf(
        ToolExecution(
          id = "tool-${System.currentTimeMillis()}",
          type = ToolType.READ_FILE,
          title = "Read Chat.tsx",
          subtitle = "142 lines inspected",
          details = "Lines 40-75 inspected for useEffect triggers"
        )
      ) + it
    }

    delay(1200)
    // Step 3 complete, Step 4 complete with finding
    _agentSteps.update { list ->
      list.mapIndexed { idx, s ->
        when (idx) {
          2 -> s.copy(status = AgentStepStatus.COMPLETED)
          3 -> s.copy(
            status = AgentStepStatus.COMPLETED,
            finding = "Message state was being reset to empty array whenever conversationId prop changed without retaining cached cache."
          )
          4 -> s.copy(status = AgentStepStatus.RUNNING)
          else -> s
        }
      }
    }
    _agentStatusText.value = "Applying fix to Chat.tsx & chatStore.ts..."

    // Check if permission needed for package installation or modifying files
    if (_permissions.value.alwaysAskDangerous && prompt.contains("install", ignoreCase = true)) {
      _pendingApproval.value = PendingApproval(
        id = "appr-1",
        command = "npm install axios",
        title = "Agent wants to run",
        impactDescription = "This will modify package.json and lockfiles."
      )
    }

    delay(1300)
    // Step 5 complete, Step 6 running tests
    _agentSteps.update { list ->
      list.mapIndexed { idx, s ->
        when (idx) {
          4 -> s.copy(status = AgentStepStatus.COMPLETED)
          5 -> s.copy(status = AgentStepStatus.RUNNING)
          else -> s
        }
      }
    }
    _agentStatusText.value = "Running test suite in terminal..."

    // Add tool execution: Edit files
    _toolExecutions.update {
      listOf(
        ToolExecution(
          id = "tool-${System.currentTimeMillis()}",
          type = ToolType.EDIT_FILE,
          title = "Modified 3 files",
          subtitle = "Chat.tsx, chatStore.ts, useMessages.ts",
          details = "+42 lines, -18 lines applied"
        )
      ) + it
    }

    delay(1200)
    // Run tests tool
    _toolExecutions.update {
      listOf(
        ToolExecution(
          id = "tool-${System.currentTimeMillis()}",
          type = ToolType.TERMINAL,
          title = "$ npm test",
          subtitle = "42 tests passed · Exit code 0",
          exitCode = 0,
          output = "PASS Chat.test.tsx\nPASS chatStore.test.ts"
        )
      ) + it
    }

    // All steps complete
    _agentSteps.update { list ->
      list.map { it.copy(status = AgentStepStatus.COMPLETED) }
    }
    _isAgentWorking.value = false
    _agentStatusText.value = "Task completed · 3 files modified · 42 tests passed"
    _activeProject.update { it.copy(isDirty = true, changedFilesCount = 3, lastActivity = "Agent fixed chat loading") }
  }

  fun toggleDevServer() {
    _isDevServerRunning.update { !it }
  }

  companion object {
    fun getSampleProjects(): List<Project> {
      return listOf(
        Project(
          id = "sco-1",
          name = "ScoSpace",
          branch = "main",
          lastActivity = "12 min ago",
          changedFilesCount = 3,
          isDirty = true,
          activeSessionText = "Fix chat loading",
          description = "Modern collaborative workspace & real-time messaging canvas",
          path = "~/projects/scospace"
        ),
        Project(
          id = "pay-2",
          name = "PayHabesha",
          branch = "development",
          lastActivity = "Yesterday",
          changedFilesCount = 0,
          isDirty = false,
          activeSessionText = null,
          description = "Fintech payment processing engine and mobile banking client",
          path = "~/projects/payhabesha"
        ),
        Project(
          id = "fix-3",
          name = "FixNet",
          branch = "main",
          lastActivity = "3 days ago",
          changedFilesCount = 1,
          isDirty = true,
          activeSessionText = null,
          description = "Network diagnostic telemetry dashboard and ping agent",
          path = "~/projects/fixnet"
        )
      )
    }

    fun getSampleChatFile(): ProjectFile {
      return ProjectFile(
        path = "src/components/Chat.tsx",
        name = "Chat.tsx",
        isDirectory = false,
        language = "typescript",
        content = """import React, { useEffect, useState } from 'react';
import { useChatStore } from '../store/chatStore';
import { MessageList } from './MessageList';
import { PromptInput } from './PromptInput';

interface ChatProps {
  conversationId: string;
}

export const Chat: React.FC<ChatProps> = ({ conversationId }) => {
  // Access global message store
  const { messages, loadMessages, sendMessage, isStreaming } = useChatStore();
  const [input, setInput] = useState('');

  useEffect(() => {
    // Retain conversation state across re-renders
    if (conversationId) {
      loadMessages(conversationId);
    }
  }, [conversationId, loadMessages]);

  const handleSend = async () => {
    if (!input.trim() || isStreaming) return;
    const text = input;
    setInput('');
    await sendMessage(conversationId, text);
  };

  return (
    <div className="flex flex-col h-full bg-slate-900 text-slate-100">
      <header className="px-4 py-3 border-b border-slate-800 flex items-center justify-between">
        <h2 className="font-semibold text-sm">Agent Conversation</h2>
        <span className="text-xs text-emerald-400">● Connected</span>
      </header>

      <div className="flex-1 overflow-y-auto p-4">
        <MessageList messages={messages} />
      </div>

      <div className="p-4 border-t border-slate-800">
        <PromptInput
          value={input}
          onChange={setInput}
          onSubmit={handleSend}
          disabled={isStreaming}
        />
      </div>
    </div>
  );
};
"""
      )
    }

    fun getSampleFileTree(): List<ProjectFile> {
      return listOf(
        ProjectFile(
          path = "src",
          name = "src",
          isDirectory = true,
          children = listOf(
            ProjectFile(
              path = "src/components",
              name = "components",
              isDirectory = true,
              children = listOf(
                getSampleChatFile(),
                ProjectFile("src/components/MessageList.tsx", "MessageList.tsx", false, "// Message list component\nexport const MessageList = () => null;"),
                ProjectFile("src/components/PromptInput.tsx", "PromptInput.tsx", false, "// Prompt input\nexport const PromptInput = () => null;")
              )
            ),
            ProjectFile(
              path = "src/store",
              name = "store",
              isDirectory = true,
              children = listOf(
                ProjectFile(
                  path = "src/store/chatStore.ts",
                  name = "chatStore.ts",
                  isDirectory = false,
                  content = """import { create } from 'zustand';

export interface ChatState {
  messages: Array<{ id: string; text: string; sender: 'user' | 'agent' }>;
  isStreaming: boolean;
  loadMessages: (id: string) => Promise<void>;
  sendMessage: (id: string, text: string) => Promise<void>;
}

export const useChatStore = create<ChatState>((set) => ({
  messages: [],
  isStreaming: false,
  loadMessages: async (id) => {
    // Preserve cache and avoid unnecessary state flush
    const res = await fetch(`/api/conversations/${'$'}id/messages`);
    const data = await res.json();
    set({ messages: data });
  },
  sendMessage: async (id, text) => {
    set((s) => ({
      messages: [...s.messages, { id: Date.now().toString(), text, sender: 'user' }]
    }));
  }
}));
"""
                )
              )
            ),
            ProjectFile(
              path = "src/hooks",
              name = "hooks",
              isDirectory = true,
              children = listOf(
                ProjectFile("src/hooks/useMessages.ts", "useMessages.ts", false, "// Hook for message lifecycle\nexport const useMessages = () => ({});")
              )
            ),
            ProjectFile("src/App.tsx", "App.tsx", false, "export default function App() { return <div>IDE</div>; }"),
            ProjectFile("src/main.tsx", "main.tsx", false, "import React from 'react';\nimport ReactDOM from 'react-dom/client';")
          )
        ),
        ProjectFile(
          path = "package.json",
          name = "package.json",
          isDirectory = false,
          language = "json",
          content = """{
  "name": "scospace",
  "version": "1.0.0",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "zustand": "^4.5.2"
  },
  "devDependencies": {
    "typescript": "^5.4.5",
    "vite": "^5.2.0",
    "vitest": "^1.6.0"
  }
}"""
        ),
        ProjectFile(
          path = "README.md",
          name = "README.md",
          isDirectory = false,
          language = "markdown",
          content = "# ScoSpace\nMobile-first autonomous development workspace built on Android."
        ),
        ProjectFile(
          path = "vite.config.ts",
          name = "vite.config.ts",
          isDirectory = false,
          language = "typescript",
          content = "import { defineConfig } from 'vite';\nimport react from '@vitejs/plugin-react';\n\nexport default defineConfig({\n  plugins: [react()],\n  server: { port: 5173 }\n});"
        )
      )
    }

    fun getInitialAgentSteps(): List<AgentTaskStep> {
      return listOf(
        AgentTaskStep("s1", "Understand project", AgentStepStatus.COMPLETED, "Parsed package.json and project AST"),
        AgentTaskStep("s2", "Locate chat components", AgentStepStatus.COMPLETED, "Found Chat.tsx and useMessages.ts"),
        AgentTaskStep("s3", "Trace message lifecycle", AgentStepStatus.COMPLETED, "Identified state recreation during mount"),
        AgentTaskStep("s4", "Identify state bug", AgentStepStatus.COMPLETED, "Finding: Message state is recreated when the conversation component mounts.", listOf("Chat.tsx", "useMessages.ts", "chatStore.ts")),
        AgentTaskStep("s5", "Implementing fix", AgentStepStatus.COMPLETED, "Applied memoized store listener"),
        AgentTaskStep("s6", "Run tests", AgentStepStatus.COMPLETED, "Executed npm test - 42 passed"),
        AgentTaskStep("s7", "Verify behavior", AgentStepStatus.COMPLETED, "Clean compilation confirmed")
      )
    }

    fun getInitialToolExecutions(): List<ToolExecution> {
      return listOf(
        ToolExecution(
          id = "tool-edit-1",
          type = ToolType.EDIT_FILE,
          title = "Modified 3 files",
          subtitle = "Chat.tsx, chatStore.ts, useMessages.ts",
          details = "Lines 42-50 in Chat.tsx updated with dependency array"
        ),
        ToolExecution(
          id = "tool-term-1",
          type = ToolType.TERMINAL,
          title = "$ npm test",
          subtitle = "42 tests passed",
          exitCode = 0,
          output = "✓ 42 tests passed in 1.4s"
        ),
        ToolExecution(
          id = "tool-search-1",
          type = ToolType.SEARCH,
          title = "Search \"messages\"",
          subtitle = "24 matches · 8 files",
          details = "Chat.tsx, chatStore.ts, useMessages.ts, Chat.test.tsx"
        ),
        ToolExecution(
          id = "tool-read-1",
          type = ToolType.READ_FILE,
          title = "Read file",
          subtitle = "Chat.tsx",
          details = "Read lines 1 to 142"
        )
      )
    }

    fun getSampleDiffs(): List<FileDiff> {
      return listOf(
        FileDiff(
          filePath = "src/components/Chat.tsx",
          additionsCount = 28,
          deletionsCount = 12,
          lines = listOf(
            DiffLine(DiffLineType.UNCHANGED, 40, 40, "export const Chat: React.FC<ChatProps> = ({ conversationId }) => {"),
            DiffLine(DiffLineType.REMOVED, 41, null, "-  const messages = initial;"),
            DiffLine(DiffLineType.ADDED, null, 41, "+  const messages = store.messages;"),
            DiffLine(DiffLineType.UNCHANGED, 42, 42, "   const [input, setInput] = useState('');"),
            DiffLine(DiffLineType.REMOVED, 43, null, "-  useEffect(loadMessages, []);"),
            DiffLine(DiffLineType.ADDED, null, 43, "+  useEffect(() => {"),
            DiffLine(DiffLineType.ADDED, null, 44, "+    loadMessages(conversationId);"),
            DiffLine(DiffLineType.ADDED, null, 45, "+  }, [conversationId, loadMessages]);"),
            DiffLine(DiffLineType.UNCHANGED, 46, 46, "   return (")
          )
        ),
        FileDiff(
          filePath = "src/store/chatStore.ts",
          additionsCount = 14,
          deletionsCount = 6,
          lines = listOf(
            DiffLine(DiffLineType.UNCHANGED, 15, 15, "export const useChatStore = create<ChatState>((set) => ({"),
            DiffLine(DiffLineType.REMOVED, 16, null, "-  messages: initialMessages,"),
            DiffLine(DiffLineType.ADDED, null, 16, "+  messages: [],"),
            DiffLine(DiffLineType.UNCHANGED, 17, 17, "   isStreaming: false,")
          )
        )
      )
    }

    fun getInitialTerminalSessions(): List<TerminalSession> {
      return listOf(
        TerminalSession(
          id = "term-1",
          name = "bash",
          currentDir = "~/projects/sco",
          lines = listOf(
            TerminalLine("AgentIDE Bash Shell (Linux x86_64)", TerminalLineType.INFO),
            TerminalLine("~/projects/sco $ git status", TerminalLineType.COMMAND),
            TerminalLine("modified: src/components/Chat.tsx", TerminalLineType.STDERR),
            TerminalLine("modified: src/store/chatStore.ts", TerminalLineType.STDERR),
            TerminalLine("~/projects/sco $ npm test", TerminalLineType.COMMAND),
            TerminalLine("✓ 42 passed in 1.4s", TerminalLineType.SUCCESS)
          )
        ),
        TerminalSession(
          id = "term-2",
          name = "dev server",
          currentDir = "~/projects/sco",
          lines = listOf(
            TerminalLine("$ npm run dev", TerminalLineType.COMMAND),
            TerminalLine("VITE v5.2.0 ready in 280 ms", TerminalLineType.SUCCESS),
            TerminalLine("➜ Local:   http://localhost:5173/", TerminalLineType.SUCCESS),
            TerminalLine("➜ Network: use --host to expose", TerminalLineType.STDOUT)
          )
        ),
        TerminalSession(
          id = "term-3",
          name = "agent",
          currentDir = "~/projects/sco",
          lines = listOf(
            TerminalLine("[agent] Initialized workspace analysis", TerminalLineType.INFO),
            TerminalLine("[agent] Ran static typecheck: 0 errors", TerminalLineType.SUCCESS)
          )
        )
      )
    }

    fun getInitialProviders(): List<AIProvider> {
      return listOf(
        AIProvider(
          id = "tokenrouter",
          name = "TokenRouter",
          baseUrl = "https://api.tokenrouter.io/v1",
          apiKey = "tr-live-•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("glm-5.3-free", "GLM 5.3 Free", "tokenrouter", "1000k", hasTools = true, hasStreaming = true, hasReasoning = true, isFree = true),
            AIModel("glm-5.3", "GLM 5.3 Pro", "tokenrouter", "1000k", hasTools = true, hasStreaming = true, hasReasoning = true),
            AIModel("qwen-2.5-coder", "Qwen 2.5 Coder 32B", "tokenrouter", "128k", hasTools = true, hasStreaming = true),
            AIModel("deepseek-r1", "DeepSeek R1", "tokenrouter", "128k", hasTools = true, hasStreaming = true, hasReasoning = true)
          )
        ),
        AIProvider(
          id = "google",
          name = "Google Gemini",
          baseUrl = "https://generativelanguage.googleapis.com",
          apiKey = "AIzaSy•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("gemini-2.5-pro", "Gemini 2.5 Pro", "google", "2000k", hasTools = true, hasStreaming = true, hasVision = true, hasReasoning = true),
            AIModel("gemini-2.5-flash", "Gemini 2.5 Flash", "google", "1000k", hasTools = true, hasStreaming = true, hasVision = true)
          )
        ),
        AIProvider(
          id = "openai",
          name = "OpenAI",
          baseUrl = "https://api.openai.com/v1",
          apiKey = "sk-•••••••••••••",
          isConnected = true,
          models = listOf(
            AIModel("gpt-4o", "GPT-4o", "openai", "128k", hasTools = true, hasStreaming = true, hasVision = true),
            AIModel("o3-mini", "o3-mini", "openai", "200k", hasTools = true, hasStreaming = true, hasReasoning = true)
          )
        ),
        AIProvider(
          id = "custom",
          name = "Custom Gateway",
          baseUrl = "http://localhost:11434/v1",
          apiKey = "",
          isConnected = false,
          models = emptyList()
        )
      )
    }
  }
}
