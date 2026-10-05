package com.awaki

import com.awaki.agent.llm.CatalogModel
import com.awaki.ui.components.catalogSuggestions
import com.awaki.ui.components.ModelFormFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model form offers the provider's own listing so an id is picked instead of
 * typed and its limits arrive instead of guessed. Two rules make that safe: the
 * suggestion list narrows without ever hiding the option to name a model by
 * hand, and picking a row writes only what that row actually states.
 */
class ModelFormCatalogTest {

  private companion object {
    val SONNET = CatalogModel("claude-sonnet-4-5", "Claude Sonnet 4.5", 200_000, 64_000, tools = true, images = true)
    val OPUS = CatalogModel("claude-opus-4-1", "Claude Opus 4.1", 200_000, 32_000, tools = true, images = true)
    val LISTING = listOf(SONNET, OPUS)

    fun blank() = ModelFormFields("", "", "", "", tools = true, images = false)
  }

  @Test
  fun `a partial id narrows the listing by either name the user may remember`() {
    assertEquals(listOf(OPUS), catalogSuggestions(LISTING, "opus"))
    // The friendly name matches too, however the keyboard cased it.
    assertEquals(listOf(SONNET), catalogSuggestions(LISTING, "sonnet 4.5"))
    assertEquals(listOf(SONNET), catalogSuggestions(LISTING, "SONNET"))
    assertEquals(2, catalogSuggestions(LISTING, "claude").size)
  }

  @Test
  fun `an empty or unmatched query suggests nothing`() {
    assertTrue(catalogSuggestions(LISTING, "").isEmpty())
    assertTrue(catalogSuggestions(LISTING, "   ").isEmpty())
    // No match is not an error: the form still accepts the typed id as-is.
    assertTrue(catalogSuggestions(LISTING, "mythos-9").isEmpty())
    assertTrue(catalogSuggestions(emptyList(), "claude").isEmpty())
  }

  @Test
  fun `a router with hundreds of models is offered a screenful at a time`() {
    val huge = (1..300).map { CatalogModel("model-$it", "Model $it") }
    val suggested = catalogSuggestions(huge, "model")

    assertEquals(8, suggested.size)
    // Sorted, so the same query always offers the same eight.
    assertEquals(suggested.map { it.modelId }.sorted(), suggested.map { it.modelId })
  }

  @Test
  fun `picking a model writes the limits the provider stated`() {
    val picked = blank().filledFrom(OPUS)

    assertEquals("claude-opus-4-1", picked.modelId)
    assertEquals("Claude Opus 4.1", picked.displayName)
    // Plain digits, because these fields are what the wire request reads.
    assertEquals(200_000, picked.contextWindow.toInt())
    assertEquals(32_000, picked.maxOutputTokens.toInt())
    assertTrue(picked.tools)
    assertTrue(picked.images)
  }

  @Test
  fun `a listing that omits a limit never blanks a number already typed`() {
    val typed = ModelFormFields("qwen-72b", "My Qwen", "131072", "8192", tools = false, images = true)
    // A gateway that lists ids and nothing else: no field here may be erased.
    val picked = typed.filledFrom(CatalogModel("qwen2.5-72b-instruct", ""))

    assertEquals("qwen2.5-72b-instruct", picked.modelId)
    assertEquals("My Qwen", picked.displayName)
    assertEquals("131072", picked.contextWindow)
    assertEquals("8192", picked.maxOutputTokens)
    assertEquals(false, picked.tools)
    assertEquals(true, picked.images)
  }

  @Test
  fun `picking an entry leaves the form valid without typing anything`() {
    val picked = blank().filledFrom(SONNET)

    assertTrue(picked.modelId.isNotBlank())
    assertTrue(picked.displayName.isNotBlank())
    assertFalse(picked.modelId == picked.displayName)
  }
}
