package com.example.data.repository

import com.example.data.model.FileDiff
import com.example.data.model.GitCommit
import com.example.data.model.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GitRepositoryManager(private val fileSystem: ProjectFileSystem) {

  // Baseline snapshots of files: projectId -> (relativePath -> content)
  private val baselineSnapshots = ConcurrentHashMap<String, MutableMap<String, String>>()

  // Commits per project
  private val projectCommits = ConcurrentHashMap<String, MutableList<GitCommit>>()

  fun initializeProjectBaseline(project: Project) {
    val map = baselineSnapshots.computeIfAbsent(project.id) { ConcurrentHashMap() }
    val dir = File(project.path)
    if (!dir.exists()) return

    dir.walkTopDown()
      .filter { it.isFile && !it.name.startsWith(".") && it.length() < 150_000 }
      .forEach { file ->
        val relPath = file.toRelativeString(dir)
        try {
          map[relPath] = file.readText()
        } catch (_: Exception) {}
      }

    val commits = projectCommits.computeIfAbsent(project.id) { mutableListOf() }
    if (commits.isEmpty()) {
      commits.add(
        GitCommit(
          hash = "a1b2c3d",
          message = "Initial commit for ${project.name}",
          author = "Sco Developer",
          date = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86400000L)),
          filesChanged = map.keys.take(5).toList()
        )
      )
    }
  }

  fun getChangedFiles(project: Project): List<String> {
    val baselines = baselineSnapshots[project.id] ?: emptyMap()
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()

    val currentFiles = mutableMapOf<String, String>()
    dir.walkTopDown()
      .filter { it.isFile && !it.name.startsWith(".") && it.length() < 150_000 }
      .forEach { file ->
        val relPath = file.toRelativeString(dir)
        try {
          currentFiles[relPath] = file.readText()
        } catch (_: Exception) {}
      }

    val changed = mutableListOf<String>()
    // Check modified or added
    for ((path, currentContent) in currentFiles) {
      val baseContent = baselines[path]
      if (baseContent == null || baseContent != currentContent) {
        changed.add(path)
      }
    }
    // Check deleted
    for (basePath in baselines.keys) {
      if (!currentFiles.containsKey(basePath)) {
        changed.add(basePath)
      }
    }
    return changed
  }

  fun computeAllDiffs(project: Project): List<FileDiff> {
    val baselines = baselineSnapshots[project.id] ?: emptyMap()
    val dir = File(project.path)
    if (!dir.exists()) return emptyList()

    val diffs = mutableListOf<FileDiff>()
    val changed = getChangedFiles(project)

    for (path in changed) {
      val baseContent = baselines[path] ?: ""
      val currentContent = fileSystem.readFile(project, path)
      val diff = DiffEngine.computeDiff(path, baseContent, currentContent)
      if (diff != null) {
        diffs.add(diff)
      }
    }
    return diffs
  }

  fun revertFile(project: Project, relativePath: String): Boolean {
    val baselines = baselineSnapshots[project.id] ?: return false
    val original = baselines[relativePath]
    return if (original != null) {
      fileSystem.writeFile(project, relativePath, original)
    } else {
      // It was newly added, so delete it
      fileSystem.deleteFile(project, relativePath)
    }
  }

  fun revertAllFiles(project: Project): Boolean {
    val changed = getChangedFiles(project)
    var allSuccess = true
    for (path in changed) {
      val success = revertFile(project, path)
      if (!success) allSuccess = false
    }
    return allSuccess
  }

  fun commit(project: Project, stagedFiles: Set<String>, message: String, author: String = "Sco Developer"): GitCommit? {
    if (stagedFiles.isEmpty()) return null
    val baselines = baselineSnapshots.computeIfAbsent(project.id) { ConcurrentHashMap() }

    // Update baseline to current content for all staged files
    for (path in stagedFiles) {
      val currentContent = fileSystem.readFile(project, path)
      val file = File(project.path, path)
      if (file.exists()) {
        baselines[path] = currentContent
      } else {
        baselines.remove(path)
      }
    }

    val hash = UUID.randomUUID().toString().replace("-", "").take(7)
    val commit = GitCommit(
      hash = hash,
      message = message.ifBlank { "Update project files" },
      author = author,
      date = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date()),
      filesChanged = stagedFiles.toList()
    )

    val commits = projectCommits.computeIfAbsent(project.id) { mutableListOf() }
    commits.add(0, commit)
    return commit
  }

  fun getCommitHistory(project: Project): List<GitCommit> {
    return projectCommits[project.id]?.toList() ?: emptyList()
  }
}
