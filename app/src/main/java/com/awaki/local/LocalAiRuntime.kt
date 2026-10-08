package com.awaki.local

import com.awaki.data.repository.LocalModelRepository
import com.awaki.local.model.LocalModel
import com.awaki.local.runtime.LoadedModelInfo
import com.awaki.local.runtime.LocalEngineDiagnostics
import com.awaki.local.runtime.LocalEngineException
import com.awaki.local.runtime.LocalInferenceEngine
import com.awaki.local.server.LocalAiApi
import com.awaki.local.server.LocalAiEndpoint
import com.awaki.local.server.LocalAiServer
import com.awaki.local.server.LocalServerException
import com.awaki.settings.model.AIModel
import com.awaki.settings.model.AIProvider
import com.awaki.settings.model.LLMProtocol
import com.awaki.settings.model.ModelCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The models on this device, shaped like the providers Awaki already knows.
 *
 * This is the seam that keeps the agent loop out of the local-runtime business: it opens
 * the loopback server, turns each installed model into an ordinary `AIModel` record and
 * hands back the base URL plus the token that reach it. From here on a phone-sized GGUF
 * file is a provider with a `127.0.0.1` address — the same selection, streaming, retry,
 * approval and tool machinery as any cloud endpoint, with no branch anywhere that asks
 * which kind of model it is talking to.
 *
 * Two things decide when the server exists. A device the engine cannot run a model on — no arm64,
 * or the native library did not load — has nothing to serve, so it never binds a port; and a device
 * with no installed model has no reason to hold one. Otherwise the server lives for as long as the
 * first model does, and the model itself stays asleep until a request actually needs it.
 */
