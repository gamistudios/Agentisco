package com.agentisco.workspace.filesystem

import android.content.Context
import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-project configuration stored as `.agentisco.json` in the project root. */
data class ProjectConfig(
  val name: String,
  val description: String = "",
  val createdAt: Long = 0L,
  /** Original folder the project was imported from ("" = none). */
  val sourcePath: String = "",
  /** Mirror workspace changes back to [sourcePath] automatically. */
  val autoSync: Boolean = true,
  val version: String = "1.0"
)

class ProjectFileSystem(private val baseDir: File) {

  companion object {
    const val CONFIG_FILE_NAME = ".agentisco.json"

    /**
     * Built-in dependency and build-output folders skipped by every scan,
     * search and import. Repos routinely carry hundreds of thousands of
     * generated files here; walking them makes opening a project unusably
     * slow. Overridable from Settings — see [ignoredDirs].
     */
    val DEFAULT_IGNORED_DIRS: Set<String> = setOf(
      "node_modules", "bower_components", "jspm_packages",
      "build", "dist", "out", "target", "coverage", "htmlcov",
      "__pycache__", ".venv", "venv", ".tox", ".eggs",
      ".pytest_cache", ".mypy_cache", ".gradle",
      ".next", ".nuxt", ".turbo", ".cache",
      ".dart_tool", ".pub-cache", "pods", "deriveddata",
      "cmake-build-debug", "cmake-build-release"
    )

    /** Effective exclusion list (lowercase folder names), set from Settings. */
    @Volatile
    var ignoredDirs: Set<String> = DEFAULT_IGNORED_DIRS

    fun isIgnoredName(name: String): Boolean = name.lowercase() in ignoredDirs

    fun isIgnoredDir(dir: File): Boolean = dir.isDirectory && isIgnoredName(dir.name)

    /** Entries hidden from the workspace tree: git internals and app metadata. */
    private fun isHiddenName(name: String): Boolean =
      name.startsWith(".git") || name == ".sco_meta" || name == CONFIG_FILE_NAME || isIgnoredName(name)

    fun readProjectConfig(dir: File): ProjectConfig? {
      val file = File(dir, CONFIG_FILE_NAME)
      if (!file.isFile) return null
      return runCatching {
        val obj = org.json.JSONObject(file.readText())
        ProjectConfig(
          name = obj.optString("name"),
          description = obj.optString("description"),
          createdAt = obj.optLong("createdAt"),
          sourcePath = obj.optString("sourcePath"),
          autoSync = obj.optBoolean("autoSync", true),
          version = obj.optString("version", "1.0")
        )
      }.getOrNull()
    }

    fun writeProjectConfig(dir: File, config: ProjectConfig): Boolean = runCatching {
      val obj = org.json.JSONObject()
        .put("name", config.name)
        .put("description", config.description)
        .put("createdAt", config.createdAt)
        .put("sourcePath", config.sourcePath)
        .put("autoSync", config.autoSync)
        .put("version", config.version)
      File(dir, CONFIG_FILE_NAME).writeText(obj.toString(2))
      true
    }.getOrDefault(false)
  }

  constructor(context: Context) : this(File(context.filesDir, "sco_projects"))

  init {
    if (!baseDir.exists()) {
      baseDir.mkdirs()
    }
  }

