package com.awaki

import com.awaki.local.LocalModelPaths
import com.awaki.local.LocalModelUpgrade
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The upgrade a device that ran the Python runtime has to survive.
 *
 * An older build unpacked 2,814 files beside the weights and this build reads none of them, so
 * the space leaves only if this code takes it — and the one thing that must not leave with it is
 * the model the user installed.
 */
class LocalModelUpgradeTest {

  @get:Rule val root = TemporaryFolder()

  /** A device's private files dir, with the model directory an older build filled. */
  private fun filesDir(): File {
    val files = root.newFolder("files")
    val models = LocalModelPaths.hostDir(files).apply { mkdirs() }
    val runtime = File(models, LocalModelPaths.LEGACY_RUNTIME_DIR_NAME)
    File(runtime, "bin").mkdirs()
    File(runtime, "bin/python").writeText("a#!/usr/bin/env python".repeat(10))
    File(runtime, "lib").mkdirs()
    File(runtime, "lib/libgomp.so.1").writeText("x".repeat(100))
    File(models, LocalModelPaths.LEGACY_VENV_DIR_NAME).mkdirs()
    File(models, "${LocalModelPaths.LEGACY_VENV_DIR_NAME}/pyvenv.cfg").writeText("home = /runtime")
    File(models, LocalModelPaths.LEGACY_SERVER_SCRIPT_NAME).writeText("print('serving')")
    File(models, LocalModelPaths.LEGACY_READY_MARKER_NAME).writeText("0".repeat(64))
    return files
  }

  @Test
  fun `what the Python build left behind is gone, and its size is reported`() {
    val files = filesDir()
    val expected = LocalModelUpgrade.legacyPaths(files).sumOf { path ->
      if (path.isDirectory) path.walkTopDown().filter { it.isFile }.sumOf { it.length() } else path.length()
    }

    assertEquals(expected, LocalModelUpgrade.reclaimLegacyRuntime(files))
    assertTrue("the interpreter's own tree is the expensive half", expected > 200)
    LocalModelUpgrade.legacyPaths(files).forEach { assertFalse("${it.name} survived", it.exists()) }
  }

  /** Deleting the leftovers is only safe while the weights next to them are not leftovers. */
  @Test
  fun `an installed model is not a leftover`() {
    val files = filesDir()
    val models = LocalModelPaths.hostDir(files)
    val weights = File(models, "LFM2.5-230M-Q4_0.gguf").apply { writeText("GGUF") }

    LocalModelUpgrade.reclaimLegacyRuntime(files)

    assertTrue("the model the user downloaded is still there", weights.exists())
    assertEquals(4L, weights.length())
  }

  /** Runs on every launch, so a device with nothing to clean must not pay for the walk. */
  @Test
  fun `a build that never shipped a Python runtime frees nothing`() {
    val files = root.newFolder("fresh")
    assertEquals(0L, LocalModelUpgrade.reclaimLegacyRuntime(files))
    assertFalse("cleaning up does not create the directory it cleans", LocalModelPaths.hostDir(files).exists())
  }

  /** A second launch has nothing left to take, and must not report a reclaim the device never got. */
  @Test
  fun `the reclaim happens once`() {
    val files = filesDir()
    assertTrue(LocalModelUpgrade.reclaimLegacyRuntime(files) > 0)
    assertEquals(0L, LocalModelUpgrade.reclaimLegacyRuntime(files))
  }
}
