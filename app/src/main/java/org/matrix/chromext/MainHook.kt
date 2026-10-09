package org.matrix.chromext

import android.app.Application
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.matrix.chromext.hook.BaseHook
import org.matrix.chromext.hook.ContextMenuHook
import org.matrix.chromext.hook.PageInfoHook
import org.matrix.chromext.hook.PageMenuHook
import org.matrix.chromext.hook.PreferenceHook
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.utils.Hooks
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.hookAfter

val supportedPackages =
    arrayOf(
        "app.vanadium.browser",
        "com.android.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.chrome.beta",
        "com.chrome.canary",
        "com.chrome.dev",
        "com.coccoc.trinhduyet",
        "com.coccoc.trinhduyet_beta",
        "com.herond.android.browser",
        "com.kiwibrowser.browser",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary",
        "com.microsoft.emmx.dev",
        "com.naver.whale",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "org.axpos.aosmium",
        "org.bromite.bromite",
        "org.chromium.chrome",
        "org.chromium.thorium",
        "org.cromite.cromite",
        "org.greatfire.freebrowser",
        "org.triple.banana",
        "us.spotco.mulch")

class MainHook : XposedModule() {
  private var moduleReady = false

  override fun onModuleLoaded(param: ModuleLoadedParam) {
    check(apiVersion >= XposedInterface.API_102) {
      "ChromeXt-Extensions requires libxposed API 102"
    }
    Hooks.attach(this)
    Log.attachFramework { priority, message, error -> log(priority, TAG, message, error) }
    Resource.init(moduleApplicationInfo.sourceDir)
    Log.i("${param.processName}: $frameworkName $frameworkVersion, API $apiVersion")
    moduleReady = true
  }

  override fun onPackageReady(lpparam: PackageReadyParam) {
    if (!moduleReady ||
        !lpparam.isFirstPackage ||
        lpparam.packageName == BuildConfig.APPLICATION_ID ||
        lpparam.packageName == "android")
        return
    if (supportedPackages.contains(lpparam.packageName)) {
      // Every constructor taking a Context, rather than a fixed index into an order the JLS leaves
      // unspecified: browsers do not agree on how many constructors WindowAndroid has, and Whale in
      // particular has no one-argument overload, so index 1 there is a test-only constructor the
      // browser never calls and the whole module stays inert. The overloads delegate into one
      // another, so hooking all of them just means the callback can run twice, which Chrome.init
      // and initHooks both already absorb.
      val windowAndroid = lpparam.classLoader.loadClass("org.chromium.ui.base.WindowAndroid")
      val constructors =
          windowAndroid.declaredConstructors.filter {
            val first = it.parameterTypes.firstOrNull()
            first != null && Context::class.java.isAssignableFrom(first)
          }
      if (constructors.isEmpty()) Log.e("No WindowAndroid constructor takes a Context")
      constructors.forEach { constructor ->
        constructor.hookAfter { it, _ ->
          Chrome.init(it.args[0] as Context, lpparam.packageName)
          // Keep the menu hooks reachable even when the UserScript hook cannot be installed
          runCatching { initHooks(UserScriptHook) }.onFailure { Log.ex(it) }
          if (ContextMenuHook.isInit) return@hookAfter
          // The settings screen and the browser menu are independent features, so a browser that
          // cannot host our preferences must still get its menu entries. Edge is exactly that case:
          // it builds its settings programmatically, so PreferenceFragmentCompat carries no
          // addPreferencesFromResource for PreferenceHook to hook.
          if (!Chrome.isVivaldi) {
            runCatching { initHooks(PreferenceHook) }
                .onFailure { if (BuildConfig.DEBUG && !Chrome.isSamsung) Log.ex(it) }
          }
          runCatching {
                initHooks(if (Chrome.isEdge || Chrome.isCocCoc) PageInfoHook else PageMenuHook)
              }
              .onFailure {
                initHooks(ContextMenuHook)
                if (BuildConfig.DEBUG && !(Chrome.isSamsung || Chrome.isEdge)) Log.ex(it)
              }
        }
      }
    } else {
      // onPackageReady runs before Application is instantiated. Wait for a real, attached
      // application instead of depending on the removed AndroidAppHelper API.
      var applicationHook: HookHandle? = null
      applicationHook =
          Application::class.java.getDeclaredMethod("attach", Context::class.java).hookAfter {
              chain,
              _ ->
            val app = chain.thisObject as Application
            if (app.packageName != lpparam.packageName) return@hookAfter
            applicationHook?.unhook()
            Chrome.init(app, lpparam.packageName)
            initWebViewHooks(lpparam.packageName)
          }
    }
  }

