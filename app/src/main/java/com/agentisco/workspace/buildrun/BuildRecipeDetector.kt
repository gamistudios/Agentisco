package com.agentisco.workspace.buildrun

import org.json.JSONObject
import java.io.File

/**
 * Turns a project folder into a suggested Run & Build pipeline. [detect] is a
 * deterministic local pass over real marker files (package.json scripts and
 * lockfiles, Gradle, Flutter, Python, Go, Rust, ...) that never touches the
 * network. [buildAiContext] collects a compact project description for an
 * optional LLM refinement, and [parseAiResponse] folds the model's strict-JSON
 * answer back into the same suggestion shape.
 */
object BuildRecipeDetector {

  private val SKIP_DIRS = setOf(
    "node_modules", ".git", "build", "dist", ".gradle", ".idea", ".dart_tool",
    "venv", ".venv", "__pycache__", "target", ".next", ".nuxt", ".cache",
    "Pods", "vendor"
  )

  private val KEY_FILE_EXCERPTS = listOf(
    "package.json", "pubspec.yaml", "requirements.txt", "pyproject.toml",
    "go.mod", "Cargo.toml", "build.gradle.kts", "build.gradle",
    "settings.gradle.kts", "settings.gradle", "pom.xml", "composer.json",
    "Gemfile", "Makefile", "README.md"
  )

  /** Local deterministic detection over the project's real files. */
  fun detect(projectDir: File): BuildRecipeSuggestion {
    val names = projectDir.list()?.toSet() ?: emptySet()
    return when {
      "package.json" in names -> detectNode(projectDir, names)
      "pubspec.yaml" in names -> suggestion(
        install = "flutter pub get",
        build = "flutter build apk --debug",
        test = "flutter test",
        run = "flutter run",
        notes = "Detected a Flutter project."
      )
      "gradlew" in names || "build.gradle.kts" in names || "build.gradle" in names -> suggestion(
        build = "./gradlew assembleDebug",
        test = "./gradlew test",
        notes = "Detected a Gradle project."
      )
      "manage.py" in names -> suggestion(
        install = pythonInstall(projectDir),
        test = "python3 manage.py test",
        run = "python3 manage.py runserver",
        port = 8000,
        notes = "Detected a Django project."
      )
      "requirements.txt" in names || "pyproject.toml" in names -> detectPython(projectDir)
      "go.mod" in names -> suggestion(
        install = "go mod download",
        build = "go build ./...",
        test = "go test ./...",
        run = "go run .",
        notes = "Detected a Go project."
      )
      "Cargo.toml" in names -> suggestion(
        build = "cargo build",
        test = "cargo test",
        run = "cargo run",
        notes = "Detected a Rust project."
      )
      "composer.json" in names -> suggestion(
        install = "composer install",
        test = if (File(projectDir, "phpunit.xml").exists() || File(projectDir, "phpunit.xml.dist").exists()) "vendor/bin/phpunit" else "",
        run = if (File(projectDir, "artisan").isFile) "php artisan serve" else "",
        port = if (File(projectDir, "artisan").isFile) 8000 else null,
        notes = "Detected a PHP project."
      )
      "Gemfile" in names -> suggestion(
        install = "bundle install",
        test = if (File(projectDir, "spec").isDirectory) "bundle exec rspec" else "",
        run = if (File(projectDir, "bin/rails").isFile) "bundle exec rails server" else "",
        port = if (File(projectDir, "bin/rails").isFile) 3000 else null,
        notes = "Detected a Ruby project."
      )
      names.any { it.endsWith(".csproj") || it.endsWith(".sln") } -> suggestion(
        install = "dotnet restore",
        build = "dotnet build",
        test = "dotnet test",
        run = "dotnet run",
        notes = "Detected a .NET project."
      )
      "pom.xml" in names -> suggestion(
        build = "mvn package",
        test = "mvn test",
        notes = "Detected a Maven project."
      )
      "index.html" in names -> suggestion(
        run = "python3 -m http.server 8000",
        port = 8000,
        notes = "Detected a static HTML site."
      )
      else -> BuildRecipeSuggestion(notes = "No known project markers found.")
    }
  }

  private fun detectNode(projectDir: File, names: Set<String>): BuildRecipeSuggestion {
    val pm = when {
      "pnpm-lock.yaml" in names -> "pnpm"
      "yarn.lock" in names -> "yarn"
      "bun.lockb" in names || "bun.lock" in names -> "bun"
      else -> "npm"
    }
    val pkg = runCatching { JSONObject(File(projectDir, "package.json").readText()) }.getOrNull()
    val scriptNames = pkg?.optJSONObject("scripts")?.keys()?.asSequence()?.toSet() ?: emptySet()
    val deps = pkg?.optJSONObject("dependencies")?.keys()?.asSequence()?.toSet() ?: emptySet()
    val devDeps = pkg?.optJSONObject("devDependencies")?.keys()?.asSequence()?.toSet() ?: emptySet()
    val runScript = listOf("dev", "start", "serve").firstOrNull { scriptNames.contains(it) }
    val port = when {
      "vite" in deps || "vite" in devDeps -> 5173
      "next" in deps -> 3000
      "react-scripts" in deps -> 3000
      "nuxt" in deps || "nuxt3" in deps -> 3000
      "astro" in deps -> 4321
      "expo" in deps -> 8081
      "@angular/cli" in devDeps -> 4200
      else -> null
    }
    return suggestion(
      install = "$pm install",
      build = if (scriptNames.contains("build")) "$pm run build" else "",
      test = if (scriptNames.contains("test")) "$pm test" else "",
      run = runScript?.let { "$pm run $it" } ?: "",
      port = port,
      notes = "Detected a Node.js project ($pm)."
    )
  }

