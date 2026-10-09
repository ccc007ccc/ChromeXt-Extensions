package org.matrix.chromext.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.matrix.chromext.Chrome
import org.matrix.chromext.R
import org.matrix.chromext.manager.ExtensionManagerLauncher
import org.matrix.chromext.proxy.PageInfoProxy
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.script.Local
import org.matrix.chromext.utils.*

object PageInfoHook : BaseHook() {

  override fun init() {

    if (ContextMenuHook.isInit) return
    val proxy = PageInfoProxy
    val newRow = proxy.rowConstructor ?: throw NoSuchMethodException("PageInfoRowView(Context)")
    val title = proxy.mTitle ?: throw NoSuchFieldException("PageInfoRowView.mTitle")
    val show = proxy.showPageInfo ?: throw NoSuchMethodException("PageInfoController.show")
    val destroy = proxy.destroy ?: throw NoSuchMethodException("PageInfoController.destroy")
    if (proxy.nativeCallbacks.isEmpty()) throw NoSuchMethodException("PageInfoController callbacks")

    // The row container is looked up by resource id and never kept in a field, and Edge can swap
    // the whole PageInfoView for a bottom sheet behind a feature flag, so rather than chase it
    // through the controller we keep the first row built during show() and take its parent.
    var firstRow: ViewGroup? = null
    var controller: Any? = null
    var showing = false
    var inserted = false

    fun toolRow(
        titleRes: Int,
        iconRes: Int,
        host: Any,
        parent: ViewGroup,
        onClick: () -> Unit
    ): View {
      val row = newRow.newInstance(parent.getContext(), null) as ViewGroup
      row.setVisibility(View.VISIBLE)
      (proxy.mIcon?.get(row) as? ImageView)?.setImageResource(iconRes)
      val subTitle = proxy.mSubtitle?.get(row) as? TextView
      (subTitle?.getParent() as? ViewGroup)?.removeView(subTitle)
      val label = title.get(row) as TextView
      label.setText(titleRes)
      row.setOnClickListener {
        onClick()
        runCatching { destroy.invoke(host) }.onFailure { Log.ex(it) }
      }
      return row
    }

    fun insertRow(host: Any) {
      if (inserted) return
      val parent = firstRow?.getParent() as? ViewGroup ?: return
      val url = Chrome.getUrl() ?: return
      if (isChromeScheme(url)) return
      inserted = true
      parent.addView(
          toolRow(R.string.main_menu_extension, R.drawable.ic_extension, host, parent) {
            ExtensionManagerLauncher.open()
          })
      parent.addView(
          toolRow(R.string.main_menu_eruda_console, R.drawable.ic_devtools, host, parent) {
            UserScriptProxy.evaluateJavascript(Local.openEruda)
          })
    }

    show.intercept { chain ->
      showing = true
      inserted = false
      firstRow = null
      controller = null
      try {
        val result = chain.proceed()
        controller?.let { host -> insertRow(host) }
        result
      } finally {
        showing = false
        firstRow = null
        controller = null
      }
    }

    newRow.hookAfter { it, _ ->
      if (showing && firstRow == null) firstRow = it.thisObject as ViewGroup
    }

    // Native runs these while show() is still on the stack, which is both where the controller
    // instance becomes reachable and the earliest point at which the rows are already laid out.
    proxy.nativeCallbacks.forEach { callback ->
      callback.hookAfter { it, _ ->
        if (!showing) return@hookAfter
        controller = it.thisObject
        insertRow(it.thisObject)
      }
    }

    isInit = true
  }
}