  private fun initWebViewHooks(packageName: String) {
    Chrome.isQihoo = packageName == "com.qihoo.contents"

    if (Chrome.isMi) {
      WebViewHook.WebView = Chrome.load("com.miui.webkit.WebView")
      runCatching {
            WebViewHook.ViewClient = Chrome.load("com.android.browser.tab.TabWebViewClient")
            WebViewHook.ChromeClient = Chrome.load("com.android.browser.tab.TabWebChromeClient")
          }
          .onFailure {
            val miuiAutologinBar = Chrome.load("com.android.browser.MiuiAutologinBar")
            // Use MiuiAutologinBar to find `com.android.browser.tab.Tab`, which can located by
            // searching the string "X-MiOrigin"
            val fields = miuiAutologinBar.declaredFields.map { it.type }
            val tab =
                miuiAutologinBar.declaredMethods
                    .find {
                      it.parameterCount == 2 &&
                          it.parameterTypes[1] == Boolean::class.java &&
                          !fields.contains(it.parameterTypes[0])
                    }!!
                    .run { parameterTypes[0] }
            tab.declaredFields.forEach {
              if (findMethodOrNull(it.type) {
                // Found by searching the string "Console: "
                it.name == "onGeolocationPermissionsHidePrompt"
              } != null)
                  WebViewHook.ChromeClient = it.type
              if (findMethodOrNull(it.type) {
                // Found by searching the string "Tab.MainWebViewClient"
                it.name == "onReceivedHttpAuthRequest"
              } != null)
                  WebViewHook.ViewClient = it.type
            }
          }

      hookWebView()
      return
    }

    if (Chrome.isQihoo) {
      WebViewHook.WebView = Chrome.load("com.qihoo.webkit.WebView")
      WebViewHook.ViewClient = Chrome.load("com.qihoo.webkit.WebViewClient")
      WebViewHook.ChromeClient = Chrome.load("com.qihoo.webkit.WebChromeClient")
      hookWebView()
      return
    }

    WebViewClient::class.java.declaredConstructors[0].hookAfter { it, _ ->
      if (it.thisObject::class != WebViewClient::class) {
        WebViewHook.ViewClient = it.thisObject::class.java
        hookWebView()
      }
    }

    WebChromeClient::class.java.declaredConstructors[0].hookAfter { it, _ ->
      if (it.thisObject::class != WebChromeClient::class) {
        WebViewHook.ChromeClient = it.thisObject::class.java
        hookWebView()
      }
    }
  }

  private fun hookWebView() {
    if (WebViewHook.ChromeClient == null || WebViewHook.ViewClient == null) return
    if (WebViewHook.WebView == null) {
      runCatching {
            WebViewHook.WebView = WebView::class.java
            WebView.setWebContentsDebuggingEnabled(true)
          }
          .onFailure { if (BuildConfig.DEBUG) Log.ex(it) }
    }
    initHooks(WebViewHook, ContextMenuHook)
  }

  private fun initHooks(vararg hook: BaseHook) {
    hook.forEach {
      if (it.isInit) return@forEach
      it.init()
      if (it.isInit) Log.d("${it.javaClass.simpleName} hooked")
    }
  }
}
