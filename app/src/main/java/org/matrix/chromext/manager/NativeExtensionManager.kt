package org.matrix.chromext.manager

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.gson.Gson
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.Chrome
import org.matrix.chromext.R
import org.matrix.chromext.Resource
import org.matrix.chromext.extensions.PackageDescription
import org.matrix.chromext.extensions.PackagePage
import org.matrix.chromext.extensions.PackageReview
import org.matrix.chromext.manager.ipc.IExtensionStore
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findMethodOrNull

object ExtensionManagerLauncher {
  private val panels = WeakHashMap<Activity, WeakReference<NativeExtensionManager>>()
  var lastOutcome = "not-opened"
    private set

  fun isShowing(activity: Activity): Boolean = panels[activity]?.get()?.showing == true

  internal fun reviewForDiagnostics(activity: Activity, file: File) {
    check(BuildConfig.DEBUG)
    checkNotNull(panels[activity]?.get()) { "Open the native manager first" }
        .reviewForDiagnostics(file)
  }

  fun open(context: Context = Chrome.getContext()) {
    lastOutcome = "finding-window"
    val activity =
        context.findActivity()
            ?: run {
              lastOutcome = "no-window"
              Log.e("No Chrome window for extension manager")
              return
            }
    if (activity.isFinishing || activity.isDestroyed) {
      lastOutcome = "closed-window"
      return
    }
    Resource.enrich(activity)
    val tab = Chrome.getTab()
    val incognito =
        runCatching {
              val tabContext =
                  tab?.let {
                    findMethodOrNull(it.javaClass, true) {
                          parameterCount == 0 &&
                              returnType == Context::class.java &&
                              !java.lang.reflect.Modifier.isStatic(modifiers)
                        }
                        ?.invoke(it) as? Context
                  }
              if (tab == null || tabContext?.findActivity() !== activity) {
                Log.d(
                    "Manager window mismatch: owner=${activity.javaClass.name}, tab=${tabContext?.findActivity()?.javaClass?.name}")
                null
              } else {
                val method =
                    findMethodOrNull(tab.javaClass, true) {
                      name in setOf("isIncognito", "isIncognitoBranded", "isOffTheRecord") &&
                          parameterCount == 0
                    }
                if (method != null) method.invoke(tab) as Boolean
                else {
                  val profile =
                      findMethodOrNull(tab.javaClass, true) {
                            name == "getProfile" && parameterCount == 0
                          }
                          ?.invoke(tab)
                  profile?.let {
                    findMethodOrNull(it.javaClass, true) {
                          name == "isOffTheRecord" && parameterCount == 0
                        }
                        ?.invoke(it) as? Boolean
                  }
                }
              }
            }
            .onFailure { Log.ex(it, "Cannot verify the Chrome window's profile") }
            .getOrNull()
    if (incognito != false) {
      lastOutcome = if (incognito == true) "incognito-refused" else "profile-unverified"
      android.widget.Toast.makeText(
              activity,
              if (incognito == true) R.string.manager_incognito_unavailable
              else R.string.manager_profile_unavailable,
              android.widget.Toast.LENGTH_LONG)
          .show()
      return
    }
    panels[activity]?.get()?.let { if (it.showing) return }
    val panel = NativeExtensionManager(activity) { panels.remove(activity) }
    panels[activity] = WeakReference(panel)
    panel.show()
    lastOutcome = "panel-shown"
  }
}

