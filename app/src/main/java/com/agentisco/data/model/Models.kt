package com.agentisco.data.model

import com.agentisco.core.model.AppDestination

data class Project(
  val id: String,
  val name: String,
  val branch: String,
  val lastActivity: String,
  val changedFilesCount: Int = 0,
  val isDirty: Boolean = false,
  val activeSessionText: String? = null,
  val description: String = "",
  val path: String = "~/projects",
  /** True when the project's root folder no longer exists on disk. */
  val isMissing: Boolean = false,
  /** True when the project was registered from an existing folder. */
  val isImported: Boolean = false,
  /** The original folder this project was imported from ("" = none). */
  val sourcePath: String = "",
  /** Mirror changes back to [sourcePath] automatically. */
  val autoSyncToSource: Boolean = true,
  /**
   * Real on-disk size of the project folder in bytes, measured by
   * [com.agentisco.workspace.filesystem.ProjectMetadataScanner] off the main
   * thread. -1 means "not measured yet" — the UI shows a placeholder.
   */
  val sizeBytes: Long = -1L,
  /**
   * Newest modification time found inside the project (epoch millis, 0 when
   * unknown). Measured by the same walk that computes [sizeBytes].
   */
  val lastModified: Long = 0L,
  /**
   * Absolute path of a real logo/favicon/launcher icon found inside the
   * project (see [com.agentisco.workspace.filesystem.ProjectMetadataScanner]).
   * null when the project ships no decodable icon — the UI falls back to a
   * type icon or a name-derived initial.
   */
  val iconPath: String? = null,
  /** Project type inferred from real marker files in the project root. */
  val kind: ProjectKind = ProjectKind.UNKNOWN
)

/**
 * Project type inferred from files that actually exist in the project root —
 * never guessed from the project's name. [label] is what the UI shows.
 */
enum class ProjectKind(val label: String) {
  ANDROID("Android"),
  GRADLE("Gradle"),
  NODE("Node"),
  FLUTTER("Flutter"),
  RUST("Rust"),
  GO("Go"),
  PYTHON("Python"),
  MAVEN("Maven"),
  DOTNET(".NET"),
  RUBY("Ruby"),
  PHP("PHP"),
  CPP("C/C++"),
  GIT_REPO("Git repo"),
  UNKNOWN("")
}

/** Free/total space of the filesystem the workspace lives on. */
data class WorkspaceStorageInfo(
  val freeBytes: Long,
  val totalBytes: Long
) {
  val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
  /** 0f..1f — how full the volume is. */
  val usedFraction: Float
    get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class ProjectFile(
  val path: String,
  val name: String,
  val isDirectory: Boolean,
  val content: String = "",
  val language: String = "typescript",
  val children: List<ProjectFile> = emptyList(),
  val sizeBytes: Long = 0L,
  val lastModified: Long = 0L
)

enum class DiffLineType {
  ADDED,
  REMOVED,
  UNCHANGED
}

data class DiffLine(
  val type: DiffLineType,
  val oldLineNo: Int?,
  val newLineNo: Int?,
  val text: String
)

data class FileDiff(
  val filePath: String,
  val additionsCount: Int,
  val deletionsCount: Int,
  val lines: List<DiffLine>,
  val originalContent: String = "",
  val newContent: String = ""
)

enum class TerminalLineType {
  COMMAND,
  STDOUT,
  STDERR,
  SUCCESS,
  INFO
}

data class TerminalLine(
  val text: String,
  val type: TerminalLineType = TerminalLineType.STDOUT
)

data class TerminalSession(
  val id: String,
  val name: String,
  val currentDir: String,
  val lines: List<TerminalLine> = emptyList(),
  val isRunning: Boolean = false,
  /** The project/workspace these tabs belong to — terminals never mix projects. */
  val projectId: String = ""
)

data class GitCommit(
  val hash: String,
  val message: String,
  val author: String,
  val date: String,
  val filesChanged: List<String>
)

data class CommandPaletteItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val category: String,
  val destination: AppDestination? = null
)
