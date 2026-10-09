package org.matrix.chromext.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.manager.ipc.IExtensionStore

/** Only native module code receives this Binder; it is never attached to a JavaScript bridge. */
class ExtensionStoreConnection(
    context: Context,
    private val ready: (IExtensionStore) -> Unit,
    private val lost: () -> Unit,
) : ServiceConnection, AutoCloseable {
  private val context = context.applicationContext
  private var bound = false
  private var closed = false
  private val main = Handler(Looper.getMainLooper())
  private val timeout = Runnable {
    if (!closed) {
      close()
      lost()
    }
  }

  init {
    bound =
        this.context.bindService(
            Intent(ExtensionStoreService.ACTION)
                .setComponent(
                    ComponentName(
                        BuildConfig.APPLICATION_ID, ExtensionStoreService::class.java.name)),
            this,
            Context.BIND_AUTO_CREATE)
    if (!bound) throw Unavailable()
    main.postDelayed(timeout, 10_000)
  }

  override fun onServiceConnected(name: ComponentName, binder: IBinder) {
    main.removeCallbacks(timeout)
    if (!closed) ready(IExtensionStore.Stub.asInterface(binder))
  }

  override fun onServiceDisconnected(name: ComponentName) {
    if (!closed) lost()
  }

  override fun onNullBinding(name: ComponentName) {
    close()
    lost()
  }

  override fun onBindingDied(name: ComponentName) {
    close()
    lost()
  }

  override fun close() {
    if (closed) return
    closed = true
    main.removeCallbacks(timeout)
    if (bound) {
      context.unbindService(this)
      bound = false
    }
  }

  class Unavailable : IllegalStateException("Extension package service could not be started")
}
