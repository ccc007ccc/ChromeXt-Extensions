package org.matrix.chromext.devtools

import android.net.LocalSocket
import org.json.JSONArray
import org.matrix.chromext.utils.Log

fun getInspectPages(): JSONArray? {
  return runCatching { JSONArray(devToolsGet("/json")) }.onFailure { Log.ex(it) }.getOrNull()
}

fun hitDevTools(): LocalSocket {
  val client = LocalSocket()
  connectDevTools(client)
  client.outputStream.write("GET /json HTTP/1.1\r\n\r\n".toByteArray())
  return client
}

object DevSessions {
  private val clients = mutableSetOf<DevToolClient>()

  fun get(condition: (DevToolClient) -> Boolean): DevToolClient? {
    var cached = clients.find { condition(it) }
    if (cached?.isClosed() == true) {
      clients.remove(cached)
      cached = null
    }
    return cached
  }

  fun new(
      tabId: String,
      tag: String?,
      condition: (DevToolClient) -> Boolean = { true }
  ): DevToolClient {
    var client =
        get { it.tabId == tabId && it.tag == tag && condition(it) } ?: DevToolClient(tabId, tag)
    if (client.isClosed()) {
      hitDevTools().close()
      client = DevToolClient(tabId)
    }
    return client
  }

  fun add(client: DevToolClient?) {
    if (client == null) return
    val cached = clients.find { it.tabId == client.tabId }
    if (cached != null) clients.remove(cached)
    clients.add(client)
  }
}
