package com.agentisco

import com.agentisco.data.model.DiffLineType
import com.agentisco.data.model.FileDiff
import com.agentisco.data.model.Project
import com.agentisco.ui.components.MAX_DIFF_LINES
import com.agentisco.workspace.filesystem.ProjectFileSystem
import com.agentisco.workspace.git.GitRepositoryManager
import com.agentisco.workspace.git.GitRunResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The Source Control diff pass reads both sides of every changed file into a
 * phone heap, so the shapes that used to exhaust it - a deleted generated file,
 * a blob git cannot hand over complete, an oversized asset - have to end in a
 * description of the file rather than an OutOfMemoryError.
 *
 * git itself is faked here so each case is exact: no test depends on the
 * machine's git to produce a megabyte of blob.
 */
class GitDiffPassTest {

  private fun workspace(): Pair<ProjectFileSystem, Project> {
    val root = File(System.getProperty("java.io.tmpdir"), "agentisco_diffpass_${System.nanoTime()}")
    val projectsRoot = File(root, "projects").apply { mkdirs() }
    val projectDir = File(projectsRoot, "demo").apply { mkdirs() }
    File(projectDir, ".git").mkdirs()
    return ProjectFileSystem(projectsRoot) to Project(
      id = "p", name = "demo", branch = "main",
      lastActivity = "now", path = projectDir.absolutePath
    )
  }

  /**
   * A manager whose git is scripted: [status] is the porcelain reply, [shows]
   * maps a path to the blob `git show` returns for it, and [numstat] carries the
   * per-file line counts git reports.
   */
  private fun manager(
    fs: ProjectFileSystem,
    status: String,
    numstat: String = "",
    shows: Map<String, GitRunResult> = emptyMap()
  ): GitRepositoryManager = GitRepositoryManager(fs) { _, args ->
    when {
      args.startsWith("git config") -> GitRunResult(0, "")
      args.startsWith("git status") -> GitRunResult(0, status)
      args.contains("--numstat") -> GitRunResult(0, numstat)
      args.startsWith("git show") -> {
        val token = args.substringAfter("git show ").substringBefore(" 2>/dev/null")
        val path = token.removePrefix("HEAD:").removePrefix(":").trim().trim('\'')
        shows[path] ?: GitRunResult(1, "")
      }
      else -> GitRunResult(0, "")
    }
  }

  @Test
  fun `a deleted generated file is described instead of diffed`() {
    val (fs, project) = workspace()
    // The crash this guards: the file is gone from disk, so no disk-size check can
    // catch it - only the blob git still holds is enormous.
    val blob = "generated line\n".repeat(MAX_DIFF_LINES + 25)
    val git = manager(
      fs,
      status = " D dist/bundle.js\n",
      numstat = "0\t${MAX_DIFF_LINES + 25}\tdist/bundle.js\n",
      shows = mapOf("dist/bundle.js" to GitRunResult(0, blob))
    )
    val diffs = runBlocking { git.computeAllDiffs(project) }

    assertEquals(listOf("dist/bundle.js"), diffs.map { it.filePath })
    val diff = diffs.single()
    assertEquals("Too many lines to diff", diff.lines.single().text)
    assertEquals(DiffLineType.UNCHANGED, diff.lines.single().type)
    // The counts still come from git, so the Source Control badge stays honest.
    assertEquals(0, diff.additionsCount)
    assertEquals(MAX_DIFF_LINES + 25, diff.deletionsCount)
    // None of the blob is kept in the result.
    assertEquals("", diff.originalContent)
    assertEquals("", diff.newContent)
  }

  @Test
  fun `a blob git could not hand over complete is never diffed`() {
    val (fs, project) = workspace()
    val git = manager(
      fs,
      status = " M big.txt\n",
      numstat = "40\t12\tbig.txt\n",
      shows = mapOf("big.txt" to GitRunResult(0, "half a file", truncated = true))
    )
    val diff: FileDiff = runBlocking { git.computeAllDiffs(project) }.single()

    assertEquals("File too large to diff", diff.lines.single().text)
    assertEquals(40, diff.additionsCount)
    assertEquals(12, diff.deletionsCount)
  }

  @Test
  fun `an image asset is reported as binary`() {
    val (fs, project) = workspace()
    File(project.path, "logo.png").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
    val git = manager(fs, status = " M logo.png\n")
    val diff = runBlocking { git.computeAllDiffs(project) }.single()

    assertEquals("Binary file", diff.lines.single().text)
  }

  @Test
  fun `a working file above the read ceiling is described without being opened`() {
    val (fs, project) = workspace()
    // Above GitRepositoryManager's per-file read ceiling.
    File(project.path, "huge.txt").writeText("x".repeat(1_100_000))
    val git = manager(fs, status = " M huge.txt\n", numstat = "1\t1\thuge.txt\n")
    val diff = runBlocking { git.computeAllDiffs(project) }.single()

    assertEquals("File too large to diff", diff.lines.single().text)
    assertEquals(1, diff.additionsCount)
    assertEquals(1, diff.deletionsCount)
  }

  @Test
  fun `an ordinary change still diffs line by line`() {
    val (fs, project) = workspace()
    File(project.path, "app.txt").writeText("a\nB\nc")
    val git = manager(
      fs,
      status = " M app.txt\n",
      numstat = "1\t1\tapp.txt\n",
      shows = mapOf("app.txt" to GitRunResult(0, "a\nb\nc"))
    )
    val diff = runBlocking { git.computeAllDiffs(project) }.single()

    assertEquals(
      listOf(
        DiffLineType.UNCHANGED, DiffLineType.REMOVED,
        DiffLineType.ADDED, DiffLineType.UNCHANGED
      ),
      diff.lines.map { it.type }
    )
    assertEquals(1, diff.additionsCount)
    assertEquals(1, diff.deletionsCount)
    assertEquals("a\nb\nc", diff.originalContent)
    assertEquals("a\nB\nc", diff.newContent)
  }
}
