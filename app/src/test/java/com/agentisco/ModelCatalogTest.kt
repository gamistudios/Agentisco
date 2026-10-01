package com.agentisco

import com.agentisco.agent.llm.ModelCatalog
import com.agentisco.agent.llm.ModelFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A provider's model listing is the only trustworthy source for which models a
 * key can actually call, and the parsed rows are what prefills the model form.
 * Each protocol publishes a different subset, so the rules under test are which
 * fields are read, which entries are dropped, and what happens when a provider
 * publishes nothing but names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCatalogTest {

  @Test
  fun `Anthropic listing carries only names so the family table supplies the limits`() {
    // This is verbatim what /v1/models returns: no context window, no output ceiling.
    val models = ModelCatalog.anthropic(
      """{"data":[
        {"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5","created_at":"2025-09-29T01:00:00Z"},
        {"type":"model","id":"claude-opus-4-1","display_name":"Claude Opus 4.1"}
      ]}"""
    )

    assertEquals(2, models.size)
    val sonnet = models[0]
    assertEquals("claude-sonnet-4-5", sonnet.modelId)
    assertEquals("Claude Sonnet 4.5", sonnet.displayName)
    assertEquals(200_000, sonnet.contextWindow)
    assertEquals(64_000, sonnet.maxOutputTokens)
    assertTrue(sonnet.tools == true)
    // Opus 4.1 writes half of what Sonnet 4.5 may, and a shared 64k default would
    // be rejected by the API for it.
    assertEquals(32_000, models[1].maxOutputTokens)
  }

  @Test
  fun `OpenAI compatible listing keeps the router's own numbers`() {
    val models = ModelCatalog.openAiCompatible(
      """{"object":"list","data":[
        {"id":"qwen/qwen3-coder","context_length":131072,"max_tokens":32768,
         "supported_parameters":["tools","temperature"],"architecture":{"input_modalities":["text"]}},
        {"id":"moonshot/kimi-k2","context_length":131072,
         "supported_parameters":["response_format"],"architecture":{"input_modalities":["text","image"]}}
      ]}"""
    )

    val coder = models.first { it.modelId == "qwen/qwen3-coder" }
    assertEquals("qwen3-coder", coder.displayName)
    assertEquals(131_072, coder.contextWindow)
    assertEquals(32_768, coder.maxOutputTokens)
    assertTrue(coder.tools == true)
    assertFalse(coder.images == true)

    // "response_format" is not tools; the router says so and the table stays out.
    val kimi = models.first { it.modelId == "moonshot/kimi-k2" }
    assertNull(kimi.maxOutputTokens)
    assertFalse(kimi.tools == true)
    assertTrue(kimi.images == true)
  }

  @Test
  fun `OpenAI listing that omits limits still gets a safe pair for a known family`() {
    val models = ModelCatalog.openAiCompatible("""{"data":[{"id":"gpt-4o"}]}""")
    val model = models.single()
    assertEquals(128_000, model.contextWindow)
    assertEquals(16_384, model.maxOutputTokens)
  }

  @Test
  fun `Gemini listing strips the resource prefix and drops models that cannot chat`() {
    val models = ModelCatalog.gemini(
      """{"models":[
        {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","inputTokenLimit":1048576,
         "outputTokenLimit":65536,"supportedGenerationMethods":["generateContent","streamGenerateContent"]},
        {"name":"models/text-embedding-004","displayName":"Text Embedding 004","inputTokenLimit":2048,
         "supportedGenerationMethods":["embedContent"]},
        {"name":"models/gemini-2.5-flash","inputTokenLimit":1048576,"outputTokenLimit":65536,
         "supportedGenerationMethods":["generateContent"]}
      ]}"""
    )

    assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models.map { it.modelId })
    val pro = models.first()
    // Google publishes real ceilings, so they are taken as-is rather than guessed.
    assertEquals(1_048_576, pro.contextWindow)
    assertEquals(65_536, pro.maxOutputTokens)
    // No displayName: the id is better than an empty field in the picker.
    assertEquals("gemini-2.5-flash", models[1].displayName)
  }

  @Test
  fun `A model no family table knows keeps exactly what the provider published`() {
    val models = ModelCatalog.anthropic(
      """{"data":[{"id":"acme-private-7","display_name":"Acme Private 7","context_length":9000}]}"""
    )
    val model = models.single()
    assertNull(ModelFacts.lookup("acme-private-7"))
    // An unknown family has nothing filled in, so the form leaves it blank.
    assertNull(model.contextWindow)
    assertNull(model.maxOutputTokens)
    assertNull(model.tools)
    assertNull(model.images)
    assertEquals("Acme Private 7", model.displayName)
  }

  @Test
  fun `Zero and missing limits are treated as unknown, never as a real ceiling`() {
    val models = ModelCatalog.openAiCompatible(
      """{"data":[{"id":"gpt-5","context_length":0,"max_tokens":null},{"id":""}]}"""
    )
    val gpt5 = models.single()
    // 0 would ask the API for a zero-token answer; the family table's numbers win.
    assertEquals(400_000, gpt5.contextWindow)
    assertEquals(128_000, gpt5.maxOutputTokens)
  }

  @Test
  fun `A body that is not a listing yields no models instead of failing`() {
    assertTrue(ModelCatalog.anthropic("not json at all").isEmpty())
    assertTrue(ModelCatalog.anthropic("""{"error":{"type":"authentication_error"}}""").isEmpty())
    assertTrue(ModelCatalog.gemini("""{}""").isEmpty())
    assertTrue(ModelCatalog.openAiCompatible("""{"data":[]}""").isEmpty())
  }

  @Test
  fun `Longest family prefix wins so a generation is not read as its series`() {
    // The 4-1 releases differ by tens of thousands of output tokens; resolving
    // either by the shared "claude-*-4" prefix would send the wrong one.
    assertEquals(32_000, ModelFacts.lookup("claude-opus-4-1")?.maxOutputTokens)
    assertEquals(64_000, ModelFacts.lookup("claude-sonnet-4-5")?.maxOutputTokens)
    assertEquals(8_192, ModelFacts.lookup("claude-3-5-haiku-20241022")?.maxOutputTokens)
    // An undated Claude still gets the conservative floor, not a bluff.
    assertEquals(8_192, ModelFacts.lookup("claude-some-new-name")?.maxOutputTokens)
    assertEquals(200_000, ModelFacts.lookup("claude-some-new-name")?.contextWindow)
    assertNull(ModelFacts.lookup("llama-3.3-70b"))
  }
}
