package org.matrix.chromext.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.matrix.chromext.manager.ipc.IExtensionStore

/** Runs as a different Android UID, even though the debug APK has the same development signer. */
class UnauthorizedBrokerProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    start()
  }

  override fun onStart() {
    val result = Bundle()
    val ready = CountDownLatch(1)
    var api: IExtensionStore? = null
    val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            api = IExtensionStore.Stub.asInterface(binder)
            ready.countDown()
          }

          override fun onServiceDisconnected(name: ComponentName) {}

          override fun onNullBinding(name: ComponentName) {
            ready.countDown()
          }
        }
    var bound = false
    val input = File(targetContext.cacheDir, "untrusted-probe.zip")
    var code = Activity.RESULT_CANCELED
    try {
      bound =
          targetContext.bindService(
              Intent("io.github.ccc007ccc.chromextextensions.BIND_EXTENSION_STORE")
                  .setComponent(
                      ComponentName(
                          "io.github.ccc007ccc.chromextextensions",
                          "org.matrix.chromext.manager.ExtensionStoreService")),
              connection,
              Context.BIND_AUTO_CREATE)
      check(bound && ready.await(10, TimeUnit.SECONDS) && api != null) {
        "Could not connect to package service"
      }
      val service = checkNotNull(api)
      input.writeText("not a ZIP; authorization must be checked first")
      val id = "00000000-0000-4000-8000-000000000000"
      val operations =
          linkedMapOf<String, () -> Any?>(
              "version" to { service.protocolVersion() },
              "list" to { service.list(0, 8) },
              "describe" to { service.describe(id) },
              "review" to
                  {
                    ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                      service.review(it)
                    }
                  },
              "commit" to { service.commit("forged-token") },
              "cancel" to { service.cancel("forged-token") },
              "remove" to { service.remove(id) },
              "resource" to { service.openResource(id, "../metadata.json")?.close() })
      operations.forEach { (name, operation) ->
        val error = runCatching { operation() }.exceptionOrNull()
        check(error is SecurityException) { "$name did not reject the untrusted UID: $error" }
      }
      result.putString(
          "stream",
          "PASS: all ${operations.size} Binder operations reject a different app UID before reading packages or ZIP input.\n")
      code = Activity.RESULT_OK
    } catch (error: Throwable) {
      result.putString("stream", "FAIL: ${error.stackTraceToString()}")
    } finally {
      if (bound) targetContext.unbindService(connection)
      input.delete()
    }
    finish(code, result)
  }
}
