package com.agentisco

import android.app.Application
import com.agentisco.data.local.ProviderConfigStore

class AgentiscoApplication : Application() {
  /** Durable provider/model configuration + credential storage, created once per process. */
  val providerStore: ProviderConfigStore by lazy { ProviderConfigStore(this) }
}
