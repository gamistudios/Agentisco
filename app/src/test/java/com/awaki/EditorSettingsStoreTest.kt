package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.local.EditorSettingsStore
import com.awaki.editor.model.EditorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Editor preferences used to live in a plain state flow, so a font size chosen this
 * morning was gone by the afternoon. What has to hold here: a written value is what a
 * new reader of the file gets, a file the app does not fully understand still yields
 * usable settings, and a store with no application keeps working in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSettingsStoreTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  private val file: File
    get() = File(context.getDir("awaki", Context.MODE_PRIVATE), "editor_settings.json")

  @Before
  fun clearPersistedSettings() {
    file.delete()
  }

  @Test
  fun `a fresh store starts on the documented defaults`() {
    val settings = EditorSettingsStore(context).get()
    assertEquals(EditorSettings(), settings)
    assertEquals(12, settings.fontSize)
    assertEquals("Awaki Dark", settings.syntaxThemeName)
  }

  @Test
  fun `every field survives the process it was set in`() {
    val changed = EditorSettings(
      fontSize = 17,
      lineHeightMultiplier = 1.55f,
      tabSize = 4,
      useSpaces = false,
      wordWrap = true,
      showLineNumbers = false,
      highlightActiveLine = false,
      bracketMatching = false,
      codeFolding = false,
      syntaxThemeName = "Tokyo Night",
      autoSave = true,
      formatOnSave = true,
      showMinimap = true,
      touchShortcutsExpanded = false
    )
    EditorSettingsStore(context).update { changed }

    val reread = EditorSettingsStore(context).get()
    assertEquals(changed, reread)
    assertEquals(17, reread.fontSize)
    assertEquals(1.55f, reread.lineHeightMultiplier, 0.0001f)
    assertFalse(reread.useSpaces)
    assertEquals("Tokyo Night", reread.syntaxThemeName)
    assertTrue(reread.showMinimap)
  }

  @Test
  fun `an update writes from what is on disk, not from what the caller remembered`() {
    EditorSettingsStore(context).update { it.copy(tabSize = 8) }
    val second = EditorSettingsStore(context)
    second.update { it.copy(fontSize = 20) }

    assertEquals("the first change must still be there", 8, second.get().tabSize)
    assertEquals(20, second.get().fontSize)
  }

  @Test
  fun `a file missing a key keeps that default instead of a zero`() {
    file.writeText("""{"fontSize":18,"tabSize":4}""")

    val settings = EditorSettingsStore(context).get()
    assertEquals(18, settings.fontSize)
    assertEquals(4, settings.tabSize)
    assertEquals(1.35f, settings.lineHeightMultiplier, 0.0001f)
    assertTrue("useSpaces default is true", settings.useSpaces)
    assertEquals("Awaki Dark", settings.syntaxThemeName)
  }

  @Test
  fun `a file the app cannot read costs the user nothing but that one change`() {
    file.writeText("this was never json {{{")

    assertEquals(EditorSettings(), EditorSettingsStore(context).get())

    val settings = EditorSettingsStore(context).update { it.copy(fontSize = 15) }
    assertEquals(15, settings.fontSize)
    assertTrue("the good write replaced the broken file", file.isFile)
    assertEquals(15, EditorSettingsStore(context).get().fontSize)
  }

  @Test
  fun `a store with no application still behaves in memory`() {
    val store = EditorSettingsStore(null)
    assertEquals(EditorSettings(), store.get())

    store.update { it.copy(wordWrap = true) }
    assertTrue(store.get().wordWrap)
    assertEquals("nothing was written anywhere", false, file.isFile)
  }
}
