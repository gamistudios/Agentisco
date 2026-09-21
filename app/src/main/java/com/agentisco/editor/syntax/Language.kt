package com.agentisco.editor.syntax

import androidx.compose.ui.graphics.Color
import com.agentisco.ui.theme.*

enum class Language(
  val id: String,
  val displayName: String,
  val extensions: List<String>,
  val accentColor: Color,
  val commentPrefix: String = "//",
  val hasBlockComments: Boolean = true,
  val blockCommentStart: String = "/*",
  val blockCommentEnd: String = "*/"
) {
  KOTLIN(
    id = "kotlin",
    displayName = "Kotlin",
    extensions = listOf("kt", "kts"),
    accentColor = Color(0xFF7F52FF),
    commentPrefix = "//"
  ),
  JAVA(
    id = "java",
    displayName = "Java",
    extensions = listOf("java"),
    accentColor = Color(0xFFEA2D2E),
    commentPrefix = "//"
  ),
  PYTHON(
    id = "python",
    displayName = "Python",
    extensions = listOf("py", "pyw"),
    accentColor = Color(0xFF3572A5),
    commentPrefix = "#",
    hasBlockComments = false,
    blockCommentStart = "\"\"\"",
    blockCommentEnd = "\"\"\""
  ),
  JAVASCRIPT(
    id = "javascript",
    displayName = "JavaScript",
    extensions = listOf("js", "mjs", "cjs"),
    accentColor = Color(0xFFF7DF1E),
    commentPrefix = "//"
  ),
  TYPESCRIPT(
    id = "typescript",
    displayName = "TypeScript",
    extensions = listOf("ts", "mts", "cts"),
    accentColor = Color(0xFF3178C6),
    commentPrefix = "//"
  ),
  JSX_TSX(
    id = "jsx_tsx",
    displayName = "React (JSX/TSX)",
    extensions = listOf("jsx", "tsx"),
    accentColor = Color(0xFF61DAFB),
    commentPrefix = "//"
  ),
  HTML(
    id = "html",
    displayName = "HTML",
    extensions = listOf("html", "htm", "xhtml"),
    accentColor = Color(0xFFE34F26),
    commentPrefix = "<!--",
    hasBlockComments = true,
    blockCommentStart = "<!--",
    blockCommentEnd = "-->"
  ),
  CSS(
    id = "css",
    displayName = "CSS",
    extensions = listOf("css"),
    accentColor = Color(0xFF2965F1),
    commentPrefix = "/*",
    hasBlockComments = true,
    blockCommentStart = "/*",
    blockCommentEnd = "*/"
  ),
  SCSS(
    id = "scss",
    displayName = "SCSS/Sass",
    extensions = listOf("scss", "sass"),
    accentColor = Color(0xFFC6538C),
    commentPrefix = "//"
  ),
  JSON(
    id = "json",
    displayName = "JSON",
    extensions = listOf("json"),
    accentColor = Color(0xFFCBCB41),
    commentPrefix = "//",
    hasBlockComments = false
  ),
  YAML(
    id = "yaml",
    displayName = "YAML",
    extensions = listOf("yaml", "yml"),
    accentColor = Color(0xFFCB171E),
    commentPrefix = "#",
    hasBlockComments = false
  ),
  MARKDOWN(
    id = "markdown",
    displayName = "Markdown",
    extensions = listOf("md", "markdown"),
    accentColor = Color(0xFF083FA1),
    commentPrefix = "<!--",
    hasBlockComments = true,
    blockCommentStart = "<!--",
    blockCommentEnd = "-->"
  ),
  PHP(
    id = "php",
    displayName = "PHP",
    extensions = listOf("php", "phtml"),
    accentColor = Color(0xFF4F5D95),
    commentPrefix = "//"
  ),
  DART(
    id = "dart",
    displayName = "Dart",
    extensions = listOf("dart"),
    accentColor = Color(0xFF00B4AB),
    commentPrefix = "//"
  ),
  C(
    id = "c",
    displayName = "C",
    extensions = listOf("c", "h"),
    accentColor = Color(0xFF555555),
    commentPrefix = "//"
  ),
  CPP(
    id = "cpp",
    displayName = "C++",
    extensions = listOf("cpp", "cc", "cxx", "hpp", "hh"),
    accentColor = Color(0xFFF34B7D),
    commentPrefix = "//"
  ),
  CSHARP(
    id = "csharp",
    displayName = "C#",
    extensions = listOf("cs"),
    accentColor = Color(0xFF178600),
    commentPrefix = "//"
  ),
  GO(
    id = "go",
    displayName = "Go",
    extensions = listOf("go"),
    accentColor = Color(0xFF00ADD8),
    commentPrefix = "//"
  ),
  RUST(
    id = "rust",
    displayName = "Rust",
    extensions = listOf("rs"),
    accentColor = Color(0xFFDEA584),
    commentPrefix = "//"
  ),
  SQL(
    id = "sql",
    displayName = "SQL",
    extensions = listOf("sql"),
    accentColor = Color(0xFFE38C00),
    commentPrefix = "--",
    hasBlockComments = true,
    blockCommentStart = "/*",
    blockCommentEnd = "*/"
  ),
  SHELL(
    id = "shell",
    displayName = "Shell / Bash",
    extensions = listOf("sh", "bash", "zsh", "env"),
    accentColor = Color(0xFF89E051),
    commentPrefix = "#",
    hasBlockComments = false
  ),
  XML(
    id = "xml",
    displayName = "XML",
    extensions = listOf("xml", "svg", "plist", "iml"),
    accentColor = Color(0xFF0060AC),
    commentPrefix = "<!--",
    hasBlockComments = true,
    blockCommentStart = "<!--",
    blockCommentEnd = "-->"
  ),
  VUE(
    id = "vue",
    displayName = "Vue",
    extensions = listOf("vue"),
    accentColor = Color(0xFF41B883),
    commentPrefix = "//"
  ),
  DOCKERFILE(
    id = "dockerfile",
    displayName = "Dockerfile",
    extensions = listOf("dockerfile"),
    accentColor = Color(0xFF384D54),
    commentPrefix = "#",
    hasBlockComments = false
  ),
  GIT_CONFIG(
    id = "git",
    displayName = "Git Config",
    extensions = listOf("gitignore", "gitmodules", "gitattributes"),
    accentColor = Color(0xFFF05032),
    commentPrefix = "#",
    hasBlockComments = false
  ),
  PLAIN_TEXT(
    id = "plaintext",
    displayName = "Plain Text",
    extensions = listOf("txt", "log", "conf"),
    accentColor = Color(0xFF94A3B8),
    commentPrefix = "#",
    hasBlockComments = false
  );

  companion object {
    fun fromFileName(fileName: String): Language {
      val lower = fileName.lowercase().trim()

      if (lower == "dockerfile" || lower.startsWith("dockerfile.")) return DOCKERFILE
      if (lower == ".gitignore" || lower == ".gitmodules" || lower == ".gitattributes") return GIT_CONFIG
      if (lower.endsWith(".gradle.kts")) return KOTLIN
      if (lower.endsWith(".gradle")) return KOTLIN
      if (lower == ".env" || lower.startsWith(".env.")) return SHELL

      val extension = lower.substringAfterLast('.', "")
      if (extension.isBlank()) return PLAIN_TEXT

      for (lang in entries) {
        if (lang.extensions.contains(extension)) {
          return lang
        }
      }

      return when (extension) {
        "mjs", "cjs" -> JAVASCRIPT
        "mts", "cts" -> TYPESCRIPT
        "sass" -> SCSS
        "pyw" -> PYTHON
        "cc", "cxx", "hpp", "hh" -> CPP
        "bash", "zsh" -> SHELL
        "svg", "plist" -> XML
        "htm", "xhtml" -> HTML
        "markdown" -> MARKDOWN
        else -> PLAIN_TEXT
      }
    }

    fun fromId(id: String): Language {
      return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PLAIN_TEXT
    }
  }
}
