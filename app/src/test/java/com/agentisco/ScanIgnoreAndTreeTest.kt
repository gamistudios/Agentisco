package com.agentisco

import com.agentisco.data.local.ScanIgnoreSettings
import com.agentisco.data.local.ScanIgnoreStore
import com.agentisco.workspace.filesystem.ProjectFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Huge-project hardening: tree scans are metadata-only and skip generated
 * folders; imports/mirrors never copy or delete them; the exclusion list is
 * user-configurable via [ScanIgnoreStore].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanIgnoreAndTreeTest {

  private fun freshFs(): Pair<ProjectFileSystem, File> {
    val tempDir = File(System.getProperty("java.io.tmpdir"), "test_scan_${System.currentTimeMillis()}")
    tempDir.mkdirs()
    return ProjectFileSystem(tempDir) to tempDir
  }

  @After
  fun restoreIgnoredDirs() {
    ProjectFileSystem.ignoredDirs = ProjectFileSystem.DEFAULT_IGNORED_DIRS
  }

  @Test
  fun `tree scan reads no file content and skips generated folders`() {
    val (fs, temp) = freshFs()
    try {
      val project = fs.createProject("Big", "test")
      fs.writeFile(project, "src/app.ts", "const x = 1;")
      File(project.path, "node_modules/left-pad").mkdirs()
      File(project.path, "node_modules/left-pad/index.js").writeText("junk ".repeat(100))
      File(project.path, "build").mkdirs()
      File(project.path, "build/out.o").writeText("binary")

      val tree = fs.getFileTree(project)
      assertTrue(tree.none { it.name == "node_modules" })
      assertTrue(tree.none { it.name == "build" })

      // Metadata-only: the file node exists but carries no content.
      val src = tree.first { it.name == "src" }
      val app = src.children.first { it.name == "app.ts" }
      assertEquals("", app.content)
      assertEquals("const x = 1;", fs.readFile(project, app.path))
    } finally {
      temp.deleteRecursively()
    }
  }

  @Test
  fun `depth-limited scan and listChildren stay shallow`() {
    val (fs, temp) = freshFs()
    try {
      val project = fs.createProject("Deep", "test")
      fs.writeFile(project, "src/deeper/file.ts", "x")

      val root = fs.getFileTree(project, maxDepth = 1)
      val src = root.first { it.name == "src" }
      assertTrue("depth 1 must not materialize children", src.children.isEmpty())

      val kids = fs.listChildren(project, "src")!!
      // src is scaffolded with index.ts too; deeper/ must appear as a plain
      // directory entry without its own contents being walked.
      assertTrue(kids.any { it.name == "deeper" && it.isDirectory })
      assertTrue("listChildren must not descend", kids.none { it.name == "file.ts" })
      assertTrue(kids.first().isDirectory)
      assertNull(fs.listChildren(project, "not-here"))
      assertNull(fs.listChildren(project, "src/deeper/file.ts"))
    } finally {
      temp.deleteRecursively()
    }
  }

  @Test
  fun `copyFolder imports skip and mirrorFolder preserves generated folders`() {
    val (fs, temp) = freshFs()
    try {
      val source = File(temp, "source").apply { mkdirs() }
      File(source, "src").mkdirs()
      File(source, "src/main.ts").writeText("code")
      File(source, "node_modules/pkg").mkdirs()
      File(source, "node_modules/pkg/index.js").writeText("dep")

      val workspace = File(temp, "workspace").apply { mkdirs() }
      fs.copyFolder(source, workspace)
      assertTrue(File(workspace, "src/main.ts").isFile)
      assertFalse("node_modules must not be copied", File(workspace, "node_modules").exists())

      // Mirroring workspace -> original must not delete the original's deps.
      File(source, "build").mkdirs()
      File(source, "build/stale.o").writeText("out")
      fs.mirrorFolder(workspace, source)
      assertTrue("mirror must never prune node_modules from the source", File(source, "node_modules/pkg").isDirectory)
      assertTrue("mirror must never prune build from the source", File(source, "build/stale.o").isFile)
    } finally {
      temp.deleteRecursively()
    }
  }

  @Test
  fun `searches skip generated folders and honor limits`() {
    val (fs, temp) = freshFs()
    try {
      val project = fs.createProject("Search", "test")
      fs.writeFile(project, "src/app.ts", "needle here")
      File(project.path, "node_modules/pkg").mkdirs()
      File(project.path, "node_modules/pkg/index.js").writeText("needle here")

      val content = fs.searchInProject(project, "needle")
      assertEquals(1, content.size)
      assertFalse(content.single().filePath.contains("node_modules"))

      for (i in 1..10) fs.writeFile(project, "many/hit-$i.ts", "")
      val names = fs.findFilesByName(project, "hit-", limit = 4)
      assertEquals(4, names.size)
      assertTrue(names.none { it.name == "node_modules" })
    } finally {
      temp.deleteRecursively()
    }
  }

  @Test
  fun `ignore settings extend or override defaults and sanitize input`() {
    val store = ScanIgnoreStore(null) // in-memory
    store.set(ScanIgnoreSettings())

    val extend = ScanIgnoreSettings(extraDirs = listOf("generated"), removedDefaults = listOf("dist"))
    val effective = ScanIgnoreStore.effectiveDirs(extend)
    assertTrue(effective.contains("node_modules"))
    assertTrue(effective.contains("generated"))
    assertFalse(effective.contains("dist"))

    val override = ScanIgnoreSettings(useCustomListOnly = true, extraDirs = listOf("mine"))
    assertEquals(setOf("mine"), ScanIgnoreStore.effectiveDirs(override))

    assertEquals("foo", ScanIgnoreStore.sanitizeName("  Foo  "))
    assertEquals("", ScanIgnoreStore.sanitizeName("a/b"))
    assertEquals("", ScanIgnoreStore.sanitizeName(".."))
    assertEquals("", ScanIgnoreStore.sanitizeName(""))

    // Applying through the store drives the global scanner list.
    ProjectFileSystem.ignoredDirs = ScanIgnoreStore.effectiveDirs(override)
    assertTrue(ProjectFileSystem.isIgnoredName("mine"))
    assertFalse(ProjectFileSystem.isIgnoredName("node_modules"))
  }
}