/** Browser-owned native package UI. Extension execution remains gated by the runtime milestones. */
private class NativeExtensionManager(
    private val activity: Activity,
    private val dismissed: () -> Unit
) {
  private val dialog = Dialog(activity)
  private val children = mutableSetOf<Dialog>()
  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val gson = Gson()
  private var connection: ExtensionStoreConnection? = null
  private var service: IExtensionStore? = null
  private var closed = false
  private var busy = true
  private var picking = false
  private var reviewToken: String? = null
  private var requestGeneration = 0
  private var pageOffset = 0
  private var total = 0
  private val entries = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
  private val status = label("", 14f)
  private val importButton =
      button(R.string.manager_import) { pickArchive() }.apply { id = R.id.extension_manager_import }
  private val refreshButton = button(R.string.manager_refresh) { load(0) }
  private val nextButton = button(R.string.manager_next_page) { load(pageOffset + PAGE_SIZE) }
  private val previousButton =
      button(R.string.manager_previous_page) { load((pageOffset - PAGE_SIZE).coerceAtLeast(0)) }
  val showing
    get() = dialog.isShowing

  fun reviewForDiagnostics(file: File) {
    check(BuildConfig.DEBUG && !closed && !busy && service != null)
    check(dialog.window?.decorView?.hasWindowFocus() == true) { "Manager window is not focused" }
    inspectArchive {
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { file.delete() }
    }
  }

  private val lifecycle =
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(owner: Activity) {
          if (owner === activity) dialog.dismiss()
        }

        override fun onActivityStopped(owner: Activity) {
          if (owner === activity && !picking) dialog.dismiss()
        }

        override fun onActivityCreated(owner: Activity, state: Bundle?) {}

        override fun onActivityStarted(owner: Activity) {}

        override fun onActivityResumed(owner: Activity) {}

        override fun onActivityPaused(owner: Activity) {}

        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) {}
      }

  fun show() {
    val column =
        LinearLayout(activity).apply {
          id = R.id.extension_manager_panel
          orientation = LinearLayout.VERTICAL
          setPadding(dp(24), dp(16), dp(24), dp(16))
          addView(label(activity.getString(R.string.main_menu_extension), 24f))
          addView(label(activity.getString(R.string.manager_runtime_pending), 15f))
          addView(importButton)
          addView(refreshButton)
          addView(status)
          addView(
              ScrollView(activity).apply { addView(entries) }, LinearLayout.LayoutParams(-1, 0, 1f))
          addView(
              LinearLayout(activity).apply {
                addView(previousButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(nextButton, LinearLayout.LayoutParams(0, -2, 1f))
              })
          addView(button(android.R.string.cancel) { dialog.dismiss() })
        }
    dialog.setContentView(column)
    dialog.setOnDismissListener { close() }
    activity.application.registerActivityLifecycleCallbacks(lifecycle)
    dialog.show()
    val metrics = activity.resources.displayMetrics
    dialog.window?.apply {
      setLayout(
          if (activity.resources.configuration.smallestScreenWidthDp >= 600) dp(600)
          else ViewGroup.LayoutParams.MATCH_PARENT,
          (metrics.heightPixels * 0.85).toInt())
      setGravity(
          if (activity.resources.configuration.smallestScreenWidthDp >= 600) Gravity.CENTER
          else Gravity.BOTTOM)
    }
    setBusy(true, activity.getString(R.string.manager_connecting))
    try {
      connection =
          ExtensionStoreConnection(
              activity,
              ready = {
                service = it
                load(0)
              },
              lost = {
                requestGeneration++
                service = null
                setBusy(false, activity.getString(R.string.manager_service_lost))
              })
    } catch (error: Exception) {
      showError(error)
    }
  }

  private fun load(offset: Int) =
      submit(
          R.string.manager_loading,
          { api ->
            check(api.protocolVersion() == 1) { "Unsupported package service version" }
            gson.fromJson(api.list(offset, PAGE_SIZE), PackagePage::class.java)
          }) { page ->
            pageOffset = page.offset
            total = page.total
            status.text =
                activity.resources.getQuantityString(R.plurals.manager_count, total, total)
            entries.removeAllViews()
            if (page.entries.isEmpty())
                entries.addView(label(activity.getString(R.string.manager_empty), 16f))
            page.entries.forEach(::addEntry)
            updateButtons()
          }

  private fun addEntry(entry: PackageDescription) {
    val id = requireNotNull(entry.installationId)
    entries.addView(
        LinearLayout(activity).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(0, dp(12), 0, dp(12))
          addView(label(entry.name, 20f))
          addView(
              label(
                  activity.getString(
                      R.string.manager_version, entry.version, entry.manifestVersion),
                  14f))
          addView(label(activity.getString(R.string.manager_inactive), 15f))
          addView(
              button(R.string.manager_details) {
                if (!busy)
                    submit(
                        R.string.manager_loading,
                        { gson.fromJson(it.describe(id), PackageDescription::class.java) }) {
                            details ->
                          showChild(
                              AlertDialog.Builder(activity)
                                  .setTitle(details.name)
                                  .setMessage(describe(details))
                                  .setPositiveButton(android.R.string.ok, null))
                        }
              })
          addView(
              button(R.string.manager_uninstall) {
                if (!busy)
                    showChild(
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.manager_remove_title, entry.name))
                            .setMessage(R.string.manager_remove_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.manager_uninstall) { _, _ ->
                              submit(R.string.manager_removing, { it.remove(id) }) { load(0) }
                            })
              })
        })
  }

  private fun pickArchive() {
    if (busy) return
    picking = true
    try {
      ChromeDocumentPicker.pick(activity) { uri ->
        picking = false
        if (uri != null && !closed) inspect(uri)
      }
    } catch (error: Exception) {
      picking = false
      showError(error)
    }
  }

  private fun inspect(uri: Uri) = inspectArchive {
    activity.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException(activity.getString(R.string.manager_cannot_read))
  }

  private fun inspectArchive(open: () -> ParcelFileDescriptor) =
      submit(
          R.string.manager_checking,
          { api -> open().use { gson.fromJson(api.review(it), PackageReview::class.java) } },
          abandoned = { api, review -> api.cancel(review.token) }) { review ->
            reviewToken = review.token
            showChild(
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.manager_review, review.entry.name))
                    .setMessage(
                        describe(review.entry) +
                            "\n\n" +
                            activity.getString(R.string.manager_review_note))
                    .setPositiveButton(R.string.manager_import_only) { _, _ ->
                      reviewToken = null
                      submit(R.string.manager_importing, { it.commit(review.token) }) { load(0) }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> discard(review.token) }
                    .setOnCancelListener { discard(review.token) })
          }

  private fun discard(token: String) {
    reviewToken = null
    submit(R.string.manager_loading, { it.cancel(token) }) { load(0) }
  }

  private fun describe(entry: PackageDescription) = buildString {
    append(activity.getString(R.string.manager_version, entry.version, entry.manifestVersion))
    entry.description?.let { append("\n\n").append(it) }
    for ((title, values) in
        listOf(
            R.string.manager_permissions to entry.permissions,
            R.string.manager_hosts to entry.hosts,
            R.string.manager_content_hosts to entry.contentHosts,
            R.string.manager_optional to entry.optionalPermissions)) {
      append("\n\n").append(activity.getString(title)).append('\n')
      append(values.joinToString("\n").ifEmpty { activity.getString(R.string.manager_none) })
    }
  }

  private fun <T> submit(
      message: Int,
      operation: (IExtensionStore) -> T,
      abandoned: (IExtensionStore, T) -> Unit = { _, _ -> },
      success: (T) -> Unit
  ) {
    if (closed) return
    val api =
        service
            ?: run {
              setBusy(false, activity.getString(R.string.manager_service_lost))
              return
            }
    setBusy(true, activity.getString(message))
    val generation = ++requestGeneration
    worker.execute {
      val result = runCatching { operation(api) }
      main.post {
        if (!closed &&
            generation == requestGeneration &&
            dialog.isShowing &&
            !activity.isDestroyed &&
            !activity.isFinishing) {
          setBusy(false)
          result
              .onSuccess { value -> runCatching { success(value) }.onFailure(::showError) }
              .onFailure(::showError)
        } else {
          result.onSuccess { value -> Chrome.IO.submit { runCatching { abandoned(api, value) } } }
        }
      }
    }
  }

  private fun showError(error: Throwable) {
    if (closed) return
    setBusy(false, activity.getString(R.string.manager_failed))
    if (activity.isFinishing || activity.isDestroyed) {
      dialog.dismiss()
      return
    }
    runCatching {
          showChild(
              AlertDialog.Builder(activity)
                  .setTitle(R.string.manager_failed)
                  .setMessage(
                      if (error is ExtensionStoreConnection.Unavailable)
                          activity.getString(R.string.manager_service_unavailable)
                      else error.message ?: error.javaClass.simpleName)
                  .setPositiveButton(android.R.string.ok, null))
        }
        .onFailure { Log.ex(it, "Cannot display package manager error") }
  }

  private fun showChild(builder: AlertDialog.Builder) {
    val child = builder.create()
    children.add(child)
    child.setOnDismissListener { children.remove(child) }
    try {
      child.show()
    } catch (error: Exception) {
      children.remove(child)
      throw error
    }
  }

  private fun setBusy(value: Boolean, message: String = "") {
    if (closed) return
    busy = value
    status.text = message
    updateButtons()
  }

  private fun updateButtons() {
    val available = !busy && service != null
    importButton.isEnabled = available
    refreshButton.isEnabled = available
    previousButton.isEnabled = available && pageOffset > 0
    nextButton.isEnabled = available && pageOffset + PAGE_SIZE < total
  }

  private fun label(value: String, size: Float) =
      TextView(activity).apply {
        text = value
        textSize = size
        setPadding(0, dp(5), 0, dp(5))
        setTextIsSelectable(true)
      }

  private fun button(title: Int, action: () -> Unit) =
      Button(activity).apply {
        setText(title)
        isAllCaps = false
        setOnClickListener { action() }
      }

  private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

  private fun close() {
    if (closed) return
    closed = true
    children.toList().forEach { it.dismiss() }
    ChromeDocumentPicker.forget(activity)
    activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
    val api = service
    val token = reviewToken
    if (api != null && token != null) worker.execute { runCatching { api.cancel(token) } }
    worker.shutdown()
    connection?.close()
    service = null
    dismissed()
  }

  companion object {
    private const val PAGE_SIZE = 8
  }
}
