package com.agentisco

import com.agentisco.workspace.buildrun.BuildRecipeDetector
import com.agentisco.workspace.buildrun.BuildStageKind
import org.junit.After
import org.junit.Assert.assertEquals
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
class BuildRecipeDetectorTest {

  private lateinit var dir: File

  @Before
  fun setUp() {
    dir = File.createTempFile("recipe", "-dir").let { file ->
      file.delete()
      file.mkdirs()
      file
    }
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun `pnpm project maps scripts and vite port`() {
    File(dir, "package.json").writeText(
      """{"scripts":{"dev":"vite","build":"vite build","test":"vitest run"},"devDependencies":{"vite":"^5.0.0"}}"""
    )
    File(dir, "pnpm-lock.yaml").writeText("")

    val suggestion = BuildRecipeDetector.detect(dir)

    assertEquals("pnpm install", suggestion.commands[BuildStageKind.INSTALL])
    assertEquals("pnpm run build", suggestion.commands[BuildStageKind.BUILD])
    assertEquals("pnpm test", suggestion.commands[BuildStageKind.TEST])
    assertEquals("pnpm run dev", suggestion.commands[BuildStageKind.RUN])
    assertEquals(5173, suggestion.runPort)
  }

  @Test
  fun `npm project without a framework keeps a null port`() {
    File(dir, "package.json").writeText("""{"scripts":{"start":"node server.js"}}""")

    val suggestion = BuildRecipeDetector.detect(dir)

    assertEquals("npm install", suggestion.commands[BuildStageKind.INSTALL])
    assertEquals("npm run start", suggestion.commands[BuildStageKind.RUN])
    assertEquals("", suggestion.commands[BuildStageKind.BUILD])
    assertNull(suggestion.runPort)
  }

  @Test
  fun `static html site proposes an http server`() {
    File(dir, "index.html").writeText("<html></html>")

    val suggestion = BuildRecipeDetector.detect(dir)

    assertEquals("python3 -m http.server 8000", suggestion.commands[BuildStageKind.RUN])
    assertEquals(8000, suggestion.runPort)
  }

  @Test
  fun `gradle project uses the wrapper`() {
    File(dir, "gradlew").writeText("")
    File(dir, "build.gradle.kts").writeText("")

    val suggestion = BuildRecipeDetector.detect(dir)

    assertEquals("./gradlew assembleDebug", suggestion.commands[BuildStageKind.BUILD])
    assertEquals("./gradlew test", suggestion.commands[BuildStageKind.TEST])
  }

  @Test
  fun `unknown project has no commands`() {
    val suggestion = BuildRecipeDetector.detect(dir)
    assertTrue(suggestion.commands.values.all { it.isBlank() })
  }

  @Test
  fun `ai response parsing tolerates fences and prose`() {
    val raw = """
      Here you go:
      ```json
      {"install":"npm ci","build":"npm run build","test":"","run":"npm run dev","port":3000,"summary":"Node app"}
      ```
    """.trimIndent()

    val suggestion = BuildRecipeDetector.parseAiResponse(raw)

    assertNotNull(suggestion)
    assertEquals("npm ci", suggestion!!.commands[BuildStageKind.INSTALL])
    assertEquals("npm run dev", suggestion.commands[BuildStageKind.RUN])
    assertEquals(3000, suggestion.runPort)
  }

  @Test
  fun `ai response rejects placeholders and garbage`() {
    assertNull(BuildRecipeDetector.parseAiResponse("no json at all"))
    val placeholders = BuildRecipeDetector.parseAiResponse(
      """{"install":"N/A","build":"none","test":"","run":"","port":null}"""
    )
    assertNull(placeholders)
  }
}
