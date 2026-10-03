package com.agentisco.local.py

import com.agentisco.local.LocalModelPaths
import com.agentisco.local.runtime.LoadedLocalModel
import com.agentisco.local.runtime.LoadedModelInfo
import com.agentisco.local.runtime.LocalAnswerDelta
import com.agentisco.local.runtime.LocalChatRequest
import com.agentisco.local.runtime.LocalEngineException
import com.agentisco.local.runtime.LocalFinishReason
import com.agentisco.local.runtime.LocalModelEngine
import com.agentisco.local.runtime.LocalTemplateCapabilities
import com.agentisco.local.model.LocalRuntimeSettings
import java.io.File

/**
 * The production runtime: [LocalModelEngine] over the model server the Python environment runs.
 *
 * This is the whole of it — start the guest process if it is not up, hand it the file, and carry
 * back what it says the file can do. Rendering a conversation with the model's own chat template
 * and reading the answer back into prose, thinking and tool calls happen in `serve.py`, next door
 * to the GGUF whose template those rules come from, so a model with a dialect nobody predicted
 * is llama-cpp-python's problem rather than a reason to ship a new app build.
 *
 * The one translation done here is the path: the app writes models into its private files
 * directory and the guest reads the same bytes at [LocalModelPaths.GUEST_DIR], because that
 * directory is bound into the rootfs rather than copied.
 */
class PythonEngine(
  private val filesDir: File,
  private val server: PythonModelServer,
  /** Whether the guest can run a model: the venv is on disk and verified in this process. */
  private val environmentReady: () -> Boolean = { true },
  /** What to tell the user when it cannot, phrased for a screen rather than the log. */
  private val notReadyReason: () -> String = { NO_ENVIRONMENT }
) : LocalModelEngine {

  companion object {
    const val NO_ENVIRONMENT =
      "The Python environment the models run in is not set up yet. Run Setup environment on the " +
        "on-device models screen; compiling the runtime on the phone takes a few minutes."
  }

  override val isAvailable: Boolean get() = environmentReady()

  override val unavailableReason: String get() = if (environmentReady()) "" else notReadyReason()

  /**
   * The cores this device has. The guest shares the kernel, so the count the app sees is the
   * count the model decodes on; a phone that reports none has not told anyone the truth, and
   * one core is what the runtime was left with.
   */
  override fun systemThreads(): Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

  override fun load(path: String, runtime: LocalRuntimeSettings): LoadedLocalModel {
    if (!environmentReady()) throw LocalEngineException(notReadyReason())
    val client = server.ensureRunning()
    // "use every core" is resolved here rather than left to the library, whose default is a
    // small fixed number: on a phone the difference between four threads and eight is a turn
    // that finishes before the user gives up on it.
    val threads = runtime.threadCount.takeIf { it > 0 } ?: systemThreads()
    val info = client.load(LocalModelPaths.guestPath(File(path)), runtime.copy(threadCount = threads))
    return Session(client, info)
  }

  override fun shutdown() = server.stop()

  /**
   * The resident model, as seen through the server that holds it.
   *
   * [close] unloads the weights and leaves the process running: a start costs an interpreter
   * import, and a user who switches models twice should not pay it twice.
   */
  private class Session(
    private val client: PythonModelClient,
    private val loaded: PythonModelClient.ModelInfo
  ) : LoadedLocalModel {

    override val info = LoadedModelInfo(
      publishedName = loaded.publishedName,
      architecture = loaded.architecture,
      vocabSize = loaded.vocabularySize,
      contextSize = loaded.contextSize
    )

    private val caps = loaded.capabilities

    override fun capabilities(): LocalTemplateCapabilities = caps

    override fun chat(request: LocalChatRequest, onDelta: (LocalAnswerDelta) -> Boolean): LocalFinishReason =
      client.chat(request, onDelta)

    override fun abort() = client.abort()

    override fun close() = client.unload()
  }
}
