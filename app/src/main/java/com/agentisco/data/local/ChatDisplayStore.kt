package com.agentisco.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Chat tool-activity rendering preferences. */
data class ChatDisplaySettings(
  /**
   * When true, tool cards additionally show the raw request JSON and full
   * raw responses. Default off: cards stay structured (command line, diff,
   * plain output) with the JSON hidden.
   */
  val showToolJson: Boolean = false
)

/**
 * Durable chat-display settings stored as JSON under the app-private dir.
 * Degrades to in-memory operation when [context] is null (tests / previews).
 */
class ChatDisplayStore(private val context: Context? = null) {

  private val dir: File? = context?.getDir("agentisco", Context.MODE_PRIVATE)
  private val configFile: File? get() = dir?.resolve("chat_display.json")

  private var cached: ChatDisplaySettings? = null

  @Synchronized
  fun get(): ChatDisplaySettings {
    cached?.let { return it }
    val loaded = configFile?.takeIf { it.isFile }?.let { file ->
      runCatching {
        val obj = JSONObject(file.readText())
        ChatDisplaySettings(showToolJson = obj.optBoolean("showToolJson", false))
      }.getOrNull()
    } ?: ChatDisplaySettings()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun update(transform: (ChatDisplaySettings) -> ChatDisplaySettings): ChatDisplaySettings {
    val next = transform(get())
    cached = next
    runCatching {
      val file = configFile ?: return@runCatching
      file.writeText(JSONObject().put("showToolJson", next.showToolJson).toString(2))
    }
    return next
  }
}
