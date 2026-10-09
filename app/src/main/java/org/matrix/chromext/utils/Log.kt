package org.matrix.chromext.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.lang.ref.WeakReference
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.TAG

object Log {
  private var lastToast: WeakReference<Toast>? = null
  // Keep libxposed types out of this class: the standalone manager has no framework API.
  @Volatile private var frameworkLogger: ((Int, String, Throwable?) -> Unit)? = null

  fun attachFramework(logger: (Int, String, Throwable?) -> Unit) {
    frameworkLogger = logger
  }

  fun i(msg: String) {
    Log.i(TAG, msg)
    frameworkLogger?.invoke(Log.INFO, msg, null)
  }

  fun d(msg: String, full: Boolean = false) {
    if (BuildConfig.DEBUG) {
      if (!full && msg.length > 300) {
        Log.d(TAG, msg.take(300) + " ...")
      } else {
        Log.d(TAG, msg)
      }
    }
  }

  fun w(msg: String) {
    Log.w(TAG, msg)
  }

  fun e(msg: String) {
    Log.e(TAG, msg)
    frameworkLogger?.invoke(Log.ERROR, msg, null)
  }

  fun ex(thr: Throwable, msg: String = "") {
    Log.e(TAG, msg, thr)
    frameworkLogger?.invoke(Log.ERROR, msg, thr)
  }

  fun toast(context: Context, msg: String) {
    this.lastToast?.get()?.cancel()
    val duration = Toast.LENGTH_SHORT
    val toast = Toast.makeText(context, msg, duration)
    toast.show()
    this.lastToast = WeakReference(toast)
  }
}
