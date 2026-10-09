package org.matrix.chromext.probe

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Clicks actual accessibility nodes, only while Chrome or the system picker owns the window. */
class NativeManagerProbe : Instrumentation() {
  private lateinit var automation: UiAutomation
  private var mode = "flow"
  private var archiveFile: File? = null
  private var downloadUri: Uri? = null
  private val chrome = setOf("com.android.chrome")
  private val documents =
      setOf("com.google.android.documentsui", "com.android.documentsui", "com.coloros.filemanager")
  private val nonce = UUID.randomUUID().toString().take(8)
  private val fixtureName = "ChromeXt UI probe $nonce"
  private val fixtureFileName = "chromext-ui-$nonce.zip"
  private val importLabels = setOf("导入扩展 ZIP", "Import extension ZIP")
  private val removeLabels = setOf("移除扩展包", "Remove package")
  private val detailLabels = setOf("查看声明的权限", "View requested permissions")
  private val cancelLabels = setOf("取消", "Cancel", "CANCEL")
  private val okLabels = setOf("确定", "OK", "Ok")

  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    mode = arguments?.getString("mode") ?: "flow"
    start()
  }

  override fun onStart() {
    val result = Bundle()
    var code = Activity.RESULT_CANCELED
    try {
      progress("Connecting accessibility automation")
      automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
      automation.serviceInfo =
          automation.serviceInfo.apply {
            flags =
                flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
          }
      if (mode == "status") {
        result.putString(
            "stream",
            "Chrome foreground: ${automation.rootInActiveWindow?.packageName?.toString() in chrome}\n")
      } else {
        check(mode in setOf("flow", "picker")) { "Unknown mode" }
        if (mode == "picker") createFixture()
        flow(result)
      }
      code = Activity.RESULT_OK
    } catch (error: Throwable) {
      result.putString("stream", "FAIL: ${error.stackTraceToString()}")
      if (::automation.isInitialized) result.putString("controls", diagnosticControls())
    } finally {
      archiveFile?.delete()
      downloadUri?.let { targetContext.contentResolver.delete(it, null, null) }
    }
    finish(code, result)
  }

  private fun flow(result: Bundle) {
    val name = fixtureName
    result.putString("fixture_name", name)
    progress("Opening the native Chrome menu")
    root(chrome)
    if (find(root(chrome)) {
      it.viewIdResourceName == "android:id/alertTitle" &&
          it.text?.toString() in setOf("操作失败", "Operation failed")
    } != null) {
      click(await(chrome) { it.viewIdResourceName == "android:id/button1" }, chrome)
      await(chrome) { it.viewIdResourceName?.endsWith(":id/extension_manager_import") == true }
    }
    if (find(root(chrome)) {
      it.viewIdResourceName?.endsWith(":id/extension_manager_panel") == true
    } == null) {
      // A web page may itself contain the word "Extensions". Always enter the actual browser menu.
      click(await(chrome) { it.viewIdResourceName == "com.android.chrome:id/menu_button" }, chrome)
      click(
          await(chrome) {
            it.text?.toString() in setOf("扩展", "Extensions") &&
                it.viewIdResourceName?.startsWith("com.android.chrome:id/") == true
          },
          chrome)
    }
    progress("Waiting for the Chrome-owned package panel")
    await(chrome) {
      it.viewIdResourceName?.endsWith(":id/extension_manager_import") == true && it.isEnabled
    }
    check(
        find(root(chrome)) {
          it.text?.contains("runtime is under development") == true ||
              it.text?.contains("扩展运行时正在开发") == true
        } != null)

    // Cancel one real review before accepting the next one.
    progress("Reviewing the fixed fixture file")
    reviewFixture(name)
    click(await(chrome) { it.text?.toString() in cancelLabels }, chrome)
    await(chrome) { it.text?.toString() in importLabels && it.isEnabled }
    check(find(root(chrome)) { it.text?.toString() == name } == null) {
      "Cancelled review installed a package"
    }
    if (mode == "picker") review(name) else reviewFixture(name)
    progress("Importing the inactive fixture")
    click(await(chrome) { it.text?.toString() in setOf("导入扩展包", "Import package") }, chrome)
    val rowName = await(chrome) { it.text?.toString() == name }
    val row = checkNotNull(rowName.parent)
    check(
        find(row) { it.text?.contains("未启用") == true || it.text?.contains("Inactive") == true } !=
            null)
    click(checkNotNull(find(row) { it.text?.toString() in detailLabels }), chrome)
    progress("Checking declared permissions and removing the fixture")
    assertPermissions()
    click(await(chrome) { it.text?.toString() in okLabels }, chrome)
    val installedRow = checkNotNull(await(chrome) { it.text?.toString() == name }.parent)
    click(checkNotNull(find(installedRow) { it.text?.toString() in removeLabels }), chrome)
    await(chrome) { it.text?.contains(name) == true }
    click(
        await(chrome) {
          it.text?.toString() in removeLabels && it.viewIdResourceName == "android:id/button1"
        },
        chrome)
    await(chrome) { it.text?.toString() in importLabels && it.isEnabled }
    check(find(root(chrome)) { it.text?.toString() == name } == null) {
      "Removed fixture remains visible"
    }
    click(await(chrome) { it.text?.toString() in cancelLabels }, chrome)
    await(chrome) { it.viewIdResourceName == "com.android.chrome:id/menu_button" }
    result.putString(
        "stream",
        "PASS: Chrome menu -> Chrome-owned native panel -> ${if (mode == "picker") "system document picker" else "fixed fixture file (picker bypassed)"} -> cancel review -> import inactive fixture -> permission details -> remove -> close. No extension code executed.\n")
  }

  private fun reviewFixture(name: String) {
    val done = CountDownLatch(1)
    var accepted = false
    var detail: String? = null
    automation.adoptShellPermissionIdentity(Manifest.permission.DUMP)
    try {
      targetContext.sendOrderedBroadcast(
          Intent("io.github.ccc007ccc.chromextextensions.DEBUG")
              .setPackage("com.android.chrome")
              .putExtra("operation", "review-fixture")
              .putExtra("nonce", nonce),
          null,
          object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              accepted = resultCode == Activity.RESULT_OK
              detail = resultData
              done.countDown()
            }
          },
          Handler(Looper.getMainLooper()),
          Activity.RESULT_CANCELED,
          null,
          null)
      check(done.await(5, TimeUnit.SECONDS) && accepted) {
        "Direct fixture review was not accepted: $detail"
      }
    } finally {
      automation.dropShellPermissionIdentity()
    }
    await(chrome) { it.text?.contains(name) == true }
    assertPermissions()
  }

  private fun review(name: String) {
    click(await(chrome) { it.text?.toString() in importLabels && it.isEnabled }, chrome)
    await(documents) { true }
    if (find(root(documents), ::isFixtureFile) == null) {
      val colorOs = root(documents).packageName?.toString() == "com.coloros.filemanager"
      click(
          await(documents) {
            if (colorOs) it.viewIdResourceName == "com.coloros.filemanager:id/search_view"
            else
                it.viewIdResourceName?.endsWith(":id/option_menu_search") == true ||
                    it.contentDescription?.toString() in setOf("搜索", "Search")
          },
          documents)
      val input =
          await(documents) { it.className?.toString() == "android.widget.EditText" && it.isFocused }
      check(
          input.performAction(
              AccessibilityNodeInfo.ACTION_SET_TEXT,
              Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    fixtureFileName.removeSuffix(".zip"))
              })) {
            "System picker did not accept the fixture search"
          }
      root(documents)
      automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), true)
      automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER), true)
    }
    click(await(documents, ::isFixtureFile), documents)
    await(chrome) { it.text?.contains(name) == true }
    assertPermissions()
  }

  private fun isFixtureFile(node: AccessibilityNodeInfo) =
      node.text?.toString()?.replace("\n", "")?.replace("\r", "") in
          setOf(fixtureFileName, fixtureFileName.removeSuffix(".zip")) &&
          node.viewIdResourceName?.substringAfterLast('/') in
              setOf("file_list_item_title", "title", "line1", "text1")

  private fun createFixture(): Uri {
    val resolver = targetContext.contentResolver
    val file = File(targetContext.cacheDir, "uri-fixtures/$fixtureFileName")
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    archiveFile = file
    ZipOutputStream(file.outputStream()).use { zip ->
      mapOf(
              "manifest.json" to
                  """{"manifest_version":3,"name":${JSONObject.quote(fixtureName)},"version":"1.0","permissions":["storage"],"host_permissions":["https://example.test/*"],"content_scripts":[{"matches":["https://example.test/*"],"js":["content.js"]}]}""",
              "content.js" to
                  "throw new Error('This package must remain inactive during the UI probe');")
          .forEach { (path, value) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(value.toByteArray())
            zip.closeEntry()
          }
    }
    if (mode == "picker") {
      check(Build.VERSION.SDK_INT >= 29) { "The system picker probe requires Android 10 or newer" }
      val uri =
          checkNotNull(
              resolver.insert(
                  MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                  ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fixtureFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ChromeXt-tests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                  }))
      downloadUri = uri
      checkNotNull(resolver.openOutputStream(uri)).use { output ->
        file.inputStream().use { it.copyTo(output) }
      }
      resolver.update(
          uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
    return checkNotNull(downloadUri)
  }

  private fun assertPermissions() {
    await(chrome) {
      it.text?.contains("storage") == true && it.text?.contains("https://example.test/*") == true
    }
  }

  private fun diagnosticControls(): String {
    val active = automation.rootInActiveWindow ?: return "No active window"
    if (active.packageName?.toString() !in chrome + documents) return "Outside the test apps"
    val ids = linkedSetOf<String>()
    val failure =
        find(active) {
          it.viewIdResourceName == "android:id/alertTitle" &&
              it.text?.toString() in setOf("操作失败", "Operation failed")
        } != null
    fun visit(node: AccessibilityNodeInfo) {
      if (!node.isVisibleToUser) return
      node.viewIdResourceName?.let { ids.add(it) }
      if (failure && node.viewIdResourceName == "android:id/message")
          ids.add("manager-error:${node.text}")
      if (node.text?.contains(nonce) == true || node.contentDescription?.contains(nonce) == true)
          ids.add(
              "fixture-control:${node.viewIdResourceName}:text=${node.text}:description=${node.contentDescription}")
      for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    visit(active)
    return ids.take(100).joinToString("\n")
  }

  private fun progress(message: String) {
    sendStatus(0, Bundle().apply { putString("stream", message + "\n") })
  }

  private fun root(packages: Set<String>): AccessibilityNodeInfo {
    val node = checkNotNull(automation.rootInActiveWindow) { "No focused window" }
    check(node.packageName?.toString() in packages) {
      "The phone switched away from the test window; no further UI actions performed"
    }
    return node
  }

  private fun find(
      node: AccessibilityNodeInfo,
      predicate: (AccessibilityNodeInfo) -> Boolean
  ): AccessibilityNodeInfo? {
    if (node.isVisibleToUser && predicate(node)) return node
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      find(child, predicate)?.let {
        return it
      }
    }
    return null
  }

  private fun await(
      packages: Set<String>,
      predicate: (AccessibilityNodeInfo) -> Boolean
  ): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + 8_000
    while (SystemClock.uptimeMillis() < deadline) {
      val node = automation.rootInActiveWindow
      if (node != null && node.packageName?.toString() in packages) {
        find(node, predicate)?.let {
          return it
        }
      } else if (node != null && node.packageName?.toString() !in chrome + documents) {
        error("The phone switched away from the test window; no further UI actions performed")
      }
      SystemClock.sleep(100)
    }
    error("Expected test control was not available in the focused window")
  }

  private fun click(node: AccessibilityNodeInfo, packages: Set<String>) {
    root(packages)
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable) target = target.parent
    check(target != null && target.packageName?.toString() in packages && target.isEnabled) {
      "Test control did not accept a click"
    }
    // Some OEM pickers return false after synchronously closing their window. Each caller checks
    // the resulting UI state, which is the observable success condition.
    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
  }
}
