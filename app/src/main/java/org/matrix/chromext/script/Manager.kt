package org.matrix.chromext.script

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.invokeMethod
import org.matrix.chromext.utils.isDevToolsFrontEnd
import org.matrix.chromext.utils.parseOrigin
import org.matrix.chromext.utils.resolveContentUrl

/** Shared page/DevTools plumbing. The legacy userscript database is never opened. */
object ScriptDbManager {

  val cosmeticFilters: MutableMap<String, String>
  val userAgents: MutableMap<String, String>
  val cspRules: MutableMap<String, String>

  init {
    val ctx = Chrome.getContext()
    @Suppress("UNCHECKED_CAST")
    cosmeticFilters =
        ctx.getSharedPreferences("CosmeticFilter", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>
    @Suppress("UNCHECKED_CAST")
    userAgents =
        ctx.getSharedPreferences("UserAgent", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>
    @Suppress("UNCHECKED_CAST")
    cspRules =
        ctx.getSharedPreferences("CSPRule", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>
  }

  private fun fixEncoding(url: String, path: String, codes: MutableList<String>) {
    if (path.endsWith(".js") || path.endsWith(".txt")) {
      Chrome.getContext().contentResolver.openInputStream(Uri.parse(url))?.use { stream ->
        val data = JSONObject(mapOf("utf-8" to stream.bufferedReader().readText()))
        codes.add("window.content=${data};")
        codes.add(Local.encoding)
      }
    } else if (url.endsWith(".js") || url.endsWith(".txt")) {
      codes.add(Local.encoding)
    }

    if (codes.size > 1 && (url.endsWith(".txt") || path.endsWith(".txt")))
        codes.add("fixEncoding();")
  }

  fun invokeScript(url: String, webView: Any? = null, frameId: String? = null) {
    val codes = mutableListOf<String>(Local.initChromeXt)
    val path = resolveContentUrl(url)
    val webSettings = webView?.invokeMethod { name == "getSettings" }
    fixEncoding(url, path, codes)

    if (isDevToolsFrontEnd(url)) {
      codes.add("globalThis.ChromeXt = Symbol.ChromeXt;")
      codes.add(Local.customizeDevTool)
      webSettings?.invokeMethod(null) { name == "setUserAgentString" }
    } else {
      val origin = parseOrigin(url)
      if (origin != null) {
        cspRules[origin]?.let { value ->
          runCatching {
            val rule = JSONArray(value)
            codes.add("Symbol.ChromeXt.cspRules.push(...${rule});${Local.cspRule}")
          }
        }
        cosmeticFilters[origin]?.let { value ->
          runCatching {
            val filter = JSONArray(value)
            codes.add("Symbol.ChromeXt.filters.push(...${filter});${Local.cosmeticFilter}")
          }
        }
        userAgents[origin]?.let { agent ->
          codes.add(
              "Object.defineProperties(window.navigator,{userAgent:{value:${JSONObject.quote(agent)}}});")
          webSettings?.invokeMethod(agent) { name == "setUserAgentString" }
        }
      }
      // Lock every ordinary page, independently of whether any optional feature runs on it.
      // In particular, .user.js files and the former remote manager receive no privileged bridge.
      codes.add("Symbol.ChromeXt.lock(${Local.key}, '${Local.name}');")
    }

    codes.add("//# sourceURL=local://ChromeXt/init" + if (frameId == null) "" else "/" + frameId)
    webSettings?.invokeMethod(true) { name == "setJavaScriptEnabled" }
    Chrome.evaluateJavascript(listOf(codes.joinToString("\n")), webView, frameId)
  }
}
