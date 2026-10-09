package org.matrix.chromext

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.webkit.WebView
import java.io.File
import java.io.FileReader
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.matrix.chromext.devtools.DevSessions
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.devtools.getInspectPages
import org.matrix.chromext.devtools.hitDevTools
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.ERUD_URL
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.invokeMethod
import org.matrix.chromext.utils.isDevToolsFrontEnd

object Listener {

  private val allowedActions =
      setOf(
          "copy",
          "block",
          "close",
          "focus",
          "userAgentSpoof",
          "loadEruda",
          "updateEruda",
          "syncData",
          "inspectPages",
          "websocket")

  private fun syncSharedPreference(
      payload: String,
      item: String,
      cache: MutableMap<String, String>,
  ): String? {
    val result = JSONObject(payload)
    val origin = result.getString("origin")
    val sharedPref = Chrome.getContext().getSharedPreferences(item, Context.MODE_PRIVATE)
    with(sharedPref.edit()) {
      if (result.has("data") && result.optString("data").length > 0) {
        val data = result.getString("data")
        putString(origin, data)
        cache.put(origin, data)
      } else if (cache.containsKey(origin)) {
        remove(origin)
        cache.remove(origin)
      }
      apply()
    }
    return null
  }

  private fun checkPermisson(action: String, key: Double, tab: Any?): Boolean {
    if (key != Local.key || action !in allowedActions) return false
    val url = Chrome.getUrl(tab) ?: return false
    if (action in setOf("inspectPages", "websocket") && !isDevToolsFrontEnd(url)) return false
    return true
  }

  // TabModel#closeTab(Tab tab, Tab recommendedNextTab, boolean...[, int]), dropped by Chrome 131
  // but still shipped by older forks.
  private fun findLegacyCloseTab(model: Class<*>, tab: Class<*>): Method? {
    fun Method.closesTab() =
        parameterTypes.firstOrNull() == tab &&
            parameterTypes.drop(1).all {
              it == tab || it == Boolean::class.java || it == Int::class.java
            }
    return findMethodOrNull(model, true) { name == "closeTab" && closesTab() }
        ?: findMethodOrNull(model, true) {
          returnType == Boolean::class.java && parameterTypes.size >= 5 && closesTab()
        }
  }

  // TabClosureParams is the only class offering a static Tab factory whose result builds one back,
  // a shape that survives the renaming of all three classes involved.
  private fun isTabClosureParams(clazz: Class<*>, tab: Class<*>): Boolean =
      clazz.declaredMethods.any {
        Modifier.isStatic(it.modifiers) &&
            it.parameterTypes contentDeepEquals arrayOf(tab) &&
            it.returnType.declaredMethods.any { build ->
              build.parameterTypes.size == 0 && build.returnType == clazz
            }
      }

  // Since Chrome 131: TabModel#getTabRemover()#forceCloseTabs(TabClosureParams#closeTab(tab))
  private fun forceCloseTab(model: Any, tabModel: Class<*>, tab: Class<*>, currentTab: Any) {
    val getTabRemover =
        findMethod(tabModel) {
          parameterTypes.size == 0 &&
              returnType.declaredMethods.any {
                it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    isTabClosureParams(it.parameterTypes[0], tab)
              }
        }
    val remover = getTabRemover.invoke(model)!!
    val forceCloseTabs =
        findMethod(getTabRemover.returnType) {
          returnType == Void.TYPE &&
              parameterTypes.size == 1 &&
              isTabClosureParams(parameterTypes[0], tab)
        }
    val closureParams = forceCloseTabs.parameterTypes[0]
    val builder =
        findMethod(closureParams) {
              Modifier.isStatic(modifiers) && parameterTypes contentDeepEquals arrayOf(tab)
            }
            .invoke(null, currentTab)!!
    val params =
        findMethod(builder::class.java) { parameterTypes.size == 0 && returnType == closureParams }
            .invoke(builder)
    forceCloseTabs.invoke(remover, params)
  }

