package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.local.UiThemeStore
import com.awaki.ui.theme.Daylight
import com.awaki.ui.theme.DefaultUiTheme
import com.awaki.ui.theme.Graphite
import com.awaki.ui.theme.uiThemeByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme file holds a key rather than colours, so what has to hold up is that a pick
 * survives the process dying, and that nobody is ever left holding a theme that does
 * not exist — a key from a newer build, or none at all, lands on the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiThemeStoreTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `a picked theme survives into a new store, which is what a restart makes`() {
    UiThemeStore(context).setKey(Graphite.key)
    assertEquals(
      "the file has to carry the pick across instances",
      Graphite,
      uiThemeByKey(UiThemeStore(context).get().orEmpty())
    )

    UiThemeStore(context).setKey(Daylight.key)
    assertEquals(Daylight, uiThemeByKey(UiThemeStore(context).get().orEmpty()))
  }

  @Test
  fun `a key this build does not know still resolves to a theme`() {
    val store = UiThemeStore(context)
    store.setKey("a theme a newer build invented")
    assertEquals(DefaultUiTheme, uiThemeByKey(store.get().orEmpty()))
    // The other half of the same promise: a device that has never picked anything.
    assertEquals(DefaultUiTheme, uiThemeByKey(""))
  }

  @Test
  fun `without a context the selection lives in memory only`() {
    val store = UiThemeStore(null)
    assertNull("nothing has been picked yet", store.get())
    store.setKey(Graphite.key)
    assertEquals(Graphite.key, store.get())
    // The memory belongs to the instance, so a preview host never reaches another's
    // pick — and nothing here touched a device's preferences directory either.
    assertNull(UiThemeStore(null).get())
  }
}
