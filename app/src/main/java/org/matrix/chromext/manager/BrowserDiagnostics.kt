package org.matrix.chromext.manager

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.devToolsGet
import org.matrix.chromext.extensions.PackageDescription
import org.matrix.chromext.extensions.PackageReview
import org.matrix.chromext.hook.PageMenuHook
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.manager.ipc.IExtensionStore
import org.matrix.chromext.utils.Hooks
import org.matrix.chromext.utils.Log

/**
 * Debug-only, fixed diagnostics, callable only by holders of Android's privileged DUMP permission.
 */
object BrowserDiagnostics {
  const val ACTION = "io.github.ccc007ccc.chromextextensions.DEBUG"
  private var installed = false

  fun install(context: Context) {
    if (!BuildConfig.DEBUG || installed || context.packageName != "com.android.chrome") return
    val app = context.applicationContext
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            fun finish(result: JSONObject) {
              val text = result.toString()
              Log.i("DIAGNOSTIC $text")
              pending.setResultData(text)
              pending.setResultCode(
                  if (result.optBoolean("ok")) Activity.RESULT_OK else Activity.RESULT_CANCELED)
              pending.finish()
            }
            when (intent.getStringExtra("operation")) {
              "review-fixture" -> {
                val result =
                    runCatching {
                          val activity = checkNotNull(Chrome.getContext().findActivity())
                          val nonce = checkNotNull(intent.getStringExtra("nonce"))
                          require(nonce.matches(Regex("[0-9a-f]{8}")))
                          val file =
                              File.createTempFile("chromext-ui-$nonce-", ".zip", app.cacheDir)
                          try {
                            ZipOutputStream(file.outputStream()).use { zip ->
                              mapOf(
                                      "manifest.json" to
                                          """{"manifest_version":3,"name":"ChromeXt UI probe $nonce","version":"1.0","permissions":["storage"],"host_permissions":["https://example.test/*"],"content_scripts":[{"matches":["https://example.test/*"],"js":["content.js"]}]}""",
                                      "content.js" to
                                          "throw new Error('This fixture must remain inactive');")
                                  .forEach { (name, content) ->
                                    zip.putNextEntry(ZipEntry(name))
                                    zip.write(content.toByteArray())
                                    zip.closeEntry()
                                  }
                            }
                            ExtensionManagerLauncher.reviewForDiagnostics(activity, file)
                          } catch (error: Throwable) {
                            file.delete()
                            throw error
                          }
                          JSONObject().put("ok", true)
                        }
                        .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
                finish(result)
              }
              "open-manager",
              "open-menu" -> {
                val activity = runCatching { Chrome.getContext().findActivity() }.getOrNull()
                if (activity == null || !activity.window.decorView.hasWindowFocus()) {
                  finish(
                      JSONObject().put("ok", false).put("reason", "Chrome window is not focused"))
                  return
                }
                val result = runCatching {
                  if (intent.getStringExtra("operation") == "open-manager") {
                    ExtensionManagerLauncher.open(activity)
                    true
                  } else {
                    val id =
                        activity.resources.getIdentifier("menu_button", "id", activity.packageName)
                    activity.findViewById<android.view.View>(id)?.performClick() == true
                  }
                }
                Handler(Looper.getMainLooper())
                    .postDelayed(
                        {
                          finish(
                              JSONObject()
                                  .put("ok", result.getOrDefault(false))
                                  .put("menuInsertions", PageMenuHook.modelInsertions)
                                  .put(
                                      "panelVisible", ExtensionManagerLauncher.isShowing(activity)))
                        },
                        300)
              }
              else ->
                  Chrome.IO.submit {
                    val result =
                        runCatching {
                              when (intent.getStringExtra("operation")) {
                                "status" ->
                                    JSONObject()
                                        .put("apiVersion", Hooks.framework.apiVersion)
                                        .put("version", BuildConfig.VERSION_NAME)
                                        .put("browserHooks", UserScriptHook.isInit)
                                        .put("menuHooks", PageMenuHook.isInit)
                                        .put("menuInsertions", PageMenuHook.modelInsertions)
                                        .put("managerActions", PageMenuHook.managerActions)
                                        .put("managerOutcome", ExtensionManagerLauncher.lastOutcome)
                                        .put("pickerResult", ChromeDocumentPicker.lastResult)
                                "devtools-version" -> JSONObject(devToolsGet("/json/version"))
                                "broker-probe" -> brokerProbe(app)
                                else ->
                                    throw IllegalArgumentException("Unknown diagnostic operation")
                              }.put("ok", true)
                            }
                            .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
                    finish(result)
                  }
            }
          }
        }
    app.registerReceiver(
        receiver,
        IntentFilter(ACTION),
        Manifest.permission.DUMP,
        null,
        if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
    installed = true
  }

  private fun brokerProbe(context: Context): JSONObject {
    val connected = CountDownLatch(1)
    var api: IExtensionStore? = null
    val connection =
        ExtensionStoreConnection(
            context,
            {
              api = it
              connected.countDown()
            },
            { connected.countDown() })
    val file = File(context.cacheDir, "chromext-broker-${UUID.randomUUID()}.zip")
    var installedId: String? = null
    var reviewToken: String? = null
    try {
      check(connected.await(5, TimeUnit.SECONDS) && api != null) {
        "Package service did not connect"
      }
      val service = checkNotNull(api)
      check(service.protocolVersion() == 1)
      val marker = UUID.randomUUID().toString()
      ZipOutputStream(file.outputStream()).use { zip ->
        mapOf(
                "manifest.json" to
                    """{"manifest_version":3,"name":"ChromeXt broker probe","version":"1.0","permissions":["storage"]}""",
                "payload.txt" to marker)
            .forEach { (name, content) ->
              zip.putNextEntry(ZipEntry(name))
              zip.write(content.toByteArray())
              zip.closeEntry()
            }
      }
      val gson = Gson()
      val review =
          ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            gson.fromJson(service.review(it), PackageReview::class.java)
          }
      reviewToken = review.token
      val installed = gson.fromJson(service.commit(review.token), PackageDescription::class.java)
      installedId = requireNotNull(installed.installationId)
      reviewToken = null
      check(service.describe(installedId).contains(installedId))
      val payload =
          ParcelFileDescriptor.AutoCloseInputStream(
                  service.openResource(installedId, "payload.txt"))
              .bufferedReader()
              .use { it.readText() }
      check(payload == marker)
      check(
          runCatching { service.openResource(installedId, "../metadata.json") }.exceptionOrNull()
              is IllegalArgumentException)
      check(service.remove(installedId))
      installedId = null
      return JSONObject()
          .put("authenticatedChrome", true)
          .put("reviewCommitResourceRemove", true)
          .put("traversalRejected", true)
          .put("extensionCodeExecuted", false)
    } finally {
      installedId?.let { runCatching { api?.remove(it) } }
      reviewToken?.let { runCatching { api?.cancel(it) } }
      file.delete()
      connection.close()
    }
  }
}
