package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.ChatDisplayStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDisplayStoreTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `raw json display is off by default`() {
    assertFalse(ChatDisplayStore(context).get().showToolJson)
    assertFalse(ChatDisplayStore(null).get().showToolJson)
  }

  @Test
  fun `setting persists across store instances`() {
    ChatDisplayStore(context).update { it.copy(showToolJson = true) }
    assertTrue("new store must reload the persisted flag", ChatDisplayStore(context).get().showToolJson)

    ChatDisplayStore(context).update { it.copy(showToolJson = false) }
    assertFalse(ChatDisplayStore(context).get().showToolJson)
  }
}
