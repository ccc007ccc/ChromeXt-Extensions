package org.matrix.chromext.manager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.util.WeakHashMap
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.intercept

internal fun Context.findActivity(): Activity? {
  var context = this
  val seen = mutableSetOf<Context>()
  while (seen.add(context)) {
    if (context is Activity) return context
    context = (context as? ContextWrapper)?.baseContext ?: return null
  }
  return null
}

/** A pending result is owned by one Chrome Activity and one request code, then consumed once. */
internal object ChromeDocumentPicker {
  var lastResult = "not-opened"
    private set

  private const val REQUEST = 0xCE71
  private val pending = WeakHashMap<Activity, (Uri?) -> Unit>()
  private val hooked = mutableSetOf<java.lang.reflect.Method>()

  fun pick(activity: Activity, result: (Uri?) -> Unit) {
    check(!pending.containsKey(activity)) { "A document picker is already open" }
    val method =
        findMethod(activity.javaClass, true) {
          name == "onActivityResult" &&
              parameterTypes.contentEquals(
                  arrayOf(Int::class.java, Int::class.java, Intent::class.java))
        }
    if (method !in hooked) {
      method.intercept { chain ->
        val owner = chain.thisObject as Activity
        val callback = if (chain.args[0] == REQUEST) pending.remove(owner) else null
        if (callback == null) chain.proceed()
        else {
          val intent = chain.args[2] as? Intent
          val selected =
              intent?.data ?: intent?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
          lastResult =
              "result=${chain.args[1]},scheme=${selected?.scheme},clipItems=${intent?.clipData?.itemCount ?: 0}"
          val uri = if (chain.args[1] == Activity.RESULT_OK) selected else null
          callback(uri?.takeIf { it.scheme == "content" })
          null
        }
      }
      hooked.add(method)
    }
    pending[activity] = result
    lastResult = "awaiting-result"
    try {
      activity.startActivityForResult(
          Intent(Intent.ACTION_OPEN_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("*/*")
              .putExtra(
                  Intent.EXTRA_MIME_TYPES,
                  arrayOf(
                      "application/zip",
                      "application/x-zip-compressed",
                      "application/octet-stream")),
          REQUEST)
    } catch (error: Exception) {
      pending.remove(activity)
      throw error
    }
  }

  fun forget(activity: Activity) {
    pending.remove(activity)
  }
}