class LocalAiRuntime(
  val repository: LocalModelRepository,
  private val engine: LocalInferenceEngine,
  private val server: LocalAiServer = LocalAiServer(LocalAiApi(engine, repository)),
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

  companion object {
    /** The single provider record every on-device model is reached through. */
    const val PROVIDER_ID = "local-ai"
    const val PROVIDER_NAME = "On-device"

    /**
     * A local model's record id. Derived from the model's own id rather than a timestamp,
     * so a conversation that picks a local model keeps working after a restart, and the
     * port the server happened to bind this run never becomes part of the selection.
     */
    fun recordId(modelId: String): String = "local:$modelId"

    fun isLocalRecord(recordId: String): Boolean = recordId.startsWith("local:")
  }

  private val scope = CoroutineScope(SupervisorJob() + dispatcher)

  private val _endpoint = MutableStateFlow<LocalAiEndpoint?>(null)

  /** Where the server answers, or null while nothing is being served. */
  val endpoint: StateFlow<LocalAiEndpoint?> = _endpoint.asStateFlow()

  /** The model sitting in memory right now, whether a turn put it there or the user did. */
  val residentModelId: StateFlow<String?> = engine.residentModelId

  private val _provider = MutableStateFlow<AIProvider?>(null)
  private val _models = MutableStateFlow<List<AIModel>>(emptyList())

  /** The provider record, present only while at least one installed model can be served. */
  val provider: StateFlow<AIProvider?> = _provider.asStateFlow()

  /** One selectable record per installed model, grouped under [provider]. */
  val models: StateFlow<List<AIModel>> = _models.asStateFlow()

  @Volatile
  private var bindFailure: String? = null

  /** Why no on-device model can answer right now, phrased for the user. */
  val unavailableReason: String?
    get() = when {
      !engine.isAvailable -> engine.unavailableReason
      bindFailure != null -> bindFailure
      repository.installedModels().isEmpty() -> "No on-device model is installed."
      else -> null
    }

  init {
    // Installing the first model opens the port; deleting the last one closes it and gives
    // the model's memory back — a listener with nothing to answer is only a risk.
    scope.launch {
      repository.models.collect { models ->
        if (models.any { it.installed }) ensureServing() else stopServing()
      }
    }
    // What a model's template can do is only read once it is in memory, and that reading is what
    // the derived record carries. Residency changes without the install list changing — a turn
    // loads a model, an Unload puts it back — so it gets its own republish, or a record goes on
    // claiming a tool-capable template for a model that has since been swapped out.
    scope.launch { engine.residentModelId.collect { publishRecords() } }
  }

  /**
   * Binds the server if it is not already live, then re-publishes the derived records, so
   * the second model installed on a running server appears without another bind.
   *
   * Returns the endpoint, or null when this build cannot run models or the port could not
   * be opened.
   */
  @Synchronized
  fun ensureServing(): LocalAiEndpoint? {
    if (_endpoint.value == null && engine.isAvailable && repository.installedModels().isNotEmpty()) {
      try {
        _endpoint.value = server.start()
        bindFailure = null
      } catch (e: LocalServerException) {
        bindFailure = e.message ?: "Could not open a loopback port for the on-device models."
      }
    }
    publishRecords()
    return _endpoint.value
  }

  @Synchronized
  fun stopServing() {
    server.stop()
    _endpoint.value = null
    publishRecords()
  }

  /** Gives the resident model's memory back; the next request loads it again. */
  suspend fun releaseModel() {
    engine.release()
    publishRecords()
  }

  /**
   * What this device and this build of the engine are, for a screen that has to explain a model
   * that never answers. Reaching the engine is the point: the answer is a measurement, not a guess
   * about whether the APK that shipped was compiled to run fast.
   */
  fun engineDiagnostics(): LocalEngineDiagnostics = engine.diagnostics()

  /**
   * Brings [modelId] into memory on purpose, rather than as the first thing a turn has to
   * wait for. Says what the file turned out to be so the caller can show a real number —
   * the context the engine allocated, not the one the catalog promised.
   *
   * The records are republished before this returns: what the template can do is now known,
   * and a Load whose own screen still says the opposite is a call that did not happen.
   */
  suspend fun loadModel(modelId: String): LoadedModelInfo {
    val model = repository.model(modelId)
      ?: throw LocalEngineException("No model called $modelId is known to this device")
    if (!model.installed) throw LocalEngineException("${model.name} is not installed")
    return engine.preload(model).also { publishRecords() }
  }

  /** Ends the decode in flight, which is what Stop has to reach on a local model. */
  fun stopGeneration() = engine.stop()

  /**
   * The provider and key for [model] when it names an on-device model, and null for
   * anything else — including a local model whose server is not live, so the caller treats
   * it as an unusable selection instead of sending a request to a closed port.
   */
  fun connectionFor(model: AIModel): Pair<AIProvider, String>? {
    if (model.providerId != PROVIDER_ID) return null
    val endpoint = _endpoint.value ?: return null
    val record = repository.model(model.modelId) ?: return null
    if (!record.installed) return null
    return providerOf(endpoint) to endpoint.apiKey
  }

  /**
   * Rebuilds the derived records. Called whenever the installation set or the endpoint
   * changes, and by whoever needs the current list without waiting on a flow.
   */
  @Synchronized
  fun publishRecords() {
    val endpoint = _endpoint.value
    _provider.value = endpoint?.let { providerOf(it) }
    _models.value = if (endpoint == null) emptyList() else repository.installedModels().map { aiModelOf(it) }
  }

  /** Stops watching the catalog and shuts the server down. */
  @Synchronized
  fun shutdown() {
    scope.cancel()
    server.stop()
    _endpoint.value = null
    _provider.value = null
    _models.value = emptyList()
  }

  private fun providerOf(endpoint: LocalAiEndpoint) = AIProvider(
    id = PROVIDER_ID,
    name = PROVIDER_NAME,
    baseUrl = endpoint.baseUrl,
    protocol = LLMProtocol.OPENAI_CHAT_COMPLETIONS,
    hasApiKey = true
  )

  /**
   * A local model as the provider layer sees it. Its limits are the numbers the user
   * configured, because those are what the engine will actually allocate.
   *
   * What a template can do is only known once the model is resident, and loading one just
   * to fill in a capability flag costs seconds and memory. So an unloaded model is listed
   * as tool-capable and [LocalAiApi] refuses a tool request the template truly cannot
   * serve, rather than dropping the tools and answering as if it had seen them.
   */
  private fun aiModelOf(model: LocalModel): AIModel {
    val caps = engine.loadedCapabilities().takeIf { engine.loadedModelId == model.id }
    return AIModel(
      id = recordId(model.id),
      providerId = PROVIDER_ID,
      modelId = model.id,
      displayName = model.name + if (model.quantization.isBlank()) "" else " (${model.quantization})",
      contextWindow = model.configuration.runtime.contextSize,
      maxOutputTokens = model.configuration.generation.maxOutputTokens,
      allowedToolNames = model.configuration.toolsOrDefault(),
      systemInstruction = model.configuration.systemInstruction?.takeIf { it.isNotBlank() },
      capabilities = ModelCapabilities(
        tools = caps?.supportsTools ?: true,
        images = false,
        parallelToolCalls = caps?.supportsParallelToolCalls ?: false,
        maxTokensParameter = true,
        streaming = true
      )
    )
  }
}
