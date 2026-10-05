package com.awaki.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/** How the projects list is laid out. */
data class ProjectsViewSettings(
  /** Two-up cards; the single-column list is the opt-in. */
  val gridView: Boolean = true
)

/**
 * Durable projects-layout setting stored as JSON under the app-private dir.
 * Degrades to in-memory operation when [context] is null (tests / previews).
 */
class ProjectsViewStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("awaki", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("projects_view.json")

  private var cached: ProjectsViewSettings? = null

  @Synchronized
  fun get(): ProjectsViewSettings {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        ProjectsViewSettings(gridView = JSONObject(file.readText()).optBoolean("gridView", true))
      }.getOrNull()
    } ?: ProjectsViewSettings()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun update(transform: (ProjectsViewSettings) -> ProjectsViewSettings): ProjectsViewSettings {
    val next = transform(get())
    cached = next
    runCatching {
      val file = configFile ?: return@runCatching
      file.writeText(JSONObject().put("gridView", next.gridView).toString(2))
    }
    return next
  }
}