  private fun checkErudaVerison(ctx: Context, callback: (String?) -> Unit) {
    val url = URL(ERUD_URL + "@latest/eruda.js")
    val connection = url.openConnection() as HttpURLConnection
    runCatching {
          connection.inputStream.bufferedReader().use {
            var firstLine = it.readLine()
            val new_version = Local.getErudaVersion(ctx, firstLine)
            if (new_version == null) {
              callback(null)
            } else if (new_version != Local.eruda_version) {
              Local.eruda_version = new_version
              callback(firstLine + "\n" + it.readText())
            } else {
              callback("latest")
            }
            it.close()
          }
        }
        .onFailure { callback(null) }
  }

  fun startAction(text: String, currentTab: Any? = null, auxObject: Any? = null, sourceId: String) {
    var frameId: String? = null
    val sourceParts = sourceId.split("/init/")
    if (sourceParts.size > 1) frameId = sourceParts.last()
    runCatching {
          val data = JSONObject(text)
          val action = data.getString("action")
          val key = data.getDouble("key")
          val payload = data.optString("payload")
          if (checkPermisson(action, key, currentTab)) {
            val callback = on(action, payload, currentTab, auxObject, frameId)
            if (callback != null) Chrome.evaluateJavascript(listOf(callback), currentTab, frameId)
          }
        }
        .onFailure { Log.i("${it::class.java.name}: startAction fails with " + text) }
  }

