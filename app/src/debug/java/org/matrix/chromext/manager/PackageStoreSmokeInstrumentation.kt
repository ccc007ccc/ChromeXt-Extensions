package org.matrix.chromext.manager

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import org.matrix.chromext.extensions.ExtensionExecutionState
import org.matrix.chromext.extensions.ExtensionPackageException
import org.matrix.chromext.extensions.ExtensionRepository
import org.matrix.chromext.extensions.PackageError
import org.matrix.chromext.utils.Log

/**
 * Debug-only Android filesystem/ART verification; never launches an activity or runs extension JS.
 */
class PackageStoreSmokeInstrumentation : Instrumentation() {
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    start()
  }

  override fun onStart() {
    val result = Bundle()
    var resultCode = Activity.RESULT_CANCELED
    val workspace = File(targetContext.cacheDir, "package-smoke-" + UUID.randomUUID())
    try {
      // The manager must work without libxposed classes in its classloader.
      check(
          runCatching {
                targetContext.classLoader.loadClass("io.github.libxposed.api.XposedModule")
              }
              .exceptionOrNull() is ClassNotFoundException)
      Log.i("Standalone manager logging works without an injected framework")
      check(workspace.mkdirs())
      val archive = File(workspace, "fixture.zip")
      writeZip(archive, false)
      val store = File(workspace, "store")
      val repository = ExtensionRepository(store)
      val review = repository.inspect(archive)
      check(review.manifest.name == "Device Package Fixture")
      check(!store.exists())
      val first = repository.install(archive, review)
      val second = repository.install(archive, review)
      check(first.installationId != second.installationId)
      check(first.executionState == ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING)
      check(File(first.packageDirectory, "content.js").isFile)
      val restarted = ExtensionRepository(store)
      check(restarted.list().size == 2)
      val hostile = File(workspace, "hostile.zip")
      writeZip(hostile, true)
      val rejected = runCatching { restarted.inspect(hostile) }.exceptionOrNull()
      check(rejected is ExtensionPackageException) { "Unexpected rejection: " + rejected }
      // Android 14+ can reject unsafe entry names in ZipFile before our path validator runs.
      check(
          rejected.error == PackageError.UNSAFE_PATH ||
              (rejected.error == PackageError.INVALID_ARCHIVE && rejected.cause is ZipException)) {
            "Unexpected rejection category: " + rejected.error + ", cause: " + rejected.cause
          }
      result.putString("traversal_rejection", rejected.error.name)
      check(!File(workspace, "outside.js").exists())
      check(restarted.list().size == 2)
      check(restarted.uninstall(first.installationId))
      check(restarted.list().single().installationId == second.installationId)
      check(restarted.uninstall(second.installationId))
      check(restarted.list().isEmpty())
      val activity = ComponentName(targetContext, ExtensionManagerActivity::class.java)
      @Suppress("DEPRECATION")
      check(targetContext.packageManager.getActivityInfo(activity, 0).exported)
      result.putString(
          "stream",
          "PASS: inspect without writes; distinct imports; persisted reload; traversal rejection; " +
              "isolated removal; manager component; logging without framework API. No extension code executed.\n")
      resultCode = Activity.RESULT_OK
    } catch (error: Throwable) {
      result.putString("stream", "FAIL: " + error.stackTraceToString())
    } finally {
      workspace.deleteRecursively()
    }
    finish(resultCode, result)
  }

  private fun writeZip(file: File, hostile: Boolean) {
    ZipOutputStream(file.outputStream()).use { archive ->
      val manifest =
          """{"manifest_version":3,"name":"Device Package Fixture","version":"1.0","permissions":["storage"],"content_scripts":[{"matches":["https://example.com/*"],"js":["content.js"]}]}"""
      mapOf(
              "manifest.json" to manifest,
              "content.js" to "throw new Error('Package inspection must never execute this file');",
          )
          .forEach { (name, text) ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(text.toByteArray(Charsets.UTF_8))
            archive.closeEntry()
          }
      if (hostile) {
        archive.putNextEntry(ZipEntry("../outside.js"))
        archive.write(byteArrayOf(1))
        archive.closeEntry()
      }
    }
  }
}
