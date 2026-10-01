package com.agentisco

import com.agentisco.local.model.GgufInspection
import com.agentisco.local.model.GgufInspector
import com.agentisco.local.model.GgufMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Unit tests for reading a GGUF header well enough to decide whether the bytes
 * may become a selectable model. The writer below mirrors the format itself
 * (magic, u32 version, i64 counts, then key/value pairs where every enum is
 * stored as int32), because a hand-built file is the only way to exercise the
 * hostile headers an arbitrary URL can point at.
 */
class GgufInspectorTest {

    private fun write(bytes: ByteArray): File {
        val file = File.createTempFile("model-", ".gguf")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun reasonOf(file: File): String =
        (GgufInspector.inspect(file) as GgufInspection.Invalid).reason

    private fun metadataOf(file: File): GgufMetadata =
        (GgufInspector.inspect(file) as GgufInspection.Valid).metadata

    /** little-endian, exactly as GGUF packs its header */
    private class Writer {
        val out = ByteArrayOutputStream()

        fun u32(value: Long) = apply { le(value, 4) }
        fun u64(value: Long) = apply { le(value, 8) }
        fun i32(value: Int) = apply { le(value.toLong(), 4) }
        fun raw(value: ByteArray) = apply { out.write(value) }

        /** strings are a u64 byte count followed by the UTF-8 bytes */
        fun str(value: String) = apply {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        fun string(key: String, value: String) = apply { str(key); u32(TYPE_STRING); str(value) }
        fun u32Value(key: String, value: Long) = apply { str(key); u32(TYPE_UINT32); u32(value) }

        /** array of strings: the shape a real vocabulary has */
        fun stringArray(key: String, values: List<String>) = apply {
            str(key); u32(TYPE_ARRAY); u32(TYPE_STRING); u64(values.size.toLong())
            values.forEach { str(it) }
        }

        fun type(value: Long) = apply { u32(value.toLong()) }

        fun bytes(): ByteArray = out.toByteArray()

        private fun le(value: Long, width: Int) {
            var remaining = value
            repeat(width) {
                out.write((remaining and 0xFF).toInt())
                remaining = remaining ushr 8
            }
        }

        companion object {
            const val TYPE_UINT32 = 4L
            const val TYPE_INT32 = 5L
            const val TYPE_STRING = 8L
            const val TYPE_ARRAY = 9L
        }
    }

    private fun header(writer: Writer, version: Long = 3, tensors: Long = 1, metadata: Long = 0): Writer {
        writer.raw("GGUF".toByteArray(StandardCharsets.UTF_8)).u32(version)
        // Version 1 is the one layout difference worth testing: its counts are 32-bit.
        return if (version == 1L) writer.u32(tensors).u32(metadata) else writer.u64(tensors).u64(metadata)
    }

    private fun validFile(): File = write(
        header(Writer(), metadata = 3)
            .string("general.architecture", "lfm2")
            .string("general.name", "LFM2.5-230M")
            .u32Value("general.file_type", 2)
            .bytes()
    )

    // ——— a well-formed model ———

    @Test
    fun `reads architecture name and quantization`() {
        val metadata = metadataOf(validFile())
        assertEquals("lfm2", metadata.architecture)
        assertEquals("LFM2.5-230M", metadata.publishedName)
        assertEquals(2L, metadata.fileType)
        assertEquals(3L, metadata.metadataCount)
        assertEquals(1L, metadata.tensorCount)
        assertEquals(3L, metadata.formatVersion)
    }

    @Test
    fun `reads the context length the model was trained with`() {
        val bytes = header(Writer(), metadata = 3)
            .string("general.architecture", "lfm2")
            .u32Value("lfm2.context_length", 8192)
            .u32Value("general.file_type", 2)
            .bytes()
        assertEquals(8192L, metadataOf(write(bytes)).contextLength)
    }

    @Test
    fun `a file that never states a context length claims none`() {
        assertEquals(null, metadataOf(validFile()).contextLength)
    }

    @Test
    fun `file type maps to the quantization label the screen shows`() {        assertEquals("Q4_0", GgufMetadata.fileTypeLabel(2))
        assertEquals("Q8_0", GgufMetadata.fileTypeLabel(7))
        assertEquals("Q4_K_M", GgufMetadata.fileTypeLabel(15))
        assertEquals(null, GgufMetadata.fileTypeLabel(null))
        assertEquals(null, GgufMetadata.fileTypeLabel(999))
    }

    @Test
    fun `skips a string array and keeps reading the keys after it`() {
        val bytes = header(Writer(), metadata = 3)
            .stringArray("tokenizer.ggml.tokens", listOf("a", "bb", "ccc"))
            .string("general.architecture", "llama")
            .u32Value("general.file_type", 9)
            .bytes()
        val metadata = metadataOf(write(bytes))
        assertEquals("llama", metadata.architecture)
        assertEquals(9L, metadata.fileType)
    }

    @Test
    fun `version one headers use 32-bit counts`() {
        val bytes = header(Writer(), version = 1, tensors = 4, metadata = 1)
            .string("general.architecture", "gpt2")
            .bytes()
        val metadata = metadataOf(write(bytes))
        assertEquals(1L, metadata.formatVersion)
        assertEquals(4L, metadata.tensorCount)
        assertEquals("gpt2", metadata.architecture)
    }

    @Test
    fun `signature check recognises a gguf and refuses an apk`() {
        assertTrue(GgufInspector.looksLikeGguf(validFile()))
        val apk = write("PK\u0003\u0004-extra".toByteArray(StandardCharsets.ISO_8859_1))
        assertFalse(GgufInspector.looksLikeGguf(apk))
    }

    // ——— files that must never become selectable ———

    @Test
    fun `a file that is not GGUF is rejected by its header`() {
        val bytes = "PK\u0003\u0004lfh0".toByteArray(StandardCharsets.ISO_8859_1)
        assertTrue(reasonOf(write(bytes)).contains("not a GGUF model"))
    }

    @Test
    fun `a file shorter than the magic is rejected`() {
        assertTrue(reasonOf(write("GG".toByteArray(StandardCharsets.UTF_8))).contains("too small"))
    }

    @Test
    fun `a missing file is reported as missing`() {
        val absent = File(System.getProperty("java.io.tmpdir"), "no-such-model-${System.nanoTime()}.gguf")
        assertTrue(reasonOf(absent).contains("missing"))
    }

    @Test
    fun `metadata cut short of the declared pair count is truncated`() {
        val bytes = header(Writer(), metadata = 5)
            .string("general.architecture", "lfm2")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("truncated"))
    }

    @Test
    fun `an unknown format version is rejected`() {
        val bytes = header(Writer(), version = 99, metadata = 1)
            .string("general.architecture", "lfm2")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("version"))
    }

