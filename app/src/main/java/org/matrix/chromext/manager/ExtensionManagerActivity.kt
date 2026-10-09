package org.matrix.chromext.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import org.matrix.chromext.R
import org.matrix.chromext.extensions.ExtensionInspection
import org.matrix.chromext.extensions.ExtensionManifest
import org.matrix.chromext.extensions.ExtensionRepository
import org.matrix.chromext.extensions.InstalledExtension
import org.matrix.chromext.extensions.PackageLimits

/** The package manager never executes code from an imported extension. */
class ExtensionManagerActivity : Activity() {
  private val worker = Executors.newSingleThreadExecutor()
  private val limits = PackageLimits()
  private lateinit var repository: ExtensionRepository
  private lateinit var entries: LinearLayout
  private lateinit var status: TextView
  private lateinit var importButton: Button
  private lateinit var refreshButton: Button
  private var busy = false
  private var reviewFile: File? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    repository = ExtensionRepository(File(filesDir, "extensions"), limits)
    val column =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(24), dp(32), dp(24), dp(24))
        }
    column.addView(label(getString(R.string.app_name), 25f))
    column.addView(label(getString(R.string.manager_preview), 15f))
    column.addView(label(getString(R.string.manager_runtime_pending), 16f))
    importButton =
        Button(this).apply {
          setText(R.string.manager_import)
          setOnClickListener { pickArchive() }
        }
    column.addView(importButton)
    refreshButton =
        Button(this).apply {
          setText(R.string.manager_refresh)
          setOnClickListener { if (!busy) refresh() }
        }
    column.addView(refreshButton)
    status = label("", 14f)
    column.addView(status)
    entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(entries)
    setContentView(
        ScrollView(this).apply {
          fitsSystemWindows = true
          addView(column)
        })
    // The activity can be recreated while Android's document picker is in front.
    // Recreate the review from the app-private copy instead of trusting saved manifest data.
    savedInstanceState?.getString("reviewFile")?.let { name ->
      if (name.matches(Regex("extension-import-[A-Za-z0-9-]+\\.zip"))) {
        val file = File(cacheDir, name)
        if (file.isFile) {
          inspectArchive(file)
          return
        }
      }
    }
    refresh()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    reviewFile?.let { outState.putString("reviewFile", it.name) }
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    worker.shutdown()
    if (isFinishing) reviewFile?.delete()
    super.onDestroy()
  }

  private fun pickArchive() {
    if (busy) return
    startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream")),
        PICK_ARCHIVE)
  }

  @Deprecated("Uses the platform document picker without an AndroidX dependency.")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != PICK_ARCHIVE || resultCode != RESULT_OK) return
    val uri = data?.data ?: return
    setBusy(true, getString(R.string.manager_checking))
    worker.execute {
      var file: File? = null
      try {
        file = copyArchive(uri)
        val inspection = repository.inspect(file)
        val reviewedFile = file
        runOnUiThread {
          if (isFinishing || isDestroyed) {
            reviewedFile.delete()
          } else {
            reviewFile = reviewedFile
            setBusy(false)
            review(inspection, reviewedFile)
          }
        }
      } catch (error: Exception) {
        file?.delete()
        showError(error)
      }
    }
  }

  private fun inspectArchive(file: File) {
    setBusy(true, getString(R.string.manager_checking))
    worker.execute {
      try {
        val inspection = repository.inspect(file)
        runOnUiThread {
          if (!isFinishing && !isDestroyed) {
            reviewFile = file
            setBusy(false)
            review(inspection, file)
          }
        }
      } catch (error: Exception) {
        file.delete()
        showError(error)
      }
    }
  }

  private fun copyArchive(uri: Uri): File {
    val file = File.createTempFile("extension-import-", ".zip", cacheDir)
    try {
      val source =
          contentResolver.openInputStream(uri)
              ?: throw IllegalArgumentException(getString(R.string.manager_cannot_read))
      source.use { input ->
        file.outputStream().use { output ->
          val buffer = ByteArray(32 * 1024)
          var count = 0L
          while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            require(count <= limits.maxArchiveBytes) { getString(R.string.manager_archive_limit) }
            output.write(buffer, 0, size)
          }
        }
      }
      return file
    } catch (error: Exception) {
      file.delete()
      throw error
    }
  }

  private fun review(inspection: ExtensionInspection, file: File) {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.manager_review, inspection.manifest.name))
        .setMessage(details(inspection.manifest) + "\n\n" + getString(R.string.manager_review_note))
        .setPositiveButton(R.string.manager_import_only) { _, _ ->
          reviewFile = null
          setBusy(true, getString(R.string.manager_importing))
          worker.execute {
            try {
              repository.install(file, inspection)
              file.delete()
              refreshFromWorker()
            } catch (error: Exception) {
              file.delete()
              showError(error)
            }
          }
        }
        .setNegativeButton(android.R.string.cancel) { _, _ -> discardReview(file) }
        .setOnCancelListener { discardReview(file) }
        .show()
  }

  private fun discardReview(file: File) {
    reviewFile = null
    file.delete()
    refresh()
  }

  private fun details(manifest: ExtensionManifest): String = buildString {
    append(getString(R.string.manager_version, manifest.version, manifest.manifestVersion))
    manifest.description?.let { append("\n\n").append(it) }
    append("\n\n").append(getString(R.string.manager_permissions))
    append("\n")
        .append(
            manifest.permissions.joinToString("\n").ifEmpty { getString(R.string.manager_none) })
    append("\n\n").append(getString(R.string.manager_hosts))
    append("\n")
        .append(
            manifest.requestedHostPatterns.joinToString("\n").ifEmpty {
              getString(R.string.manager_none)
            })
    val contentHosts = manifest.contentScripts.flatMap { it.matches }.distinct()
    append("\n\n").append(getString(R.string.manager_content_hosts))
    append("\n")
        .append(contentHosts.joinToString("\n").ifEmpty { getString(R.string.manager_none) })
    append("\n\n").append(getString(R.string.manager_optional))
    append("\n")
        .append(
            (manifest.optionalPermissions + manifest.optionalHostPatterns)
                .distinct()
                .joinToString("\n")
                .ifEmpty { getString(R.string.manager_none) })
  }

  private fun refresh() {
    setBusy(true, getString(R.string.manager_loading))
    worker.execute { refreshFromWorker() }
  }

  private fun refreshFromWorker() {
    try {
      val installed = repository.list()
      runOnUiThread {
        if (!isFinishing && !isDestroyed) {
          setBusy(
              false,
              resources.getQuantityString(R.plurals.manager_count, installed.size, installed.size))
          entries.removeAllViews()
          if (installed.isEmpty()) entries.addView(label(getString(R.string.manager_empty), 16f))
          installed.forEach { entry -> addEntry(entry) }
        }
      }
    } catch (error: Exception) {
      showError(error)
    }
  }

  private fun addEntry(entry: InstalledExtension) {
    val box =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(0, dp(20), 0, dp(12))
        }
    box.addView(label(entry.manifest.name, 20f))
    box.addView(
        label(
            getString(
                R.string.manager_version, entry.manifest.version, entry.manifest.manifestVersion),
            14f))
    box.addView(label(getString(R.string.manager_inactive), 15f))
    box.addView(
        Button(this).apply {
          setText(R.string.manager_details)
          setOnClickListener {
            if (!busy)
                AlertDialog.Builder(this@ExtensionManagerActivity)
                    .setTitle(entry.manifest.name)
                    .setMessage(details(entry.manifest))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
          }
        })
    box.addView(
        Button(this).apply {
          setText(R.string.manager_uninstall)
          setOnClickListener {
            if (!busy)
                AlertDialog.Builder(this@ExtensionManagerActivity)
                    .setTitle(getString(R.string.manager_remove_title, entry.manifest.name))
                    .setMessage(R.string.manager_remove_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.manager_uninstall) { _, _ ->
                      setBusy(true, getString(R.string.manager_removing))
                      worker.execute {
                        try {
                          repository.uninstall(entry.installationId)
                          refreshFromWorker()
                        } catch (error: Exception) {
                          showError(error)
                        }
                      }
                    }
                    .show()
          }
        })
    entries.addView(box)
  }

  private fun showError(error: Exception) {
    runOnUiThread {
      if (!isFinishing && !isDestroyed) {
        setBusy(false, getString(R.string.manager_failed))
        AlertDialog.Builder(this)
            .setTitle(R.string.manager_failed)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
      }
    }
  }

  private fun setBusy(value: Boolean, message: String = "") {
    busy = value
    importButton.isEnabled = !value
    refreshButton.isEnabled = !value
    status.text = message
    status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
  }

  private fun label(value: String, size: Float): TextView =
      TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
        setTextIsSelectable(true)
      }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  companion object {
    private const val PICK_ARCHIVE = 101
  }
}
