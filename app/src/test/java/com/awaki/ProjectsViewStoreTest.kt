package com.awaki

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.awaki.data.local.ProjectsViewStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectsViewStoreTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun `grid layout is the default`() {
    assertTrue(ProjectsViewStore(context).get().gridView)
    assertTrue(ProjectsViewStore(null).get().gridView)
  }

  @Test
  fun `setting persists across store instances`() {
    ProjectsViewStore(context).update { it.copy(gridView = false) }
    assertFalse("new store must reload the persisted flag", ProjectsViewStore(context).get().gridView)

    ProjectsViewStore(context).update { it.copy(gridView = true) }
    assertTrue(ProjectsViewStore(context).get().gridView)
  }
}
