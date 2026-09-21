package com.agentisco.editor.syntax

data class CodeSymbol(
  val name: String,
  val kind: SymbolKind,
  val line: Int,       // 1-indexed
  val signature: String = ""
)

enum class SymbolKind {
  CLASS,
  INTERFACE,
  FUNCTION,
  VARIABLE,
  STRUCT,
  ENUM,
  HEADING
}

object SymbolExtractor {

  fun extractSymbols(content: String, language: Language): List<CodeSymbol> {
    if (content.isBlank()) return emptyList()

    val symbols = mutableListOf<CodeSymbol>()
    val lines = content.lines()

    for ((index, line) in lines.withIndex()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")) {
        // Skip pure comments
        if (language != Language.MARKDOWN) continue
      }

      val lineNo = index + 1

      when (language) {
        Language.MARKDOWN -> {
          if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            val headingText = trimmed.drop(level).trim()
            if (headingText.isNotEmpty()) {
              symbols.add(CodeSymbol(name = headingText, kind = SymbolKind.HEADING, line = lineNo, signature = "#".repeat(level)))
            }
          }
        }
        Language.KOTLIN -> {
          // Class / Interface / Object
          val classMatch = Regex("""\b(class|interface|object|enum\s+class|sealed\s+class|data\s+class)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (classMatch != null) {
            val kindStr = classMatch.groups[1]?.value ?: "class"
            val name = classMatch.groups[2]?.value ?: ""
            val kind = if (kindStr.contains("interface")) SymbolKind.INTERFACE else if (kindStr.contains("enum")) SymbolKind.ENUM else SymbolKind.CLASS
            symbols.add(CodeSymbol(name, kind, lineNo, trimmed.take(50)))
            continue
          }
          // Fun
          val funMatch = Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_<>.]+\.)?([A-Za-z0-9_]+)\s*\(""").find(trimmed)
          if (funMatch != null) {
            val name = funMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
        Language.JAVA, Language.CSHARP -> {
          val classMatch = Regex("""\b(class|interface|enum|record)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (classMatch != null) {
            val kindStr = classMatch.groups[1]?.value ?: "class"
            val name = classMatch.groups[2]?.value ?: ""
            val kind = if (kindStr == "interface") SymbolKind.INTERFACE else if (kindStr == "enum") SymbolKind.ENUM else SymbolKind.CLASS
            symbols.add(CodeSymbol(name, kind, lineNo, trimmed.take(50)))
            continue
          }
          val methodMatch = Regex("""\b(?:public|private|protected|static|final|native|synchronized|abstract|\s)*\s+[A-Za-z0-9_<>[\]]+\s+([A-Za-z0-9_]+)\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\s*\{?""").find(trimmed)
          if (methodMatch != null) {
            val name = methodMatch.groups[1]?.value ?: ""
            if (name !in setOf("if", "for", "while", "switch", "catch")) {
              symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
            }
          }
        }
        Language.PYTHON -> {
          val classMatch = Regex("""^class\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (classMatch != null) {
            val name = classMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.CLASS, lineNo, trimmed.take(50)))
            continue
          }
          val defMatch = Regex("""^def\s+([A-Za-z0-9_]+)\s*\(""").find(trimmed)
          if (defMatch != null) {
            val name = defMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
        Language.JAVASCRIPT, Language.TYPESCRIPT, Language.JSX_TSX, Language.VUE -> {
          val classMatch = Regex("""\b(class|interface|type)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (classMatch != null) {
            val kind = if (classMatch.groups[1]?.value == "interface") SymbolKind.INTERFACE else SymbolKind.CLASS
            val name = classMatch.groups[2]?.value ?: ""
            symbols.add(CodeSymbol(name, kind, lineNo, trimmed.take(50)))
            continue
          }
          val fnMatch = Regex("""\b(?:function|const|let|var)\s+([A-Za-z0-9_]+)\s*(?:=\s*(?:async\s*)?\([^)]*\)\s*=>|=\s*function|\()""").find(trimmed)
          if (fnMatch != null) {
            val name = fnMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
        Language.GO -> {
          val typeMatch = Regex("""^type\s+([A-Za-z0-9_]+)\s+(struct|interface)""").find(trimmed)
          if (typeMatch != null) {
            val name = typeMatch.groups[1]?.value ?: ""
            val kind = if (typeMatch.groups[2]?.value == "interface") SymbolKind.INTERFACE else SymbolKind.STRUCT
            symbols.add(CodeSymbol(name, kind, lineNo, trimmed.take(50)))
            continue
          }
          val fnMatch = Regex("""^func\s+(?:\([^)]+\)\s+)?([A-Za-z0-9_]+)\s*\(""").find(trimmed)
          if (fnMatch != null) {
            val name = fnMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
        Language.RUST -> {
          val structMatch = Regex("""\b(struct|enum|trait)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (structMatch != null) {
            val name = structMatch.groups[2]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.STRUCT, lineNo, trimmed.take(50)))
            continue
          }
          val fnMatch = Regex("""\bfn\s+([A-Za-z0-9_]+)\s*\(""").find(trimmed)
          if (fnMatch != null) {
            val name = fnMatch.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
        Language.C, Language.CPP -> {
          val structMatch = Regex("""\b(class|struct|union|enum)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (structMatch != null) {
            val name = structMatch.groups[2]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.CLASS, lineNo, trimmed.take(50)))
            continue
          }
          val fnMatch = Regex("""^[A-Za-z0-9_*&<>\s]+\s+([A-Za-z0-9_]+)\s*\([^)]*\)\s*\{?""").find(trimmed)
          if (fnMatch != null) {
            val name = fnMatch.groups[1]?.value ?: ""
            if (name !in setOf("if", "for", "while", "switch", "catch")) {
              symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
            }
          }
        }
        else -> {
          // General regex for identifier followed by function call
          val generalFn = Regex("""^(?:def|fun|function|func|fn|sub)\s+([A-Za-z0-9_]+)""").find(trimmed)
          if (generalFn != null) {
            val name = generalFn.groups[1]?.value ?: ""
            symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNo, trimmed.take(50)))
          }
        }
      }
    }

    return symbols
  }
}