  private fun detectPython(projectDir: File): BuildRecipeSuggestion {
    val install = pythonInstall(projectDir).ifBlank { "pip3 install -e ." }
    val run = when {
      File(projectDir, "app.py").isFile -> "python3 app.py"
      File(projectDir, "main.py").isFile -> "python3 main.py"
      else -> ""
    }
    val test = if (File(projectDir, "tests").isDirectory) "python3 -m pytest" else ""
    val port = if (File(projectDir, "app.py").isFile) 5000 else null
    return suggestion(
      install = install,
      test = test,
      run = run,
      port = port,
      notes = "Detected a Python project."
    )
  }

  private fun pythonInstall(dir: File): String =
    if (File(dir, "requirements.txt").isFile) "pip3 install -r requirements.txt" else ""

  private fun suggestion(
    install: String = "",
    build: String = "",
    test: String = "",
    run: String = "",
    port: Int? = null,
    notes: String = ""
  ) = BuildRecipeSuggestion(
    commands = mapOf(
      BuildStageKind.INSTALL to install,
      BuildStageKind.BUILD to build,
      BuildStageKind.TEST to test,
      BuildStageKind.RUN to run
    ),
    runPort = port,
    notes = notes
  )

  /**
   * Compact description of the project for the AI pass: shallow tree plus
   * excerpts of the marker files, capped so the request stays small.
   */
  fun buildAiContext(projectDir: File, maxChars: Int = 16_000): String {
    val sb = StringBuilder()
    sb.append("Project name: ").append(projectDir.name).append('\n')
    sb.append("Top-level entries: ")
      .append(projectDir.list()?.sorted()?.take(60)?.joinToString(", ") ?: "")
      .append("\n\n")
    sb.append("Directory tree (up to two levels):\n")
    appendTree(projectDir, "", 2, sb)
    for (name in KEY_FILE_EXCERPTS) {
      val file = File(projectDir, name)
      if (!file.isFile || file.length() > 200_000) continue
      val text = runCatching { file.readText().take(4000) }.getOrNull() ?: continue
      sb.append("\n--- ").append(name).append(" ---\n").append(text).append('\n')
    }
    return sb.toString().take(maxChars)
  }

  private fun appendTree(dir: File, indent: String, depth: Int, sb: StringBuilder) {
    val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
    var shownDirs = 0
    for (child in children) {
      if (!child.isDirectory || child.name in SKIP_DIRS) continue
      if (shownDirs >= 30) {
        sb.append(indent).append("…\n")
        break
      }
      sb.append(indent).append(child.name).append("/\n")
      if (depth > 1) appendTree(child, "$indent  ", depth - 1, sb)
      shownDirs++
    }
    var shownFiles = 0
    for (child in children) {
      if (!child.isFile) continue
      if (shownFiles >= 40) {
        sb.append(indent).append("…\n")
        break
      }
      sb.append(indent).append(child.name).append('\n')
      shownFiles++
    }
  }

  /**
   * Parses the model's strict-JSON refinement. Tolerates code fences and
   * surrounding prose; rejects placeholders and multi-line commands so only
   * genuinely runnable single-line commands are applied.
   */
  fun parseAiResponse(raw: String): BuildRecipeSuggestion? {
    val cleaned = raw.trim()
      .removePrefix("```json").removePrefix("```")
      .removeSuffix("```").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start == -1 || end <= start) return null
    val obj = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull() ?: return null

    fun command(key: String): String {
      val value = obj.optString(key).lineSequence().firstOrNull()?.trim().orEmpty()
      val lowered = value.lowercase()
      val placeholder = lowered in setOf("n/a", "none", "null", "not applicable", "-")
      return if (value.length in 1..200 && !placeholder) value else ""
    }

    val port = obj.optInt("port", -1).takeIf { it in 1..65535 }
    val suggestion = BuildRecipeSuggestion(
      commands = mapOf(
        BuildStageKind.INSTALL to command("install"),
        BuildStageKind.BUILD to command("build"),
        BuildStageKind.TEST to command("test"),
        BuildStageKind.RUN to command("run")
      ),
      runPort = port,
      notes = obj.optString("summary").take(200)
    )
    if (suggestion.commands.values.all { it.isBlank() } && port == null) return null
    return suggestion
  }
}