  fun on(
      action: String,
      payload: String = "",
      currentTab: Any? = null,
      auxObject: Any? = null,
      frameId: String? = null
  ): String? {
    // Native callers also pass through this boundary; removed legacy actions never reach a parser.
    if (action !in allowedActions) return null
    var callback: String? = null
    when (action) {
      "copy" -> {
        val data = JSONObject(payload)
        val type = data.getString("type")
        val text = data.getString("text")
        val label = data.optString("label")
        val clipData =
            when (type) {
              "html" -> ClipData.newHtmlText(label, text, data.optString("htmlText"))
              else -> ClipData.newPlainText(label, text)
            }
        val context = Chrome.getContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clipData)
      }
      "block" -> {
        callback = "if (Symbol.ChromeXt) Symbol.ChromeXt.lock(${Local.key},'${Local.name}');"
      }
      "close" -> {
        val activity = Chrome.getContext()
        if (WebViewHook.isInit && currentTab != null && auxObject != null) {
          auxObject.invokeMethod(currentTab) { name == "onCloseWindow" }
        } else if (Chrome.isSamsung &&
            currentTab != null &&
            activity::class.java ==
                Chrome.load("com.sec.android.app.sbrowser.SBrowserMainActivity")) {
          val manager = activity.invokeMethod { name == "getTabManager" }!!
          @Suppress("UNCHECKED_CAST")
          val tabList = manager.invokeMethod { name == "getAllTabList" } as List<Any>
          tabList
              .find { it.invokeMethod { name == "getTab" } == currentTab }
              ?.also { manager.invokeMethod(it) { name == "closeTab" } }
        } else if (currentTab != null &&
            activity::class.java == UserScriptProxy.chromeTabbedActivity) {
          val tab = Chrome.load("org.chromium.chrome.browser.tab.Tab")
          val tabModel = Chrome.load("org.chromium.chrome.browser.tabmodel.TabModel")
          val getCurrentTabModel =
              findMethod(activity::class.java, true) {
                parameterTypes.size == 0 && returnType == tabModel
              }
          val model = getCurrentTabModel.invoke(activity)!!
          val legacyClose = findLegacyCloseTab(model::class.java, tab)
          if (legacyClose != null) {
            val args =
                legacyClose.parameterTypes.mapIndexed { i, type ->
                  when {
                    i == 0 -> currentTab
                    type == tab -> null
                    type == Boolean::class.java -> false
                    else -> 0
                  }
                }
            legacyClose.invoke(model, *args.toTypedArray())
          } else {
            forceCloseTab(model, tabModel, tab, currentTab)
          }
        } else {
          val msg = "Closing tab ${currentTab} with context ${activity}"
          callback = "console.error(new TypeError('ChromeXt Action failure', {cause: '${msg}'}));"
        }
      }
      "focus" -> {
        Chrome.updateTab(currentTab)
        val detail = JSONObject(payload)
        val requestFocus = detail.getBoolean("requestFocus")
        val activity = Chrome.getContext()
        if (requestFocus &&
            currentTab != null &&
            activity::class.java == UserScriptProxy.chromeTabbedActivity) {
          (activity as Activity).window.decorView.requestFocus()
        }
      }
      "userAgentSpoof" -> {
        if (UserScriptHook.isInit) {
          val loadUrlParams = UserScriptProxy.newLoadUrlParams(payload)
          if (UserScriptProxy.userAgentHook(payload, loadUrlParams)) {
            UserScriptProxy.loadUrl.invoke(Chrome.getTab(), loadUrlParams)
            callback = "console.log('User-Agent spoofed');"
          }
        }
      }
      "loadEruda" -> {
        val ctx = Chrome.getContext()
        val eruda = File(ctx.filesDir, "Eruda.js")
        if (eruda.exists()) {
          val codes =
              mutableListOf(
                  FileReader(eruda).use { it.readText() } +
                      "\n//# sourceURL=${ERUD_URL}@${Local.eruda_version}/eruda.js")
          codes.add("{${Local.eruda}}\n//# sourceURL=local://ChromeXt/eruda")
          Chrome.evaluateJavascript(codes, currentTab, frameId)
        } else {
          on("updateEruda", JSONObject().put("load", true).toString())
        }
      }
      "updateEruda" -> {
        val ctx = Chrome.getContext()
        Handler(ctx.mainLooper).post { Log.toast(ctx, "Updating Eruda...") }
        Chrome.IO.submit {
          checkErudaVerison(ctx) {
            val msg =
                if (it == "latest") {
                  "Eruda is already the latest"
                } else if (it != null) {
                  "Updated to eruda v" + Local.eruda_version
                } else {
                  "Failed to download Eruda.js from ${ERUD_URL}"
                }
            Handler(ctx.mainLooper).post { Log.toast(ctx, msg) }
            if (it != null) {
              if (it != "latest") {
                val file = File(ctx.filesDir, "Eruda.js")
                file.outputStream().write(it.toByteArray())
              }
              if (payload != "" && JSONObject(payload).optBoolean("load")) on("loadEruda")
            }
          }
        }
      }
      "syncData" -> {
        val type = JSONObject(payload).getString("name")
        callback =
            when (type) {
              "filters" ->
                  syncSharedPreference(payload, "CosmeticFilter", ScriptDbManager.cosmeticFilters)
              "userAgent" -> syncSharedPreference(payload, "UserAgent", ScriptDbManager.userAgents)
              "cspRules" -> syncSharedPreference(payload, "CSPRule", ScriptDbManager.cspRules)
              else -> null
            }
      }
      "inspectPages" -> {
        if (WebViewHook.isInit) WebView.setWebContentsDebuggingEnabled(true)
        Chrome.IO.submit {
          val code = "ChromeXt.post('inspect_pages', ${getInspectPages()});"
          Handler(Chrome.getContext().mainLooper).post {
            Chrome.evaluateJavascript(listOf(code), currentTab, frameId)
          }
        }
      }
      "websocket" -> {
        val detail = JSONObject(payload)
        val targetTabId = detail.getString("targetTabId")
        var target = DevSessions.get { it.tabId == targetTabId && it.tag == "transit" }
        if (detail.has("message")) {
          val message = JSONObject(detail.getString("message"))
          target?.command(
              message.getInt("id"), message.getString("method"), message.optJSONObject("params"))
        } else {
          fun response(res: JSONObject) {
            if (Chrome.checkTab(currentTab)) {
              Handler(Chrome.getContext().mainLooper).post {
                Chrome.evaluateJavascript(
                    listOf("ChromeXt.post('websocket', ${res})"), currentTab, frameId)
              }
            } else {
              target?.close()
            }
          }
          Chrome.IO.submit {
            target?.close()
            hitDevTools().close()
            target = DevToolClient(targetTabId, "transit")
            if (!target!!.isClosed()) {
              DevSessions.add(target!!)
              response(JSONObject(mapOf("open" to true)))
              target!!.listen { response(JSONObject(mapOf("message" to it))) }
            }
            response(JSONObject(mapOf("error" to "Remote session closed")))
          }
        }
      }
    }
    return callback
  }
}
