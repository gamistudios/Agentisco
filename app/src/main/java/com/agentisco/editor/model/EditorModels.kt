package com.agentisco.editor.model

import com.agentisco.data.model.ProjectFile
import com.agentisco.editor.syntax.Language
import com.agentisco.editor.syntax.SyntaxTheme

data class EditorTab(
  val id: String,
  val file: ProjectFile,
  val content: String,
  val savedContent: String,
  val manualLanguage: Language? = null,
  val cursorLine: Int = 1,
  val cursorCol: Int = 1,
  val selectionStart: Int = 0,
  val selectionEnd: Int = 0,
  val scrollLineIndex: Int = 0,
  val undoStack: List<String> = emptyList(),
  val redoStack: List<String> = emptyList()
) {
  val isDirty: Boolean get() = content != savedContent
  val effectiveLanguage: Language get() = manualLanguage ?: Language.fromFileName(file.name)

  fun withContent(newContent: String): EditorTab {
    if (newContent == content) return this
    val newUndo = if (undoStack.size >= 200) undoStack.drop(1) + content else undoStack + content
    return copy(
      content = newContent,
      undoStack = newUndo,
      redoStack = emptyList() // clear redo on new edit
    )
  }

  fun undo(): EditorTab? {
    if (undoStack.isEmpty()) return null
    val prev = undoStack.last()
    val newUndo = undoStack.dropLast(1)
    val newRedo = redoStack + content
    return copy(content = prev, undoStack = newUndo, redoStack = newRedo)
  }

  fun redo(): EditorTab? {
    if (redoStack.isEmpty()) return null
    val next = redoStack.last()
    val newRedo = redoStack.dropLast(1)
    val newUndo = undoStack + content
    return copy(content = next, undoStack = newUndo, redoStack = newRedo)
  }
}

data class EditorSettings(
  val fontSize: Int = 12,              // in sp
  val lineHeightMultiplier: Float = 1.35f,
  val tabSize: Int = 2,
  val useSpaces: Boolean = true,
  val wordWrap: Boolean = false,
  val showLineNumbers: Boolean = true,
  val highlightActiveLine: Boolean = true,
  val bracketMatching: Boolean = true,
  val codeFolding: Boolean = true,
  val syntaxThemeName: String = "Agentisco Dark",
  val autoSave: Boolean = false,
  val formatOnSave: Boolean = false,
  val showMinimap: Boolean = false,
  val touchShortcutsExpanded: Boolean = true
) {
  val theme: SyntaxTheme get() = SyntaxTheme.getByName(syntaxThemeName)
}

data class FindReplaceState(
  val isOpen: Boolean = false,
  val findQuery: String = "",
  val replaceQuery: String = "",
  val matchCase: Boolean = false,
  val wholeWord: Boolean = false,
  val currentMatchIndex: Int = 0,
  val matches: List<IntRange> = emptyList()
) {
  val totalMatches: Int get() = matches.size
  val activeMatch: IntRange? get() = if (matches.isNotEmpty() && currentMatchIndex in matches.indices) matches[currentMatchIndex] else null
}

data class GoToLineState(
  val isOpen: Boolean = false,
  val targetLine: String = "",
  val totalLines: Int = 1
)

data class CodeDiagnostic(
  val line: Int,
  val message: String,
  val isError: Boolean = true
)
