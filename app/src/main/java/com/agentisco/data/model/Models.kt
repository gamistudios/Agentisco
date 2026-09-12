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
  val isImported: Boolean = false
)

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
