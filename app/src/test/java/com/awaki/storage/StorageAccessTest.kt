package com.awaki.storage

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.settings.store.UserPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two storage-access regimes Android offers an app at targetSdk 28: the
 * "All files access" special toggle from Android 11, the legacy runtime
 * permissions below it. Getting these wrong is what made file access either
 * silently fail or nag the user on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class StorageAccessTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `modern android decides by the all-files manager flag alone`() {
    assertFalse(StorageAccess.decide(modern = true, isAllFilesManager = { false }, legacyPermissions = emptyList()))
    assertTrue(StorageAccess.decide(modern = true, isAllFilesManager = { true }, legacyPermissions = listOf("junk")))
  }

  @Test
  fun `legacy android decides by both runtime permissions, never the manager flag`() {
    assertFalse(
      StorageAccess.decide(
        modern = false,
        isAllFilesManager = { true },
        legacyPermissions = listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      )
    )
    assertTrue(
      StorageAccess.decide(
        modern = false,
        isAllFilesManager = { false },
        legacyPermissions = emptyList()
      )
    )
  }

  @Test
  @Config(sdk = [24]) // minSdk: the legacy branch runs from 24 up to Android 10.
  fun `legacy device reports exactly the permissions still missing`() {
    // The permission shadow lives on the ContextWrapper of the app context.
    val shadow = shadowOf(context as android.content.ContextWrapper)
    shadow.denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    assertFalse(StorageAccess.hasAccess(context))

    shadow.grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
    assertFalse("read alone cannot write a project back", StorageAccess.hasAccess(context))
    assertEquals(
      listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
      StorageAccess.missingLegacyPermissions(context).toList()
    )

    shadow.grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    assertTrue(StorageAccess.hasAccess(context))
    assertTrue(StorageAccess.missingLegacyPermissions(context).isEmpty())
  }

  @Test
  @Config(sdk = [34])
  fun `a refused ask survives restarts so the settings page opens once`() {
    val store = UserPreferencesStore(context)
    store.updatePreferences { it.copy(storageAskedAt = 123L) }
    // A fresh store reads the same file the first one wrote, like a process restart.
    assertEquals(123L, UserPreferencesStore(context).preferences.value.storageAskedAt)
    store.updatePreferences { it.copy(storageAskedAt = 0L) }
  }
}