  fun getProjects(): List<Project> {
    val updatedDirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    return updatedDirs.map { dir ->
      val config = readProjectConfig(dir)
      val legacyDesc = File(dir, ".sco_meta").takeIf { it.exists() }?.readText()
      val legacyName = File(dir, ".sco_name").takeIf { it.exists() }?.readText()?.trim()
      val desc = config?.description ?: legacyDesc ?: "Active local project"
      val projName = config?.name?.ifBlank { null }
        ?: legacyName?.ifBlank { null }
        ?: when (dir.name.lowercase()) {
        "agentisco" -> "Agentisco"
        else -> dir.name.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
      }
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
    // Persist project configuration inside the workspace itself.
    writeProjectConfig(
      projDir,
      ProjectConfig(
        name = cleanName,
        description = description.ifBlank { "Created in Agentisco" },
        createdAt = System.currentTimeMillis()
      )
    )
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
  fun importProject(rootPath: File, displayName: String? = null, sourcePath: String = ""): Project {
    if (!rootPath.isDirectory || !rootPath.canRead()) {
      throw IllegalArgumentException("Folder not found or not readable: ${rootPath.absolutePath}")
    }
    val name = (displayName ?: rootPath.name).ifBlank { rootPath.name }
    val existing = readProjectConfig(rootPath)
    writeProjectConfig(
      rootPath,
      (existing ?: ProjectConfig(name = name, createdAt = System.currentTimeMillis())).copy(
        name = name,
        description = existing?.description ?: "Imported folder",
        sourcePath = sourcePath.ifBlank { existing?.sourcePath ?: "" }
      )
    )
    return Project(
      id = "proj-${rootPath.name}-${Integer.toHexString(rootPath.absolutePath.hashCode())}",
      name = name,
      branch = "main",
      lastActivity = "Just now",
      changedFilesCount = 0,
      isDirty = false,
      description = existing?.description ?: "Imported folder",
      path = rootPath.absolutePath,
      isImported = true
    )
  }

  /**
   * Recursively copies a folder tree into the workspace, skipping ignored
   * dependency/build folders. Returns the number of files copied.
   */
  fun copyFolder(from: File, to: File): Int {
    if (!from.isDirectory) return 0
    to.mkdirs()
    var count = 0
    from.listFiles()?.forEach { child ->
      if (child.isDirectory && isIgnoredDir(child)) return@forEach
      val target = File(to, child.name)
      if (child.isDirectory) {
        count += copyFolder(child, target)
      } else {
        runCatching {
          child.copyTo(target, overwrite = true)
          count++
        }
      }
    }
    return count
  }

  /**
   * One-way mirror of the workspace to the user's original folder: copies
   * new/updated files and removes files deleted in the workspace, so the
   * source folder ends up exactly matching the app workspace.
   *
   * Ignored folders ([ignoredDirs]) are never traversed and never pruned —
   * the workspace skips them on import, so they must not be deleted from the
   * user's original folder.
   */
  fun mirrorFolder(from: File, to: File): Pair<Int, Int> {
    if (!from.isDirectory) return 0 to 0
    to.mkdirs()
    var copied = 0
    var removed = 0
    from.listFiles()?.forEach { child ->
      if (child.isDirectory && isIgnoredDir(child)) return@forEach
      val target = File(to, child.name)
      if (child.isDirectory) {
        val (c, r) = mirrorFolder(child, target)
        copied += c; removed += r
      } else {
        val needsCopy = !target.exists() || target.lastModified() < child.lastModified() ||
          target.length() != child.length()
        if (needsCopy) {
          runCatching {
            child.copyTo(target, overwrite = true)
            copied++
          }
        }
      }
    }
    // Remove files the workspace no longer has.
    to.listFiles()?.forEach { target ->
      if (isIgnoredName(target.name)) return@forEach
      val source = File(from, target.name)
      if (!source.exists()) {
        if (target.isDirectory) {
          target.deleteRecursively()
          removed++
        } else {
          runCatching { target.delete() }
          removed++
        }
      }
    }
    return copied to removed
  }

  /**
   * Metadata-only tree scan (no file content is ever read).
   *
   * [maxDepth] bounds how many folder levels are materialized: 1 = root
   * entries only, 2 = root + one level of children, and so on. Callers that
   * need deeper browsing should use [listChildren] lazily instead.
   */
  fun getFileTree(project: Project, maxDepth: Int = Int.MAX_VALUE): List<ProjectFile> {
    val dir = File(project.path)
    if (!dir.exists() || maxDepth < 1) return emptyList()
    return scanDirectory(dir, dir, maxDepth)
  }

  /**
   * Direct children of one folder (non-recursive, no content read).
   * Returns null when [relativePath] is not a directory.
   */
  fun listChildren(project: Project, relativePath: String): List<ProjectFile>? {
    val root = File(project.path)
    val dir = if (relativePath.isBlank()) root else File(root, relativePath)
    if (!dir.isDirectory) return null
    return scanDirectory(dir, root, 1)
  }

  private fun scanDirectory(currentDir: File, rootDir: File, depthLeft: Int): List<ProjectFile> {
    val items = currentDir.listFiles() ?: return emptyList()
    // Sort directories first, then alphabetical
    val sorted = items.filter { !isHiddenName(it.name) }
      .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    return sorted.map { file ->
      ProjectFile(
        path = file.toRelativeString(rootDir),
        name = file.name,
        isDirectory = file.isDirectory,
        content = "",
        language = if (file.isDirectory) "folder" else languageFor(file.extension),
        children = if (file.isDirectory && depthLeft > 1) scanDirectory(file, rootDir, depthLeft - 1) else emptyList(),
        sizeBytes = if (file.isDirectory) 0L else file.length(),
        lastModified = file.lastModified()
      )
    }
  }

  private fun languageFor(extension: String): String = when (extension.lowercase()) {
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

  /**
   * Permanently deletes a project's workspace folder from disk.
   *
   * Only ever touches a folder inside the app's projects root — a project
   * registered from an arbitrary location is left alone, and the original
   * folder an imported project came from ([Project.sourcePath]) is never
   * touched. Returns false when the folder was outside the projects root or
   * the delete failed, so callers can still unregister the project.
   */
  fun deleteProjectFolder(project: Project): Boolean {
    return try {
      val root = File(project.path)
      if (!root.isDirectory) return false
      val rootCanonical = root.canonicalPath
      val baseCanonical = baseDir.canonicalPath
      // Refuse anything that isn't strictly inside the projects root.
      if (rootCanonical != baseCanonical && !rootCanonical.startsWith("$baseCanonical${File.separator}")) {
        android.util.Log.w(
          "ScoOS-Projects",
          "Refusing to delete project folder outside projects root: $rootCanonical"
        )
        return false
      }
      root.deleteRecursively()
    } catch (e: Exception) {
      android.util.Log.e("ScoOS-Projects", "Failed to delete project folder ${project.path}", e)
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

  /** Full-text search across the project. Ignored folders are never walked. */
  fun searchInProject(project: Project, query: String, maxMatches: Int = 500): List<SearchMatch> {
    if (query.isBlank()) return emptyList()
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()

    val matches = mutableListOf<SearchMatch>()
    dir.walkTopDown()
      .onEnter { !isIgnoredDir(it) }
      .filter { it.isFile && !it.name.startsWith(".") && it.length() < 200_000 }
      .forEach { file ->
        if (matches.size >= maxMatches) return@forEach
        val relPath = file.toRelativeString(dir)
        try {
          file.useLines { lines ->
            lines.forEachIndexed { index, line ->
              if (matches.size >= maxMatches) return@forEachIndexed
              if (line.contains(query, ignoreCase = true)) {
                matches.add(SearchMatch(filePath = relPath, lineNumber = index + 1, lineText = line.trim()))
              }
            }
          }
        } catch (_: Exception) {}
      }
    return matches
  }

  /** Paths whose file or folder name contains [query] (case-insensitive), capped at [limit]. */
  fun findFilesByName(project: Project, query: String, limit: Int = 200): List<ProjectFile> {
    if (query.isBlank()) return emptyList()
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()
    val q = query.trim().lowercase()
    return dir.walkTopDown()
      .onEnter { !isIgnoredDir(it) }
      .filter { it != dir && !isHiddenName(it.name) && it.name.lowercase().contains(q) }
      .take(limit)
      .map { file ->
        ProjectFile(
          path = file.toRelativeString(dir),
          name = file.name,
          isDirectory = file.isDirectory,
          content = "",
          language = if (file.isDirectory) "folder" else languageFor(file.extension),
          sizeBytes = if (file.isDirectory) 0L else file.length(),
          lastModified = file.lastModified()
        )
      }
      .toList()
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
