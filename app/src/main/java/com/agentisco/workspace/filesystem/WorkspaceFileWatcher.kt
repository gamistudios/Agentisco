package com.agentisco.workspace.filesystem

import android.os.FileObserver
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time filesystem observer for the active workspace.
 *
 * Recursively watches the project directory using Android's [FileObserver] (inotify-based),
 * filtering out large non-working-tree directories (like `.git/objects`, `node_modules`, `build`),
 * while keeping watch on `.git/HEAD` and `.git/index` so branch switches and Git index modifications
 * trigger updates immediately.
 *
 * Events are debounced (250ms) to consolidate multi-file writes (builds, batch edits, git operations)
 * into a single timely refresh pass.
 */
class WorkspaceFileWatcher(
  private val coroutineScope: CoroutineScope,
  private val onDirectoryChanged: () -> Unit
) {

  private val activeObservers = ConcurrentHashMap<String, FileObserver>()
  private var currentRoot: File? = null
  private var debounceJob: Job? = null

  companion object {
    private const val DEBOUNCE_MS = 350L

    private val IGNORED_FOLDER_NAMES = setOf(
      ".git",
      "objects",
      "node_modules",
      ".gradle",
      "build",
      "dist",
      "out",
      "target",
      ".idea",
      ".scannerwork",
      ".cache",
      ".turbo"
    )

    private const val WATCH_MASK = FileObserver.CREATE or
      FileObserver.DELETE or
      FileObserver.MODIFY or
      FileObserver.MOVED_FROM or
      FileObserver.MOVED_TO or
      FileObserver.CLOSE_WRITE
  }

  fun setRoot(root: File?) {
    stop()
    if (root == null || !root.isDirectory) {
      currentRoot = null
      return
    }
    currentRoot = root
    start()
  }

  fun start() {
    val root = currentRoot ?: return
    if (!root.isDirectory) return

    stop()
    registerTree(root)
  }

  fun stop() {
    debounceJob?.cancel()
    debounceJob = null
    activeObservers.values.forEach { runCatching { it.stopWatching() } }
    activeObservers.clear()
  }

  private fun registerTree(dir: File) {
    if (!dir.isDirectory) return
    val path = dir.absolutePath

    // Prevent watching redundant large metadata directories
    if (IGNORED_FOLDER_NAMES.contains(dir.name)) return

    if (!activeObservers.containsKey(path)) {
      val obs = createObserver(dir)
      activeObservers[path] = obs
      runCatching { obs.startWatching() }
    }

    // Traverse children up to a reasonable depth
    dir.listFiles()?.forEach { child ->
      if (child.isDirectory && !IGNORED_FOLDER_NAMES.contains(child.name)) {
        registerTree(child)
      }
    }
  }

  private fun notifyChange(changedPath: String?) {
    // If a new directory was created, attach an observer to it
    if (changedPath != null) {
      val f = File(changedPath)
      if (f.isDirectory && !IGNORED_FOLDER_NAMES.contains(f.name) && !activeObservers.containsKey(f.absolutePath)) {
        registerTree(f)
      }
    }

    debounceJob?.cancel()
    debounceJob = coroutineScope.launch {
      delay(DEBOUNCE_MS)
      onDirectoryChanged()
    }
  }

  private fun createObserver(dir: File): FileObserver {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      ModernDirectoryObserver(dir)
    } else {
      @Suppress("DEPRECATION")
      LegacyDirectoryObserver(dir.absolutePath)
    }
  }

  private fun handleFileEvent(folderPath: String, path: String?) {
    if (path == null) return
    // Ignore internal git objects, locks, and swap/temp files to prevent feedback loops
    if (path.endsWith(".swp") ||
        path.endsWith(".tmp") ||
        path.endsWith(".lock") ||
        path == "index.lock" ||
        path == "objects" ||
        folderPath.contains("/.git") ||
        folderPath.endsWith("/.git")
    ) {
      return
    }
    val fullPath = "$folderPath/$path"
    notifyChange(fullPath)
  }

  @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
  private inner class ModernDirectoryObserver(private val folder: File) : FileObserver(folder, WATCH_MASK) {
    override fun onEvent(event: Int, path: String?) {
      handleFileEvent(folder.absolutePath, path)
    }
  }

  @Suppress("DEPRECATION")
  private inner class LegacyDirectoryObserver(private val folderPath: String) : FileObserver(folderPath, WATCH_MASK) {
    override fun onEvent(event: Int, path: String?) {
      handleFileEvent(folderPath, path)
    }
  }
}
