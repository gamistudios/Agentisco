package com.agentisco

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentisco.local.LocalModelPaths
import com.agentisco.local.py.ServerScript
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The copy of the model server that ships with the app, and the rules for putting it on disk.
 *
 * The asset is the product here: a `serve.py` with a syntax error, or one whose protocol number
 * disagrees with the client's, fails only when a phone tries to answer a turn. So the shipped
 * file is read and checked, not a string copied into a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalServerScriptTest {

  private fun modelsDir(): File = Files.createTempDirectory("scoos-script").toFile()

  private fun asset(): ByteArray =
    ApplicationProvider.getApplicationContext<Context>().assets.open(ServerScript.ASSET_PATH)
      .use { it.readBytes() }

  @Test
  fun `the server the app ships is the one that gets installed`() {
    val target = File(modelsDir(), LocalModelPaths.SERVER_SCRIPT_NAME)
    assertTrue(ServerScript.install(::asset, target))
    assertEquals(String(asset(), Charsets.UTF_8), target.readText())
    // Installed under the name the guest command runs, which is the only path the two sides
    // share; a rename here has to happen in LocalModelPaths too.
    assertEquals("serve.py", target.name)
    assertTrue(target.readText().contains("PROTOCOL_VERSION = 2"))
  }

  @Test
  fun `the shipped script is python the interpreter will accept`() {
    val source = String(asset(), Charsets.UTF_8)
    assertTrue(source, source.startsWith("#!/usr/bin/env python3"))
    // The routes the Kotlin client calls, by name, so a rename on either side fails a test.
    listOf("/health", "/v1/models", "/v1/load", "/v1/chat/completions", "/abort", "/v1/unload").forEach {
      assertTrue("the script has no $it", source.contains("\"$it\""))
    }
    // The fields the client reads an answer out of: it splits these into prose, thinking and
    // calls, and a script that stopped writing one would silently lose part of every answer.
    listOf("\"messages\"", "\"delta\"", "\"finish_reason\"", "\"reasoning_content\"", "\"tool_calls\"").forEach {
      assertTrue("the script neither takes nor writes $it", source.contains(it))
    }
    assertTrue(source, source.contains("class PromptTooLong"))
    assertTrue(source, source.contains("exceed context window"))
  }

  @Test
  fun `an unchanged script is not written again`() {
    val target = File(modelsDir(), LocalModelPaths.SERVER_SCRIPT_NAME)
    ServerScript.install(::asset, target)
    val written = target.lastModified()
    assertFalse(ServerScript.install(::asset, target))
    assertEquals(written, target.lastModified())
  }

  @Test
  fun `a script the app replaced is written over the old one, leaving no half file`() {
    val directory = modelsDir()
    val target = File(directory, LocalModelPaths.SERVER_SCRIPT_NAME)
    target.writeText("old and broken")
    assertTrue(ServerScript.install({ "new server".toByteArray() }, target))
    assertEquals("new server", target.readText())
    // The staging file is renamed, never left behind for the next launch to trip over.
    assertEquals(listOf("serve.py"), directory.list()!!.sorted())
  }

  @Test
  fun `the model directory is created for a device that has never downloaded anything`() {
    val root = modelsDir()
    val target = File(File(root, "local-models"), LocalModelPaths.SERVER_SCRIPT_NAME)
    ServerScript.install({ "server".toByteArray() }, target)
    assertTrue(target.isFile)
  }

  @Test
  fun `an empty script is refused rather than installed`() {
    val target = File(modelsDir(), LocalModelPaths.SERVER_SCRIPT_NAME)
    val failure = runCatching { ServerScript.install({ ByteArray(0) }, target) }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertFalse("nothing was written", target.exists())
  }
}
