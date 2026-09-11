package com.agentisco.settings.model

data class AIModel(
  val id: String,
  val name: String,
  val providerId: String,
  val contextWindow: String = "128k",
  val hasTools: Boolean = true,
  val hasStreaming: Boolean = true,
  val hasVision: Boolean = false,
  val hasReasoning: Boolean = false,
  val isFree: Boolean = false
)

data class AIProvider(
  val id: String,
  val name: String,
  val baseUrl: String,
  val apiKey: String,
  val isConnected: Boolean,
  val models: List<AIModel>
)
