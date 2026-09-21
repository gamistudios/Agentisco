package com.agentisco.editor

import androidx.compose.ui.text.AnnotatedString
import com.agentisco.data.model.ProjectFile
import com.agentisco.editor.model.EditorTab
import com.agentisco.editor.syntax.*
import org.junit.Assert.*
import org.junit.Test

class EditorEngineTest {

  @Test
  fun testLanguageDetectionFromExtension() {
    assertEquals(Language.PYTHON, Language.fromFileName("script.py"))
    assertEquals(Language.JAVASCRIPT, Language.fromFileName("index.js"))
    assertEquals(Language.TYPESCRIPT, Language.fromFileName("service.ts"))
    assertEquals(Language.JSX_TSX, Language.fromFileName("App.tsx"))
    assertEquals(Language.JSX_TSX, Language.fromFileName("Component.jsx"))
    assertEquals(Language.HTML, Language.fromFileName("index.html"))
    assertEquals(Language.CSS, Language.fromFileName("styles.css"))
    assertEquals(Language.SCSS, Language.fromFileName("theme.scss"))
    assertEquals(Language.JSON, Language.fromFileName("package.json"))
    assertEquals(Language.YAML, Language.fromFileName("deploy.yaml"))
    assertEquals(Language.YAML, Language.fromFileName("config.yml"))
    assertEquals(Language.MARKDOWN, Language.fromFileName("README.md"))
    assertEquals(Language.KOTLIN, Language.fromFileName("MainActivity.kt"))
    assertEquals(Language.JAVA, Language.fromFileName("Main.java"))
    assertEquals(Language.C, Language.fromFileName("main.c"))
    assertEquals(Language.CPP, Language.fromFileName("engine.cpp"))
    assertEquals(Language.RUST, Language.fromFileName("main.rs"))
    assertEquals(Language.GO, Language.fromFileName("server.go"))
    assertEquals(Language.SHELL, Language.fromFileName("build.sh"))
    assertEquals(Language.SQL, Language.fromFileName("schema.sql"))
    assertEquals(Language.XML, Language.fromFileName("AndroidManifest.xml"))
    assertEquals(Language.DOCKERFILE, Language.fromFileName("Dockerfile"))
  }

  @Test
  fun testSyntaxHighlighterTokenization() {
    val theme = SyntaxTheme.AgentiscoDark
    val pythonLine = "def calculate_sum(a, b):"
    val highlighted = SyntaxHighlighter.highlightLine(pythonLine, Language.PYTHON, theme)
    assertNotNull(highlighted)
    assertEquals(pythonLine, highlighted.text)
    assertTrue("Should have span styles for keywords and function", highlighted.spanStyles.isNotEmpty())

    val jsLine = "import { useState } from 'react';"
    val jsHighlighted = SyntaxHighlighter.highlightLine(jsLine, Language.JAVASCRIPT, theme)
    assertEquals(jsLine, jsHighlighted.text)
    assertTrue("Should have span styles for import and string", jsHighlighted.spanStyles.isNotEmpty())
  }

  @Test
  fun testMultiLineHighlightCode() {
    val theme = SyntaxTheme.AgentiscoDark
    val code = """
      // A sample program
      import React from 'react';
      
      export function App() {
        const count = 42;
        return <div className="app">Hello</div>;
      }
    """.trimIndent()

    val highlighted = SyntaxHighlighter.highlightCode(code, Language.JSX_TSX, theme)
    assertEquals(code, highlighted.text)
    assertTrue("Should have multiple highlighted spans across lines", highlighted.spanStyles.size >= 4)
  }

  @Test
  fun testJsonHighlighting() {
    val theme = SyntaxTheme.AgentiscoDark
    val json = """
      {
        "name": "agentisco",
        "version": 1,
        "active": true
      }
    """.trimIndent()

    val highlighted = SyntaxHighlighter.highlightCode(json, Language.JSON, theme)
    assertEquals(json, highlighted.text)
    assertTrue("JSON keys and values must be styled", highlighted.spanStyles.isNotEmpty())
  }

  @Test
  fun testSyntaxHighlightTransformation() {
    val theme = SyntaxTheme.AgentiscoDark
    val transformation = SyntaxHighlightTransformation(Language.PYTHON, theme)
    val input = AnnotatedString("def foo():\n    return 123")
    val transformed = transformation.filter(input)
    assertEquals(input.text, transformed.text.text)
    assertTrue("Transformed text must contain styled spans", transformed.text.spanStyles.isNotEmpty())
  }

  @Test
  fun testSymbolExtraction() {
    val kotlinCode = """
      class UserManager {
        fun authenticateUser(token: String): Boolean {
          return true
        }
      }
      interface AuthCallback {
        fun onSuccess()
      }
    """.trimIndent()

    val symbols = SymbolExtractor.extractSymbols(kotlinCode, Language.KOTLIN)
    assertTrue("Should extract symbols from Kotlin code", symbols.isNotEmpty())
    val names = symbols.map { it.name }
    assertTrue("Should contain UserManager class", names.contains("UserManager"))
    assertTrue("Should contain authenticateUser function", names.contains("authenticateUser"))
    assertTrue("Should contain AuthCallback interface", names.contains("AuthCallback"))
  }

  @Test
  fun testMarkdownSymbolExtraction() {
    val md = """
      # Project Title
      ## Getting Started
      ### Installation
    """.trimIndent()

    val symbols = SymbolExtractor.extractSymbols(md, Language.MARKDOWN)
    assertEquals(3, symbols.size)
    assertEquals("Project Title", symbols[0].name)
    assertEquals("Getting Started", symbols[1].name)
    assertEquals("Installation", symbols[2].name)
    assertEquals(SymbolKind.HEADING, symbols[0].kind)
  }

  @Test
  fun testEditorTabDirtyAndUndoRedo() {
    val file = ProjectFile(path = "src/test.py", name = "test.py", isDirectory = false, content = "print('hello')", sizeBytes = 14)
    val tab = EditorTab(id = "1", file = file, content = file.content, savedContent = file.content)
    assertFalse("Initial tab should not be dirty", tab.isDirty)

    val modifiedTab = tab.withContent("print('hello world')")
    assertTrue("Modified tab should be dirty", modifiedTab.isDirty)
    assertEquals(1, modifiedTab.undoStack.size)

    val undoneTab = modifiedTab.undo()
    assertNotNull("Undone tab should not be null", undoneTab)
    assertEquals("print('hello')", undoneTab!!.content)
    assertFalse("Reverted tab should not be dirty", undoneTab.isDirty)
    assertEquals(1, undoneTab.redoStack.size)

    val redoneTab = undoneTab.redo()
    assertNotNull("Redone tab should not be null", redoneTab)
    assertEquals("print('hello world')", redoneTab!!.content)
    assertTrue("Redone tab should be dirty again", redoneTab.isDirty)
  }
}
