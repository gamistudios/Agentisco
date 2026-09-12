package com.agentisco.workspace.filesystem

import android.content.Context
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectFileSystem(private val baseDir: File) {

  constructor(context: Context) : this(File(context.filesDir, "sco_projects"))

  init {
    if (!baseDir.exists()) {
      baseDir.mkdirs()
    }
    initializeDefaultProjectsIfEmpty()
  }

  fun getProjects(): List<Project> {
    val dirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    if (dirs.isEmpty()) {
      initializeDefaultProjectsIfEmpty()
    }
    val updatedDirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    return updatedDirs.map { dir ->
      val descFile = File(dir, ".sco_meta")
      val desc = if (descFile.exists()) descFile.readText() else "Active local project"
      val nameFile = File(dir, ".sco_name")
      val projName = if (nameFile.exists() && nameFile.readText().isNotBlank()) {
        nameFile.readText().trim()
      } else when (dir.name.lowercase()) {
        "agentisco" -> "Agentisco"
        else -> dir.name.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
      }
      val fileCount = dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.count()
      Project(
        id = "proj-${dir.name}",
        name = projName,
        branch = "main",
        lastActivity = "Active",
        changedFilesCount = 0,
        isDirty = false,
        description = desc,
        path = dir.absolutePath
      )
    }
  }

  /**
   * Suggests a fresh default root for a new project under the projects base
   * directory (`~/projects/<name>` from the user's point of view).
   */
  fun suggestDefaultRoot(name: String): File {
    val slug = name.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
      .ifBlank { "project-${System.currentTimeMillis()}" }
    var dir = File(baseDir, slug)
    var n = 2
    while (dir.exists()) {
      dir = File(baseDir, "$slug-$n")
      n++
    }
    return dir
  }

  /** The real folder new projects live in when no custom location is chosen. */
  fun defaultProjectsRoot(): File = baseDir

  /**
   * Creates a project rooted at [rootPath], or at a fresh default folder under
   * the projects base directory when [rootPath] is null. Scaffolds starter
   * files only in a newly created/empty folder — never over existing content.
   */
  fun createProject(name: String, description: String, rootPath: File? = null): Project {
    val cleanName = name.ifBlank { "Untitled Project" }
    val slug = cleanName.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-').ifBlank { "project-${System.currentTimeMillis()}" }
    val projDir = rootPath ?: File(baseDir, slug)
    val existedBefore = projDir.exists()
    if (!projDir.exists() && !projDir.mkdirs()) {
      throw java.io.IOException("Could not create folder: ${projDir.absolutePath}")
    }
    if (!projDir.isDirectory || !projDir.canRead()) {
      throw java.io.IOException("Folder is not usable: ${projDir.absolutePath}")
    }
    // write metadata and name
    File(projDir, ".sco_name").writeText(cleanName)
    File(projDir, ".sco_meta").writeText(description.ifBlank { "Created in Agentisco" })
    // Scaffold starter files only when the folder has no content (never
    // overwrite whatever the user pointed us at).
    val hasContent = projDir.listFiles()?.any { !it.name.startsWith(".") } == true
    if (!hasContent) {
      // create basic README and package.json
      File(projDir, "README.md").writeText("# $cleanName\n\n${description}\n")
      File(projDir, "package.json").writeText(
        """
        {
          "name": "$slug",
          "version": "1.0.0",
          "private": true,
          "scripts": {
            "dev": "vite",
            "build": "tsc && vite build",
            "test": "vitest run"
          }
        }
        """.trimIndent()
      )
      val srcDir = File(projDir, "src")
      srcDir.mkdirs()
      File(srcDir, "index.ts").writeText("// Entry point for $name\nconsole.log('Starting $name');\n")
    }

    return Project(
      id = "proj-${projDir.name}-${Integer.toHexString(projDir.absolutePath.hashCode())}",
      name = name.ifBlank { "Untitled Project" },
      branch = "main",
      lastActivity = "Just now",
      changedFilesCount = 0,
      isDirty = false,
      description = description,
      path = projDir.absolutePath,
      isImported = existedBefore
    )
  }

  /**
   * Registers an existing folder as a project without touching its content.
   * Throws [IllegalArgumentException] when the folder does not exist or is
   * not readable.
   */
  fun importProject(rootPath: File, displayName: String? = null): Project {
    if (!rootPath.isDirectory || !rootPath.canRead()) {
      throw IllegalArgumentException("Folder not found or not readable: ${rootPath.absolutePath}")
    }
    val name = (displayName ?: rootPath.name).ifBlank { rootPath.name }
    val metaFile = File(rootPath, ".sco_name")
    if (!metaFile.exists()) {
      runCatching { metaFile.writeText(name) }
      runCatching { File(rootPath, ".sco_meta").writeText("Imported folder") }
    }
    return Project(
      id = "proj-${rootPath.name}-${Integer.toHexString(rootPath.absolutePath.hashCode())}",
      name = name,
      branch = "main",
      lastActivity = "Just now",
      changedFilesCount = 0,
      isDirty = false,
      description = File(rootPath, ".sco_meta").takeIf { it.exists() }?.readText().orEmpty(),
      path = rootPath.absolutePath,
      isImported = true
    )
  }

  fun getFileTree(project: Project): List<ProjectFile> {
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()
    return scanDirectory(dir, dir)
  }

  private fun scanDirectory(currentDir: File, rootDir: File): List<ProjectFile> {
    val items = currentDir.listFiles() ?: return emptyList()
    // Sort directories first, then alphabetical
    val sorted = items.filter { !it.name.startsWith(".git") && it.name != ".sco_meta" }
      .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    return sorted.map { file ->
      val relativePath = file.toRelativeString(rootDir)
      if (file.isDirectory) {
        ProjectFile(
          path = relativePath,
          name = file.name,
          isDirectory = true,
          content = "",
          language = "folder",
          children = scanDirectory(file, rootDir),
          sizeBytes = 0L,
          lastModified = file.lastModified()
        )
      } else {
        val ext = file.extension.lowercase()
        val lang = when (ext) {
          "ts", "tsx" -> "typescript"
          "js", "jsx" -> "javascript"
          "kt", "kts" -> "kotlin"
          "json" -> "json"
          "md" -> "markdown"
          "html" -> "html"
          "css" -> "css"
          "sh" -> "shell"
          "py" -> "python"
          else -> "text"
        }
        val content = try {
          if (file.length() < 100_000) file.readText() else "[File too large to display in preview]"
        } catch (_: Exception) {
          ""
        }
        ProjectFile(
          path = relativePath,
          name = file.name,
          isDirectory = false,
          content = content,
          language = lang,
          children = emptyList(),
          sizeBytes = file.length(),
          lastModified = file.lastModified()
        )
      }
    }
  }

  fun readFile(project: Project, relativePath: String): String {
    val file = File(project.path, relativePath)
    return if (file.exists() && file.isFile) {
      file.readText()
    } else {
      ""
    }
  }

  fun exists(project: Project, relativePath: String): Boolean {
    return File(project.path, relativePath).exists()
  }

  fun writeFile(project: Project, relativePath: String, content: String): Boolean {
    return try {
      val file = File(project.path, relativePath)
      file.parentFile?.mkdirs()
      file.writeText(content)
      true
    } catch (_: Exception) {
      false
    }
  }

  fun createFile(project: Project, relativePath: String, initialContent: String = ""): Boolean {
    return try {
      val file = File(project.path, relativePath)
      file.parentFile?.mkdirs()
      if (!file.exists()) {
        file.writeText(initialContent)
        true
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  fun createDirectory(project: Project, relativePath: String): Boolean {
    return try {
      val dir = File(project.path, relativePath)
      dir.mkdirs()
    } catch (_: Exception) {
      false
    }
  }

  fun deleteFile(project: Project, relativePath: String): Boolean {
    return try {
      val file = File(project.path, relativePath)
      if (file.exists()) {
        if (file.isDirectory) file.deleteRecursively() else file.delete()
      } else false
    } catch (_: Exception) {
      false
    }
  }

  fun renameFile(project: Project, oldRelativePath: String, newName: String): Boolean {
    return try {
      val file = File(project.path, oldRelativePath)
      if (!file.exists()) return false
      val newFile = File(file.parentFile, newName)
      file.renameTo(newFile)
    } catch (_: Exception) {
      false
    }
  }

  fun searchInProject(project: Project, query: String): List<SearchMatch> {
    if (query.isBlank()) return emptyList()
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()

    val matches = mutableListOf<SearchMatch>()
    dir.walkTopDown()
      .filter { it.isFile && !it.name.startsWith(".") && it.length() < 200_000 }
      .forEach { file ->
        val relPath = file.toRelativeString(dir)
        try {
          file.useLines { lines ->
            lines.forEachIndexed { index, line ->
              if (line.contains(query, ignoreCase = true)) {
                matches.add(SearchMatch(filePath = relPath, lineNumber = index + 1, lineText = line.trim()))
              }
            }
          }
        } catch (_: Exception) {}
      }
    return matches
  }

  private fun initializeDefaultProjectsIfEmpty() {
    val scoDir = File(baseDir, "agentisco")
    if (!scoDir.exists() || (scoDir.list()?.isEmpty() == true)) {
      scoDir.mkdirs()
      File(scoDir, ".sco_name").writeText("Agentisco")
      File(scoDir, ".sco_meta").writeText("Fullstack AI Agent Chat workspace with Zustand store")
      File(scoDir, "package.json").writeText(
        """
        {
          "name": "agentisco",
          "version": "1.0.0",
          "private": true,
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
        }
        """.trimIndent()
      )

      File(scoDir, "README.md").writeText(
        """
        # Agentisco Agent Workspace
        Mobile-first AI agent development environment.
        - Built with TypeScript & React
        - Message caching and streaming handlers
        """.trimIndent()
      )

      File(scoDir, "tsconfig.json").writeText(
        """
        {
          "compilerOptions": {
            "target": "ESNext",
            "module": "ESNext",
            "jsx": "react-jsx",
            "strict": true
          }
        }
        """.trimIndent()
      )

      File(scoDir, "vite.config.ts").writeText(
        """
        import { defineConfig } from 'vite';
        import react from '@vitejs/plugin-react';

        export default defineConfig({
          plugins: [react()],
          server: { port: 5173 }
        });
        """.trimIndent()
      )

      val compDir = File(scoDir, "src/components")
      compDir.mkdirs()
      File(compDir, "Chat.tsx").writeText(
        """
        import React, { useEffect, useState } from 'react';
        import { useChatStore } from '../store/chatStore';

        export interface ChatProps {
          conversationId: string;
          initialTitle?: string;
        }

        export const Chat: React.FC<ChatProps> = ({ conversationId, initialTitle }) => {
          const { messages, fetchMessages, appendMessage } = useChatStore();
          const [input, setInput] = useState('');

          useEffect(() => {
            fetchMessages(conversationId);
          }, [conversationId]);

          return (
            <div className="chat-container">
              <h2>{initialTitle || 'Active Session'}</h2>
              <div className="messages-list">
                {messages.map((m) => (
                  <div key={m.id} className={'message ' + m.role}>
                    <span>{m.text}</span>
                  </div>
                ))}
              </div>
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Type instructions..."
              />
            </div>
          );
        };
        """.trimIndent()
      )

      val storeDir = File(scoDir, "src/store")
      storeDir.mkdirs()
      File(storeDir, "chatStore.ts").writeText(
        """
        import { create } from 'zustand';

        export interface Message {
          id: string;
          role: 'user' | 'assistant';
          text: string;
          timestamp: number;
        }

        interface ChatState {
          messages: Message[];
          cachedMap: Record<string, Message[]>;
          fetchMessages: (conversationId: string) => void;
          appendMessage: (msg: Message) => void;
        }

        export const useChatStore = create<ChatState>((set, get) => ({
          messages: [],
          cachedMap: {},
          fetchMessages: (conversationId) => {
            const cached = get().cachedMap[conversationId];
            if (cached) {
              set({ messages: cached });
              return;
            }
            // Load messages
            const initial: Message[] = [
              { id: '1', role: 'assistant', text: 'Hello! How can I assist you?', timestamp: Date.now() }
            ];
            set((state) => ({
              messages: initial,
              cachedMap: { ...state.cachedMap, [conversationId]: initial }
            }));
          },
          appendMessage: (msg) => {
            set((state) => ({
              messages: [...state.messages, msg]
            }));
          }
        }));
        """.trimIndent()
      )

      val hooksDir = File(scoDir, "src/hooks")
      hooksDir.mkdirs()
      File(hooksDir, "useMessages.ts").writeText(
        """
        import { useEffect } from 'react';
        import { useChatStore } from '../store/chatStore';

        export function useMessages(conversationId: string) {
          const { messages, fetchMessages } = useChatStore();
          useEffect(() => {
            fetchMessages(conversationId);
          }, [conversationId]);
          return { messages };
        }
        """.trimIndent()
      )

      val testDir = File(scoDir, "tests")
      testDir.mkdirs()
      File(testDir, "Chat.test.tsx").writeText(
        """
        import { describe, it, expect } from 'vitest';

        describe('Chat suite', () => {
          it('renders messages correctly', () => {
            expect(true).toBe(true);
          });
        });
        """.trimIndent()
      )
    }
  }
}

data class SearchMatch(
  val filePath: String,
  val lineNumber: Int,
  val lineText: String
)
