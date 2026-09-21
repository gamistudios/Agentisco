package com.agentisco.workspace.filesystem

import com.agentisco.data.model.Project
import com.agentisco.data.model.ProjectKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Measured facts about a project folder. Every field comes from real disk
 * inspection — nothing here is inferred from the project's display name.
 */
data class ProjectMetadata(
  /** Total bytes of all regular files under the project root. */
  val sizeBytes: Long,
  /** Newest modification time found in the tree (epoch millis). */
  val lastModified: Long,
  /** Absolute path of a decodable icon found in the project, or null. */
  val iconPath: String?,
  /** Type inferred from marker files present in the root. */
  val kind: ProjectKind
)

/**
 * Walks project folders to measure size / recency and to locate a real project
 * icon. Deliberately pure `java.io` so it stays unit-testable, and deliberately
 * cached + bounded so it can be called from a background refresh without ever
 * touching the main thread or hanging on a pathological tree.
 *
 * Callers are expected to invoke [scan] from [kotlinx.coroutines.Dispatchers.IO];
 * [cached] can be read from anywhere and returns null until the first scan.
 */
object ProjectMetadataScanner {

  /** Hard caps so a runaway tree (symlink loop, generated dump) can't hang us. */
  private const val MAX_WALK_DEPTH = 32
  private const val MAX_WALK_ENTRIES = 200_000

  /** Re-measure at most this often, even if the root looks untouched. */
  private const val CACHE_TTL_MS = 30 * 60 * 1000L

  private const val MAX_ICON_BYTES = 2L * 1024 * 1024

  private data class CacheEntry(
    val metadata: ProjectMetadata,
    val scannedAt: Long,
    val rootLastModified: Long
  )

  private val cache = ConcurrentHashMap<String, CacheEntry>()

  /** Last measured metadata for [projectPath], or null when never scanned. */
  fun cached(projectPath: String): ProjectMetadata? = cache[projectPath]?.metadata

  /**
   * Measures [root]. Returns null when the folder is missing/unreadable, so
   * callers keep the previous (or placeholder) values instead of inventing one.
   */
  fun scan(root: File): ProjectMetadata? {
    if (root.path.isBlank() || !root.isDirectory || !root.canRead()) return null
    val key = root.absolutePath
    val now = System.currentTimeMillis()
    val rootStamp = root.lastModified()
    cache[key]?.let { entry ->
      if (now - entry.scannedAt < CACHE_TTL_MS && entry.rootLastModified == rootStamp) {
        return entry.metadata
      }
    }

    val (size, newest) = measure(root)
    val metadata = ProjectMetadata(
      sizeBytes = size,
      lastModified = maxOf(newest, rootStamp),
      iconPath = findIcon(root)?.absolutePath,
      kind = detectKind(root)
    )
    cache[key] = CacheEntry(metadata, now, rootStamp)
    return metadata
  }

  /** Drops memoized results for folders that no longer exist. */
  fun evictStale() {
    cache.entries.removeAll { !File(it.key).isDirectory }
  }

  /** Total bytes plus the newest mtime, in a single bounded walk. */
  private fun measure(root: File): Pair<Long, Long> {
    var total = 0L
    var newest = 0L
    var visited = 0
    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
      val (dir, depth) = stack.removeLast()
      val children = dir.listFiles() ?: continue
      for (child in children) {
        if (visited++ >= MAX_WALK_ENTRIES) return total to newest
        if (child.isDirectory) {
          if (depth < MAX_WALK_DEPTH) stack.addLast(child to depth + 1)
        } else {
          total += child.length()
          val stamp = child.lastModified()
          if (stamp > newest) newest = stamp
        }
      }
    }
    return total to newest
  }

  /**
   * Looks for a real project icon. Ordered most-specific first; the first
   * existing candidate wins. `.ico` is last because Android can only decode
   * ICO files that embed a PNG payload (see the UI-side loader).
   */
  private fun findIcon(root: File): File? {
    for (relative in ICON_CANDIDATES) {
      val file = File(root, relative)
      if (file.isFile && file.canRead() && file.length() in 1..MAX_ICON_BYTES) return file
    }
    // Android launcher icons live under mipmap-<density>; prefer the densest.
    for (base in LAUNCHER_ICON_DIRS) {
      val resDir = File(root, base)
      val densities = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") } ?: continue
      val best = densities.sortedByDescending { densityRank(it.name) }
        .asSequence()
        .flatMap { dir -> ICON_STEMS.asSequence().map { stem -> File(dir, "$stem.png") } }
        .firstOrNull { it.isFile && it.canRead() && it.length() in 1..MAX_ICON_BYTES }
      if (best != null) return best
    }
    return null
  }

  private fun densityRank(dirName: String): Int = when {
    dirName.contains("anydpi") -> 6
    dirName.contains("xxxhdpi") -> 5
    dirName.contains("xxhdpi") -> 4
    dirName.contains("xhdpi") -> 3
    dirName.contains("hdpi") -> 2
    dirName.contains("mdpi") -> 1
    else -> 0
  }

  /**
   * Type detection from marker files that really exist in the root. Order is
   * deliberate: the most specific ecosystem wins (an Android app is also a
   * Gradle project).
   */
  private fun detectKind(root: File): ProjectKind {
    fun exists(vararg names: String) = names.any { File(root, it).exists() }
    return when {
      exists("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml") -> ProjectKind.ANDROID
      exists("pubspec.yaml") -> ProjectKind.FLUTTER
      exists("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradlew") ->
        ProjectKind.GRADLE
      exists("package.json") -> ProjectKind.NODE
      exists("Cargo.toml") -> ProjectKind.RUST
      exists("go.mod") -> ProjectKind.GO
      exists("pyproject.toml", "requirements.txt", "setup.py", "Pipfile", "manage.py") -> ProjectKind.PYTHON
      exists("pom.xml") -> ProjectKind.MAVEN
      exists("composer.json") -> ProjectKind.PHP
      exists("Gemfile") -> ProjectKind.RUBY
      exists("CMakeLists.txt", "Makefile", "meson.build") -> ProjectKind.CPP
      hasProjectFile(root, ".csproj", ".sln", ".fsproj") -> ProjectKind.DOTNET
      File(root, ".git").exists() -> ProjectKind.GIT_REPO
      else -> ProjectKind.UNKNOWN
    }
  }

  private fun hasProjectFile(root: File, vararg extensions: String): Boolean {
    val names = root.list() ?: return false
    return names.any { name -> extensions.any { name.endsWith(it, ignoreCase = true) } }
  }

  /** Convenience: metadata for a [Project], or null when it has no usable path. */
  fun scan(project: Project): ProjectMetadata? =
    if (project.path.isBlank()) null else scan(File(project.path))

  private val ICON_CANDIDATES = listOf(
    ".agentisco/icon.png", ".agentisco/icon.webp", ".agentisco/icon.jpg",
    "logo.png", "logo.webp", "logo.jpg",
    "icon.png", "app-icon.png", "project-icon.png",
    "public/favicon.png", "public/logo.png", "public/icon.png",
    "static/favicon.png", "static/logo.png",
    "assets/logo.png", "assets/icon.png",
    "web/favicon.png", "web/logo.png",
    "favicon.png",
    "favicon.ico"
  )

  private val LAUNCHER_ICON_DIRS = listOf(
    "app/src/main/res",
    "android/app/src/main/res",
    "src/main/res"
  )

  private val ICON_STEMS = listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground")
}
