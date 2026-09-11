package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("ScoOS", appName)
  }

  @Test
  fun `workspace view model initial state`() {
    val viewModel = com.example.ui.WorkspaceViewModel()
    assertEquals("ScoSpace", viewModel.activeProject.value.name)
    assertEquals(com.example.data.model.AppDestination.AGENT, viewModel.currentDestination.value)
    assertEquals("GLM 5.3 Free", viewModel.selectedModel.value.name)
  }
}