    @Test
    fun `a model with no tensors is rejected`() {
        val bytes = header(Writer(), tensors = 0, metadata = 1)
            .string("general.architecture", "lfm2")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("no tensors"))
    }

    @Test
    fun `metadata without an architecture cannot be run`() {
        val bytes = header(Writer(), metadata = 1)
            .string("general.name", "Mystery")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("architecture"))
    }

    @Test
    fun `a blank architecture is not an architecture`() {
        val bytes = header(Writer(), metadata = 1)
            .string("general.architecture", "")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("architecture"))
    }

    // ——— hostile headers: an untrusted URL must not be able to allocate ———

    @Test
    fun `a string claiming more bytes than exist is refused`() {
        val bytes = header(Writer(), metadata = 1)
            .str("general.architecture")
            .type(Writer.TYPE_STRING)
            .u64(4_000_000_000L)
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("impossible string length"))
    }

    @Test
    fun `a length too large to be a signed count is refused`() {
        val bytes = header(Writer(), metadata = 1)
            .str("general.architecture")
            .type(Writer.TYPE_STRING)
            .u64(-1L)
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("impossible string length"))
    }

    @Test
    fun `an absurd metadata pair count is refused before looping`() {
        assertTrue(reasonOf(write(header(Writer(), metadata = 9_000_000_000L).bytes())).contains("impossible metadata count"))
    }

    @Test
    fun `an absurd tensor count is refused`() {
        val bytes = header(Writer(), tensors = 2_000_000_000L, metadata = 1)
            .string("general.architecture", "lfm2")
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("impossible tensor count"))
    }

    @Test
    fun `a string array claiming more bytes than exist is refused`() {
        val bytes = header(Writer(), metadata = 1)
            .str("tokenizer.ggml.tokens")
            .type(Writer.TYPE_ARRAY)
            .u32(Writer.TYPE_STRING)
            .u64(3)
            .u64(1_000L)
            .raw("short".toByteArray(StandardCharsets.UTF_8))
            .bytes()
        assertTrue(reasonOf(write(bytes)).contains("truncated"))
    }

    @Test
    fun `an unknown value type is refused`() {
        val bytes = header(Writer(), metadata = 1).str("general.architecture").type(77).bytes()
        assertTrue(reasonOf(write(bytes)).contains("unknown value type"))
    }

    @Test
    fun `a nested array is walked element by element, not guessed`() {
        val bytes = header(Writer(), metadata = 2)
            .str("nested")
            .type(Writer.TYPE_ARRAY)
            .u32(Writer.TYPE_ARRAY)
            .u64(2)
            // element 0: array<string> of one
            .u32(Writer.TYPE_STRING).u64(1).str("x")
            // element 1: array<int32> of two
            .u32(Writer.TYPE_INT32).u64(2).i32(7).i32(9)
            .string("general.architecture", "lfm2")
            .bytes()
        assertEquals("lfm2", metadataOf(write(bytes)).architecture)
    }

    @Test
    fun `tensor data after the metadata is never read`() {
        // Everything the parser needs sits in the header, so trailing weight bytes
        // of any shape must not disturb it.
        val bytes = header(Writer(), tensors = 257, metadata = 1)
            .string("general.architecture", "lfm2")
            .raw(ByteArray(4096) { 0x7F })
            .bytes()
        assertEquals(257L, metadataOf(write(bytes)).tensorCount)
    }
}
