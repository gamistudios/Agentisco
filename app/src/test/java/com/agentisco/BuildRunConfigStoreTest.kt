package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.data.local.BuildRunConfigStore
import com.agentisco.workspace.buildrun.BuildRunConfig
import com.agentisco.workspace.buildrun.BuildRunConfigSource
import com.agentisco.workspace.buildrun.BuildStageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuildRunConfigStoreTest {

  @Before
  fun cleanStorage() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.getDir("agentisco", Context.MODE_PRIVATE), "build_run.json").delete()
  }

  @Test
  fun `config round trips through disk`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = BuildRunConfigStore(context)
    store.save(
      BuildRunConfig(
        projectPath = "/projects/demo",
        commands = mapOf(
          BuildStageKind.INSTALL to "npm install",
          BuildStageKind.RUN to "npm run dev"
        ),
        runPort = 5173,
        source = BuildRunConfigSource.AI,
        detectedCommands = mapOf(BuildStageKind.RUN to "npm run dev"),
        detectedRunPort = 5173,
        detectedSource = BuildRunConfigSource.AI,
        updatedAt = 1234L
      )
    )

    val reloaded = BuildRunConfigStore(context).get("/projects/demo")

    assertNotNull(reloaded)
    val config = reloaded!!
    assertEquals("npm install", config.commandFor(BuildStageKind.INSTALL))
    assertEquals("npm run dev", config.commandFor(BuildStageKind.RUN))
    assertEquals("", config.commandFor(BuildStageKind.BUILD))
    assertEquals(5173, config.runPort)
    assertEquals(BuildRunConfigSource.AI, config.source)
    assertEquals(BuildRunConfigSource.AI, config.detectedSource)
    assertTrue(config.isConfigured(BuildStageKind.RUN))
    assertFalse(config.isConfigured(BuildStageKind.BUILD))
  }

  @Test
  fun `store without a context works in memory`() {
    val store = BuildRunConfigStore(null)
    store.save(BuildRunConfig(projectPath = "p", commands = mapOf(BuildStageKind.BUILD to "make")))

    assertEquals("make", store.get("p")?.commandFor(BuildStageKind.BUILD))
    assertNull(store.get("other"))
  }
}
