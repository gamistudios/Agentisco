package com.awaki

import com.awaki.data.model.Project
import com.awaki.data.model.ProjectKind
import com.awaki.workspace.filesystem.ProjectMetadataScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The workspace's project metadata (size / recency / icon / type) is measured
 * from real folders — these tests use real temporary folders on disk.
 */
class ProjectMetadataScannerTest {

  private fun tempDir(name: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "awaki_meta_${name}_${System.nanoTime()}")
    dir.mkdirs()
    dir.deleteOnExit()
    return dir
  }

  @Test
  fun `measures total size and newest modification time recursively`() {
    val root = tempDir("size")
    File(root, "README.md").writeText("hello")           // 5 bytes
    val nested = File(root, "src/deep").apply { mkdirs() }
    File(nested, "index.ts").writeText("0123456789")     // 10 bytes

    val meta = ProjectMetadataScanner.scan(root)
    assertNotNull(meta)
    assertEquals(15L, meta!!.sizeBytes)
    assertTrue("newest mtime should be measured", meta.lastModified > 0L)
  }

  @Test
  fun `detects project type from marker files that exist`() {
    val python = tempDir("python")
    File(python, "pyproject.toml").writeText("[project]")
    assertEquals(ProjectKind.PYTHON, ProjectMetadataScanner.scan(python)?.kind)

    val node = tempDir("node")
    File(node, "package.json").writeText("{}")
    assertEquals(ProjectKind.NODE, ProjectMetadataScanner.scan(node)?.kind)

    // Android wins over the plain Gradle markers it also ships.
    val android = tempDir("android")
    File(android, "build.gradle.kts").writeText("")
    File(android, "app/src/main").mkdirs()
    File(android, "app/src/main/AndroidManifest.xml").writeText("<manifest/>")
    assertEquals(ProjectKind.ANDROID, ProjectMetadataScanner.scan(android)?.kind)

    val plain = tempDir("plain")
    File(plain, "notes.txt").writeText("x")
    assertEquals(ProjectKind.UNKNOWN, ProjectMetadataScanner.scan(plain)?.kind)
  }

  @Test
  fun `finds a real icon in the project root and returns null without one`() {
    val withoutIcon = tempDir("icon_none")
    File(withoutIcon, "notes.txt").writeText("x")
    assertNull(ProjectMetadataScanner.scan(withoutIcon)?.iconPath)

    val root = tempDir("icon")
    File(root, "src/deep").mkdirs()
    val logo = File(root, "logo.png")
    logo.writeBytes(byteArrayOf(1, 2, 3, 4))
    assertEquals(logo.absolutePath, ProjectMetadataScanner.scan(root)?.iconPath)
  }

  @Test
  fun `finds favicon ico in nested directory up to depth 5`() {
    val root = tempDir("nested_favicon")
    val publicDir = File(root, "client/public").apply { mkdirs() }
    val favicon = File(publicDir, "favicon.ico")
    favicon.writeBytes(byteArrayOf(0, 0, 1, 0, 1, 0))
    val scanned = ProjectMetadataScanner.scan(root)
    assertNotNull(scanned)
    assertEquals(favicon.absolutePath, scanned?.iconPath)
  }

  @Test
  fun `returns null for missing folders and memoizes results`() {
    val root = tempDir("cache")
    File(root, "a.txt").writeText("abc")

    assertNull(ProjectMetadataScanner.scan(File(root, "does-not-exist")))
    assertNull(ProjectMetadataScanner.scan(Project(id = "p", name = "n", branch = "main", lastActivity = "", path = "")))

    val first = ProjectMetadataScanner.scan(root)
    assertEquals(first, ProjectMetadataScanner.cached(root.absolutePath))
    assertEquals(first, ProjectMetadataScanner.scan(root))
  }
}
