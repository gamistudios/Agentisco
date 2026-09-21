package com.agentisco.editor.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.util.concurrent.ConcurrentHashMap

object SyntaxHighlighter {

  // Cache: key = (language.id + "|" + theme.name + "|" + lineText.hashCode()) -> AnnotatedString
  // Keeps the editor 60fps smooth even when scrolling large files.
  private val lineCache = ConcurrentHashMap<String, AnnotatedString>(2048)

  /**
   * Highlights an entire document or multi-line block of code.
   * Tracks line boundaries and multi-line comments (/* ... */) and docstrings.
   */
  fun highlightCode(
    code: String,
    language: Language,
    theme: SyntaxTheme
  ): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    if (language == Language.PLAIN_TEXT) return AnnotatedString(code)

    return buildAnnotatedString {
      append(code)

      var lineStart = 0
      var inBlockComment = false
      var inPyDocstring = false

      while (lineStart < code.length) {
        var lineEnd = code.indexOf('\n', lineStart)
        val hasNewline = lineEnd != -1
        if (!hasNewline) lineEnd = code.length

        val effectiveEnd = if (lineEnd > lineStart && code[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val line = code.substring(lineStart, effectiveEnd)

        // Handle multi-line block comments (e.g. /* ... */ or <!-- ... -->)
        if (inBlockComment) {
          val closeToken = if (language == Language.HTML || language == Language.XML) "-->" else "*/"
          val closeIdx = line.indexOf(closeToken)
          if (closeIdx != -1) {
            val endOffset = lineStart + closeIdx + closeToken.length
            addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), lineStart, endOffset)
            inBlockComment = false
            // Highlight the remainder of the line after comment ends
            val remainderStart = closeIdx + closeToken.length
            if (remainderStart < line.length) {
              val remainder = line.substring(remainderStart)
              highlightLineSpans(remainder, lineStart + remainderStart, language, theme, this)
            }
          } else {
            addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), lineStart, lineStart + line.length)
          }
        } else if (inPyDocstring) {
          val closeToken = if (line.contains("\"\"\"")) "\"\"\"" else "'''"
          val closeIdx = line.indexOf(closeToken)
          if (closeIdx != -1) {
            val endOffset = lineStart + closeIdx + closeToken.length
            addStyle(SpanStyle(color = theme.string, fontStyle = FontStyle.Italic), lineStart, endOffset)
            inPyDocstring = false
            val remainderStart = closeIdx + closeToken.length
            if (remainderStart < line.length) {
              val remainder = line.substring(remainderStart)
              highlightLineSpans(remainder, lineStart + remainderStart, language, theme, this)
            }
          } else {
            addStyle(SpanStyle(color = theme.string, fontStyle = FontStyle.Italic), lineStart, lineStart + line.length)
          }
        } else {
          // Check if line enters multi-line block comment
          val openCommentToken = when (language) {
            Language.HTML, Language.XML -> "<!--"
            else -> if (language.hasBlockComments) "/*" else null
          }
          val openCommentIdx = openCommentToken?.let { line.indexOf(it) } ?: -1

          val openPyDocstringIdx = if (language == Language.PYTHON) {
            val d3 = line.indexOf("\"\"\"")
            if (d3 != -1) d3 else line.indexOf("'''")
          } else -1

          if (openCommentIdx != -1 && openCommentToken != null) {
            val closeToken = if (language == Language.HTML || language == Language.XML) "-->" else "*/"
            val closeOnSameLine = line.indexOf(closeToken, openCommentIdx + openCommentToken.length)
            if (closeOnSameLine == -1) {
              if (openCommentIdx > 0) {
                highlightLineSpans(line.substring(0, openCommentIdx), lineStart, language, theme, this)
              }
              addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), lineStart + openCommentIdx, lineStart + line.length)
              inBlockComment = true
            } else {
              highlightLineSpans(line, lineStart, language, theme, this)
            }
          } else if (openPyDocstringIdx != -1) {
            val quoteStr = if (line.contains("\"\"\"")) "\"\"\"" else "'''"
            val secondIdx = line.indexOf(quoteStr, openPyDocstringIdx + 3)
            if (secondIdx == -1) {
              if (openPyDocstringIdx > 0) {
                highlightLineSpans(line.substring(0, openPyDocstringIdx), lineStart, language, theme, this)
              }
              addStyle(SpanStyle(color = theme.string, fontStyle = FontStyle.Italic), lineStart + openPyDocstringIdx, lineStart + line.length)
              inPyDocstring = true
            } else {
              highlightLineSpans(line, lineStart, language, theme, this)
            }
          } else {
            highlightLineSpans(line, lineStart, language, theme, this)
          }
        }

        lineStart = if (hasNewline) lineEnd + 1 else lineEnd
      }
    }
  }

  /**
   * Highlights a single line of text with memory caching.
   */
  fun highlightLine(
    line: String,
    language: Language,
    theme: SyntaxTheme
  ): AnnotatedString {
    if (line.isEmpty()) return AnnotatedString("")

    val cacheKey = "${language.id}|${theme.name}|${line.hashCode()}|$line"
    lineCache[cacheKey]?.let { return it }

    val result = buildAnnotatedString {
      append(line)
      highlightLineSpans(line, 0, language, theme, this)
    }

    if (lineCache.size > 4000) {
      lineCache.clear()
    }
    lineCache[cacheKey] = result
    return result
  }

  fun clearCache() {
    lineCache.clear()
  }

  private fun highlightLineSpans(
    line: String,
    baseOffset: Int,
    language: Language,
    theme: SyntaxTheme,
    builder: AnnotatedString.Builder
  ) {
    val trimmed = line.trimStart()

    // 1. Single line full comment check
    if (isFullLineComment(trimmed, language)) {
      builder.addStyle(
        SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic),
        baseOffset,
        baseOffset + line.length
      )
      return
    }

    when (language) {
      Language.JSON -> highlightJson(line, baseOffset, theme, builder)
      Language.MARKDOWN -> highlightMarkdown(line, baseOffset, theme, builder)
      Language.HTML, Language.XML -> highlightHtmlXml(line, baseOffset, theme, builder)
      Language.CSS, Language.SCSS -> highlightCss(line, baseOffset, theme, builder)
      Language.YAML -> highlightYaml(line, baseOffset, theme, builder)
      Language.PYTHON -> highlightPython(line, baseOffset, theme, builder)
      Language.SQL -> highlightSql(line, baseOffset, theme, builder)
      Language.SHELL, Language.DOCKERFILE, Language.GIT_CONFIG -> highlightShellOrConfig(line, baseOffset, language, theme, builder)
      Language.PLAIN_TEXT -> {
        builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)
      }
      else -> highlightCStyleLanguage(line, baseOffset, language, theme, builder)
    }
  }

  private fun isFullLineComment(trimmed: String, language: Language): Boolean {
    if (trimmed.startsWith(language.commentPrefix)) return true
    if (language.hasBlockComments && trimmed.startsWith(language.blockCommentStart) && trimmed.endsWith(language.blockCommentEnd)) return true
    if (language == Language.PYTHON && trimmed.startsWith("#")) return true
    if (language == Language.SQL && trimmed.startsWith("--")) return true
    return false
  }

  // --- JSON Highlighting ---
  private fun highlightJson(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Match JSON keys: "key":
    val keyRegex = Regex(""""([^"\\\\]*)"\s*:""")
    for (match in keyRegex.findAll(line)) {
      val keyRange = match.groups[1]?.range ?: continue
      builder.addStyle(
        SpanStyle(color = theme.jsonKey, fontWeight = FontWeight.SemiBold),
        baseOffset + keyRange.first,
        baseOffset + keyRange.last + 1
      )
      val colonIdx = match.value.lastIndexOf(':')
      if (colonIdx >= 0) {
        val absColon = baseOffset + match.range.first + colonIdx
        builder.addStyle(SpanStyle(color = theme.operator), absColon, absColon + 1)
      }
    }

    // Match JSON string values: : "value"
    val valStringRegex = Regex(""":\s*"([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"""")
    for (match in valStringRegex.findAll(line)) {
      val strGroup = match.groups[1] ?: continue
      builder.addStyle(SpanStyle(color = theme.string), baseOffset + strGroup.range.first - 1, baseOffset + strGroup.range.last + 2)
    }

    // Numbers
    val numberRegex = Regex("""\b-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?\b""")
    for (match in numberRegex.findAll(line)) {
      if (!isInsideQuotes(line, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.number), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }

    // Booleans and null
    val literalRegex = Regex("""\b(true|false|null)\b""")
    for (match in literalRegex.findAll(line)) {
      if (!isInsideQuotes(line, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.constant, fontWeight = FontWeight.SemiBold), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }
  }

  // --- Markdown Highlighting ---
  private fun highlightMarkdown(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    val trimmed = line.trimStart()
    val indent = line.length - trimmed.length

    // Headers
    if (trimmed.startsWith("#")) {
      val headerLevel = trimmed.takeWhile { it == '#' }.length
      if (headerLevel in 1..6 && trimmed.getOrNull(headerLevel) == ' ') {
        builder.addStyle(
          SpanStyle(color = theme.markdownHeading, fontWeight = FontWeight.Bold),
          baseOffset + indent,
          baseOffset + line.length
        )
        return
      }
    }

    // Code blocks
    if (trimmed.startsWith("```")) {
      builder.addStyle(SpanStyle(color = theme.markdownCode, fontWeight = FontWeight.Bold), baseOffset + indent, baseOffset + line.length)
      return
    }

    // Inline code `code`
    val inlineCode = Regex("""`([^`]+)`""")
    for (match in inlineCode.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.markdownCode, background = theme.activeLineBg), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Bold **text**
    val boldRegex = Regex("""\*\*([^*]+)\*\*""")
    for (match in boldRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.markdownBold, fontWeight = FontWeight.Bold), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Links [text](url)
    val linkRegex = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
    for (match in linkRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.function, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // List bullets
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
      builder.addStyle(SpanStyle(color = theme.keyword, fontWeight = FontWeight.Bold), baseOffset + indent, baseOffset + indent + 2)
    } else if (Regex("""^\d+\.\s""").containsMatchIn(trimmed)) {
      val match = Regex("""^\d+\.\s""").find(trimmed)!!
      builder.addStyle(SpanStyle(color = theme.number, fontWeight = FontWeight.Bold), baseOffset + indent, baseOffset + indent + match.value.length)
    }

    // Blockquote
    if (trimmed.startsWith(">")) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + indent, baseOffset + line.length)
    }
  }

  // --- HTML / XML Highlighting ---
  private fun highlightHtmlXml(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments
    val commentRegex = Regex("""<!--[\s\S]*?-->|<!--.*""")
    for (match in commentRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Tags: </?([a-zA-Z0-9_-]+)
    val tagRegex = Regex("""</?([a-zA-Z0-9_-]+)""")
    for (match in tagRegex.findAll(line)) {
      if (!isInComment(line, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.tag, fontWeight = FontWeight.SemiBold), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }

    // Attributes: ([a-zA-Z0-9_:-]+)=
    val attrRegex = Regex("""\b([a-zA-Z0-9_:-]+)\s*=""")
    for (match in attrRegex.findAll(line)) {
      val group = match.groups[1] ?: continue
      if (!isInComment(line, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.attribute), baseOffset + group.range.first, baseOffset + group.range.last + 1)
      }
    }

    // Strings inside tags: "..." or '...'
    highlightStrings(line, baseOffset, theme, builder)
  }

  // --- CSS / SCSS Highlighting ---
  private fun highlightCss(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments
    val commentRegex = Regex("""/\*[\s\S]*?\*/|//.*""")
    for (match in commentRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Selectors: .class, #id, element before {
    val selectorRegex = Regex("""([.#]?[a-zA-Z0-9_-]+)(?=[^{}]*\{)""")
    for (match in selectorRegex.findAll(line)) {
      val text = match.value
      val color = when {
        text.startsWith(".") -> theme.classType
        text.startsWith("#") -> theme.function
        else -> theme.tag
      }
      builder.addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Property: ([a-zA-Z0-9_-]+)\s*:
    val propRegex = Regex("""([a-zA-Z0-9_-]+)\s*:""")
    for (match in propRegex.findAll(line)) {
      val group = match.groups[1] ?: continue
      builder.addStyle(SpanStyle(color = theme.cssProperty), baseOffset + group.range.first, baseOffset + group.range.last + 1)
    }

    // Units: 12px, 1.5rem, 100%
    val unitRegex = Regex("""\b\d+(?:\.\d+)?(px|rem|em|%|vh|vw|pt|s|ms)\b""")
    for (match in unitRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.number), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Colors: #abc, #abcdef
    val hexColorRegex = Regex("""#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b""")
    for (match in hexColorRegex.findAll(line)) {
      builder.addStyle(SpanStyle(color = theme.constant, fontWeight = FontWeight.SemiBold), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    highlightStrings(line, baseOffset, theme, builder)
  }

  // --- YAML Highlighting ---
  private fun highlightYaml(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments
    val hashIdx = line.indexOf('#')
    if (hashIdx >= 0 && !isInsideQuotes(line, hashIdx)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + hashIdx, baseOffset + line.length)
    }

    // Keys: ^(\s*)([a-zA-Z0-9_.-]+)\s*:
    val keyRegex = Regex("""^(\s*)([a-zA-Z0-9_.-]+)\s*:""")
    for (match in keyRegex.findAll(line)) {
      val group = match.groups[2] ?: continue
      builder.addStyle(SpanStyle(color = theme.jsonKey, fontWeight = FontWeight.SemiBold), baseOffset + group.range.first, baseOffset + group.range.last + 1)
    }

    // Booleans & numbers
    val literalRegex = Regex("""\b(true|false|yes|no|on|off|null|~)\b""", RegexOption.IGNORE_CASE)
    for (match in literalRegex.findAll(line)) {
      if (!isInsideQuotes(line, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.constant, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }

    highlightStrings(line, baseOffset, theme, builder)
  }

  // --- Python Highlighting ---
  private fun highlightPython(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments
    val hashIdx = line.indexOf('#')
    val commentStart = if (hashIdx >= 0 && !isInsideQuotes(line, hashIdx)) hashIdx else -1
    if (commentStart >= 0) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + commentStart, baseOffset + line.length)
    }

    val activeCode = if (commentStart >= 0) line.substring(0, commentStart) else line

    // Decorators: @decorator
    val decoratorRegex = Regex("""@([a-zA-Z0-9_.]+)""")
    for (match in decoratorRegex.findAll(activeCode)) {
      builder.addStyle(SpanStyle(color = theme.decorator, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // Keywords
    val pythonKeywords = setOf(
      "def", "class", "import", "from", "as", "return", "if", "elif", "else", "while",
      "for", "in", "try", "except", "finally", "with", "yield", "lambda", "pass",
      "break", "continue", "raise", "async", "await", "global", "nonlocal", "assert",
      "del", "is", "not", "and", "or", "match", "case"
    )
    highlightWordList(activeCode, baseOffset, pythonKeywords, theme.keyword, builder, isKeyword = true)

    // Constants / Builtins
    val pythonBuiltins = setOf(
      "True", "False", "None", "self", "cls", "print", "len", "range", "enumerate",
      "zip", "map", "filter", "str", "int", "float", "bool", "list", "dict", "set",
      "tuple", "super", "open", "type", "isinstance", "issubclass"
    )
    highlightWordList(activeCode, baseOffset, pythonBuiltins, theme.constant, builder)

    // Function declarations: def name(...)
    val defRegex = Regex("""\bdef\s+([a-zA-Z0-9_]+)""")
    for (match in defRegex.findAll(activeCode)) {
      val fn = match.groups[1] ?: continue
      builder.addStyle(SpanStyle(color = theme.function, fontWeight = FontWeight.Bold), baseOffset + fn.range.first, baseOffset + fn.range.last + 1)
    }

    // Class declarations: class Name(...)
    val classRegex = Regex("""\bclass\s+([a-zA-Z0-9_]+)""")
    for (match in classRegex.findAll(activeCode)) {
      val cls = match.groups[1] ?: continue
      builder.addStyle(SpanStyle(color = theme.classType, fontWeight = FontWeight.Bold), baseOffset + cls.range.first, baseOffset + cls.range.last + 1)
    }

    // General function calls: func(...)
    highlightFunctionCalls(activeCode, baseOffset, theme, builder)

    // Numbers
    highlightNumbers(activeCode, baseOffset, theme, builder)

    // Strings
    highlightStrings(activeCode, baseOffset, theme, builder)
  }

  // --- SQL Highlighting ---
  private fun highlightSql(line: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comment: --
    val dashIdx = line.indexOf("--")
    if (dashIdx >= 0 && !isInsideQuotes(line, dashIdx)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + dashIdx, baseOffset + line.length)
    }

    val sqlKeywords = setOf(
      "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
      "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "VIEW", "JOIN", "LEFT", "RIGHT",
      "INNER", "OUTER", "FULL", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET",
      "AS", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "EXISTS", "BETWEEN", "CASE",
      "WHEN", "THEN", "ELSE", "END", "UNION", "ALL", "DISTINCT", "PRIMARY", "KEY", "FOREIGN",
      "REFERENCES", "DEFAULT", "CASCADE", "ASC", "DESC"
    )
    for (kw in sqlKeywords) {
      val regex = Regex("""\b$kw\b""", RegexOption.IGNORE_CASE)
      for (match in regex.findAll(line)) {
        if (!isInsideQuotes(line, match.range.first)) {
          builder.addStyle(SpanStyle(color = theme.keyword, fontWeight = FontWeight.Bold), baseOffset + match.range.first, baseOffset + match.range.last + 1)
        }
      }
    }

    val sqlTypes = setOf("INT", "INTEGER", "VARCHAR", "TEXT", "BOOLEAN", "FLOAT", "DOUBLE", "DATETIME", "TIMESTAMP", "BLOB")
    for (t in sqlTypes) {
      val regex = Regex("""\b$t\b""", RegexOption.IGNORE_CASE)
      for (match in regex.findAll(line)) {
        if (!isInsideQuotes(line, match.range.first)) {
          builder.addStyle(SpanStyle(color = theme.classType, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
        }
      }
    }

    highlightNumbers(line, baseOffset, theme, builder)
    highlightStrings(line, baseOffset, theme, builder)
  }

  // --- Shell / Dockerfile / Git Config Highlighting ---
  private fun highlightShellOrConfig(
    line: String,
    baseOffset: Int,
    language: Language,
    theme: SyntaxTheme,
    builder: AnnotatedString.Builder
  ) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments: # ...
    val hashIdx = line.indexOf('#')
    if (hashIdx >= 0 && !isInsideQuotes(line, hashIdx)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + hashIdx, baseOffset + line.length)
    }

    val codePortion = if (hashIdx >= 0) line.substring(0, hashIdx) else line

    if (language == Language.DOCKERFILE) {
      val dockerDirectives = setOf(
        "FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
        "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL"
      )
      val trimmed = codePortion.trimStart()
      val firstWord = trimmed.substringBefore(' ')
      if (dockerDirectives.contains(firstWord.uppercase())) {
        val start = line.indexOf(firstWord)
        builder.addStyle(SpanStyle(color = theme.keyword, fontWeight = FontWeight.Bold), baseOffset + start, baseOffset + start + firstWord.length)
      }
    } else if (language == Language.SHELL) {
      val shellKeywords = setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "in", "case", "esac", "function", "return", "exit")
      highlightWordList(codePortion, baseOffset, shellKeywords, theme.keyword, builder, isKeyword = true)

      // Variables: $VAR, ${VAR}
      val varRegex = Regex("""\$[a-zA-Z0-9_]+|\$\{[a-zA-Z0-9_]+\}""")
      for (match in varRegex.findAll(codePortion)) {
        builder.addStyle(SpanStyle(color = theme.variable, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }

    highlightStrings(codePortion, baseOffset, theme, builder)
  }

  // --- General C-Style (Kotlin, Java, JS, TS, JSX, TSX, Dart, C, C++, C#, Go, Rust, PHP, etc.) ---
  private fun highlightCStyleLanguage(
    line: String,
    baseOffset: Int,
    language: Language,
    theme: SyntaxTheme,
    builder: AnnotatedString.Builder
  ) {
    builder.addStyle(SpanStyle(color = theme.text), baseOffset, baseOffset + line.length)

    // Comments: //
    val commentIdx = line.indexOf("//")
    val codeLimit = if (commentIdx >= 0 && !isInsideQuotes(line, commentIdx)) {
      builder.addStyle(SpanStyle(color = theme.comment, fontStyle = FontStyle.Italic), baseOffset + commentIdx, baseOffset + line.length)
      commentIdx
    } else line.length

    val codePortion = line.substring(0, codeLimit)

    // Decorators / Annotations: @Composable, @Override, etc.
    val annotationRegex = Regex("""@\w+""")
    for (match in annotationRegex.findAll(codePortion)) {
      builder.addStyle(SpanStyle(color = theme.decorator, fontWeight = FontWeight.Medium), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }

    // JSX / TSX Tag highlighting (e.g. <Component, </Component>, <div, </div>, />)
    if (language == Language.JSX_TSX || language == Language.JAVASCRIPT || language == Language.TYPESCRIPT) {
      val jsxTagRegex = Regex("""</?([a-zA-Z0-9_.-]+)""")
      for (match in jsxTagRegex.findAll(codePortion)) {
        val tagGroup = match.groups[1] ?: continue
        val tagName = tagGroup.value
        val isComponent = tagName.firstOrNull()?.isUpperCase() == true
        val tagColor = if (isComponent) theme.classType else theme.tag
        builder.addStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.SemiBold), baseOffset + tagGroup.range.first, baseOffset + tagGroup.range.last + 1)
      }

      val jsxAttrRegex = Regex("""\b([a-zA-Z0-9_:-]+)\s*=""")
      for (match in jsxAttrRegex.findAll(codePortion)) {
        val attrGroup = match.groups[1] ?: continue
        builder.addStyle(SpanStyle(color = theme.attribute), baseOffset + attrGroup.range.first, baseOffset + attrGroup.range.last + 1)
      }
    }

    // Keywords
    val keywords = getKeywordsForLanguage(language)
    highlightWordList(codePortion, baseOffset, keywords, theme.keyword, builder, isKeyword = true)

    // Types
    val types = getTypesForLanguage(language)
    highlightWordList(codePortion, baseOffset, types, theme.classType, builder)

    // Built-in constants
    val constants = setOf("true", "false", "null", "nil", "undefined", "this", "super", "self")
    highlightWordList(codePortion, baseOffset, constants, theme.constant, builder)

    // Functions: name(...)
    highlightFunctionCalls(codePortion, baseOffset, theme, builder)

    // Operators
    val opRegex = Regex("""(=>|->|===|!==|==|!=|<=|>=|\+\+|--|&&|\|\||[+\-*/%<>=!?:])""")
    for (match in opRegex.findAll(codePortion)) {
      if (!isInsideQuotes(codePortion, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.operator), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }

    // Numbers
    highlightNumbers(codePortion, baseOffset, theme, builder)

    // Strings
    highlightStrings(codePortion, baseOffset, theme, builder)
  }

  private fun highlightWordList(
    text: String,
    baseOffset: Int,
    words: Set<String>,
    color: androidx.compose.ui.graphics.Color,
    builder: AnnotatedString.Builder,
    isKeyword: Boolean = false
  ) {
    val wordRegex = Regex("""\b([a-zA-Z_]\w*)\b""")
    for (match in wordRegex.findAll(text)) {
      val word = match.value
      if (words.contains(word)) {
        if (!isInsideQuotes(text, match.range.first)) {
          builder.addStyle(
            SpanStyle(color = color, fontWeight = if (isKeyword) FontWeight.SemiBold else FontWeight.Normal),
            baseOffset + match.range.first,
            baseOffset + match.range.last + 1
          )
        }
      }
    }
  }

  private fun highlightFunctionCalls(text: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    val fnCallRegex = Regex("""\b([a-zA-Z_]\w*)\s*(?=\()""")
    for (match in fnCallRegex.findAll(text)) {
      val fn = match.groups[1] ?: continue
      if (!isInsideQuotes(text, fn.range.first)) {
        builder.addStyle(SpanStyle(color = theme.function), baseOffset + fn.range.first, baseOffset + fn.range.last + 1)
      }
    }
  }

  private fun highlightNumbers(text: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    val numRegex = Regex("""\b(?:0x[a-fA-F0-9]+|0b[01]+|\d+(?:\.\d+)?(?:f|L|u|d)?)\b""")
    for (match in numRegex.findAll(text)) {
      if (!isInsideQuotes(text, match.range.first)) {
        builder.addStyle(SpanStyle(color = theme.number), baseOffset + match.range.first, baseOffset + match.range.last + 1)
      }
    }
  }

  private fun highlightStrings(text: String, baseOffset: Int, theme: SyntaxTheme, builder: AnnotatedString.Builder) {
    val stringRegex = Regex(""""([^"\\\\]*(?:\\\\.[^"\\\\]*)*)"|'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'|`([^`\\\\]*(?:\\\\.[^`\\\\]*)*)`""")
    for (match in stringRegex.findAll(text)) {
      builder.addStyle(SpanStyle(color = theme.string), baseOffset + match.range.first, baseOffset + match.range.last + 1)
    }
  }

  private fun isInsideQuotes(text: String, index: Int): Boolean {
    var inSingle = false
    var inDouble = false
    var inBacktick = false
    var i = 0
    while (i < index && i < text.length) {
      val c = text[i]
      if (c == '\\') {
        i += 2
        continue
      }
      if (c == '\'' && !inDouble && !inBacktick) inSingle = !inSingle
      else if (c == '"' && !inSingle && !inBacktick) inDouble = !inDouble
      else if (c == '`' && !inSingle && !inDouble) inBacktick = !inBacktick
      i++
    }
    return inSingle || inDouble || inBacktick
  }

  private fun isInComment(text: String, index: Int): Boolean {
    val commentIdx = text.indexOf("<!--")
    if (commentIdx in 0..index) {
      val closeIdx = text.indexOf("-->", commentIdx)
      if (closeIdx == -1 || closeIdx >= index) return true
    }
    return false
  }

  private fun getKeywordsForLanguage(language: Language): Set<String> {
    return when (language) {
      Language.KOTLIN -> setOf(
        "package", "import", "class", "interface", "object", "enum", "fun", "val", "var",
        "override", "private", "public", "protected", "internal", "data", "sealed", "open",
        "abstract", "suspend", "inline", "tailrec", "operator", "infix", "const", "lateinit",
        "lazy", "companion", "if", "else", "when", "for", "while", "do", "return", "break",
        "continue", "throw", "try", "catch", "finally", "is", "as", "in", "by", "get", "set"
      )
      Language.JAVA -> setOf(
        "package", "import", "class", "interface", "enum", "record", "public", "private",
        "protected", "static", "final", "abstract", "synchronized", "volatile", "transient",
        "native", "strictfp", "extends", "implements", "if", "else", "switch", "case",
        "default", "while", "do", "for", "break", "continue", "return", "throw", "throws",
        "try", "catch", "finally", "new", "instanceof", "assert", "void"
      )
      Language.JAVASCRIPT, Language.TYPESCRIPT, Language.JSX_TSX, Language.VUE -> setOf(
        "import", "export", "from", "as", "default", "class", "function", "const", "let", "var",
        "if", "else", "switch", "case", "while", "for", "do", "return", "break", "continue",
        "throw", "try", "catch", "finally", "new", "delete", "typeof", "instanceof", "void",
        "async", "await", "yield", "interface", "type", "enum", "implements", "extends",
        "public", "private", "protected", "readonly", "static", "abstract", "namespace", "declare"
      )
      Language.DART -> setOf(
        "import", "export", "part", "library", "class", "mixin", "extension", "enum", "typedef",
        "void", "var", "final", "const", "late", "static", "abstract", "covariant", "async",
        "await", "yield", "if", "else", "switch", "case", "default", "for", "while", "do",
        "return", "break", "continue", "throw", "try", "catch", "finally", "new", "is", "as", "in"
      )
      Language.GO -> setOf(
        "package", "import", "func", "type", "struct", "interface", "var", "const", "chan",
        "map", "go", "select", "defer", "if", "else", "switch", "case", "default", "for",
        "range", "return", "break", "continue", "fallthrough", "goto"
      )
      Language.RUST -> setOf(
        "fn", "struct", "enum", "trait", "impl", "let", "mut", "const", "static", "type",
        "mod", "use", "pub", "crate", "super", "self", "Self", "if", "else", "match", "while",
        "loop", "for", "in", "return", "break", "continue", "unsafe", "async", "await", "where", "move"
      )
      Language.CSHARP -> setOf(
        "using", "namespace", "class", "struct", "interface", "enum", "record", "public",
        "private", "protected", "internal", "static", "readonly", "volatile", "const", "async",
        "await", "var", "if", "else", "switch", "case", "default", "while", "do", "for", "foreach",
        "in", "return", "break", "continue", "throw", "try", "catch", "finally", "new", "is", "as"
      )
      Language.CPP, Language.C -> setOf(
        "include", "define", "ifdef", "ifndef", "endif", "class", "struct", "union", "enum",
        "namespace", "template", "typename", "public", "private", "protected", "virtual",
        "override", "final", "constexpr", "static", "const", "auto", "void", "if", "else",
        "switch", "case", "default", "while", "do", "for", "return", "break", "continue",
        "new", "delete", "try", "catch", "throw", "sizeof"
      )
      Language.PHP -> setOf(
        "<?php", "?>", "namespace", "use", "class", "interface", "trait", "function", "public",
        "private", "protected", "static", "final", "abstract", "const", "var", "if", "else",
        "elseif", "switch", "case", "default", "while", "do", "for", "foreach", "as", "return",
        "break", "continue", "throw", "try", "catch", "finally", "new", "echo", "print", "include", "require"
      )
      else -> setOf("if", "else", "for", "while", "return", "import", "class", "function", "def", "var", "val")
    }
  }

  private fun getTypesForLanguage(language: Language): Set<String> {
    return when (language) {
      Language.KOTLIN -> setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "Any", "Unit", "Nothing", "List", "MutableList", "Map", "MutableMap", "Set",
        "MutableSet", "Array", "StateFlow", "Flow", "Modifier", "Color", "CoroutineScope"
      )
      Language.JAVA -> setOf(
        "String", "int", "long", "float", "double", "boolean", "char", "byte", "short",
        "Object", "Void", "Integer", "Long", "Float", "Double", "Boolean", "List", "Map",
        "Set", "ArrayList", "HashMap", "HashSet", "Optional"
      )
      Language.TYPESCRIPT, Language.JSX_TSX -> setOf(
        "string", "number", "boolean", "any", "unknown", "never", "void", "null", "undefined",
        "object", "symbol", "bigint", "Promise", "Array", "Record", "Partial", "Required",
        "Readonly", "Pick", "Omit", "JSX", "ReactNode", "FC"
      )
      Language.GO -> setOf(
        "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32",
        "uint64", "float32", "float64", "bool", "byte", "rune", "error"
      )
      Language.RUST -> setOf(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result", "Box", "Rc", "Arc"
      )
      Language.C, Language.CPP -> setOf(
        "int", "long", "short", "char", "float", "double", "bool", "void", "size_t",
        "int32_t", "int64_t", "uint32_t", "uint64_t", "string", "vector", "map", "set", "unique_ptr", "shared_ptr"
      )
      else -> setOf("String", "Int", "Boolean", "Number", "Object", "List", "Map")
    }
  }
}
