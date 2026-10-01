package com.agentisco

import com.agentisco.local.HuggingFaceAssetSource
import com.agentisco.local.LocalModelCatalog
import com.agentisco.local.RemoteAssetInfo
import com.agentisco.local.model.LocalGenerationSettings
import com.agentisco.local.model.LocalRuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for what the app believes about a model it has not downloaded yet:
 * the built-in catalog entry, and the URL shape that makes a remote size and
 * checksum readable at all.
 */
class LocalModelCatalogTest {

    // ——— resolve URLs: the gate on reading a source's claims ———

    @Test
    fun `a hugging face resolve link splits into repo revision and path`() {
        val parsed = HuggingFaceAssetSource.parseResolveUrl(
            "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_0.gguf"
        )
        assertNotNull(parsed)
        assertEquals("LiquidAI/LFM2.5-230M-GGUF", parsed!!.first)
        assertEquals("main", parsed.second)
        assertEquals("LFM2.5-230M-Q4_0.gguf", parsed.third)
    }

    @Test
    fun `a path inside the repository is kept whole`() {
        val parsed = HuggingFaceAssetSource.parseResolveUrl(
            "https://huggingface.co/org/model/resolve/refs%2Fpr%2F7/quant/Q4_K_M/model.gguf"
        )
        assertNotNull(parsed)
        assertEquals("refs%2Fpr%2F7", parsed!!.second)
        assertEquals("quant/Q4_K_M/model.gguf", parsed.third)
    }

    @Test
    fun `a host that only ends with huggingface co is refused`() {
        // This URL decides which server the checksum is fetched from, so a
        // lookalike host has to be refused rather than trusted.
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co.evil.example/org/model/resolve/main/x.gguf"))
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://evil.example/huggingface.co/org/model/resolve/main/x.gguf"))
    }

    @Test
    fun `plain http is refused`() {
        assertNull(HuggingFaceAssetSource.parseResolveUrl("http://huggingface.co/org/model/resolve/main/x.gguf"))
    }

    @Test
    fun `a model page is not an asset link`() {
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF"))
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/org/model/tree/main/x.gguf"))
    }

    @Test
    fun `a resolve link missing its revision or file is refused`() {
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/org/model/resolve/main"))
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/org/model/resolve//x.gguf"))
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/model/resolve/main/x.gguf"))
    }

    @Test
    fun `garbage is refused instead of throwing`() {
        assertNull(HuggingFaceAssetSource.parseResolveUrl("not a url at all"))
        assertNull(HuggingFaceAssetSource.parseResolveUrl(""))
        assertNull(HuggingFaceAssetSource.parseResolveUrl("https://huggingface.co/%%invalid%%/resolve/main/x"))
    }

    // ——— the built-in entry ———

    @Test
    fun `the shipped catalog has one downloadable model`() {
        val models = LocalModelCatalog.builtIn
        assertEquals(1, models.size)
        val model = models.first()
        assertTrue("built-in model must be marked as such", model.builtIn)
        assertEquals("Q4_0", model.quantization)
        assertNotNull("the built-in download URL must be resolvable for a checksum",
            HuggingFaceAssetSource.parseResolveUrl(model.downloadUrl))
    }

    @Test
    fun `the built-in model records the size and digest the source publishes`() {
        val model = LocalModelCatalog.lfm2_5_230m_q4_0
        // 149,080,928 bytes: what the repository reports for this exact asset.
        assertEquals(149_080_928L, model.sizeBytes)
        assertEquals(64, model.checksum?.length)
        assertTrue(model.checksum!!.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ——— refreshing from the source ———

    @Test
    fun `a source with nothing to say leaves the record alone`() {
        val model = LocalModelCatalog.lfm2_5_230m_q4_0
        assertEquals(model, LocalModelCatalog.refresh(model, null))
        val kept = LocalModelCatalog.refresh(model, RemoteAssetInfo(sizeBytes = 0L, checksum = null))
        assertEquals(model.sizeBytes, kept.sizeBytes)
        assertEquals(model.checksum, kept.checksum)
    }

    @Test
    fun `fresh numbers from the source replace the offline fallback`() {
        val model = LocalModelCatalog.lfm2_5_230m_q4_0
        val refreshed = LocalModelCatalog.refresh(
            model,
            RemoteAssetInfo(sizeBytes = 150_000_000L, checksum = "ab".repeat(32), version = "blobid1")
        )
        assertEquals(150_000_000L, refreshed.sizeBytes)
        assertEquals("ab".repeat(32), refreshed.checksum)
        assertEquals("blobid1", refreshed.version)
        // The record the user knows is untouched, so an update is visible as a difference.
        assertEquals(149_080_928L, model.sizeBytes)
    }

    // ——— the defaults the product asks for ———

    @Test
    fun `generation defaults are the values the spec pins`() {
        val defaults = LocalGenerationSettings.Defaults
        assertEquals(200, defaults.maxOutputTokens)
        assertEquals(0.1, defaults.temperature, 0.0)
        assertEquals(50, defaults.topK)
        assertEquals(1.05, defaults.repeatPenalty, 0.0)
    }

    @Test
    fun `runtime defaults leave the CPU thread count to the device`() {
        val defaults = LocalRuntimeSettings.Defaults
        assertEquals(2048, defaults.contextSize)
        assertEquals(0, defaults.threadCount)
    }
}
