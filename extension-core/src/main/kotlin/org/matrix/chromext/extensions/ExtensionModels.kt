package org.matrix.chromext.extensions

import java.io.File

/** The package store does not implement or activate a WebExtensions runtime. */
enum class ExtensionExecutionState {
  NOT_RUNNABLE_RUNTIME_PENDING
}

data class PackageLimits(
    val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 4096,
    val maxExpandedBytes: Long = 128L * 1024 * 1024,
    val maxSingleFileBytes: Long = 32L * 1024 * 1024,
    val maxManifestBytes: Long = 1024L * 1024,
    val maxCompressionRatio: Int = 1000,
) {
  init {
    require(maxArchiveBytes > 0 && maxEntries > 0 && maxExpandedBytes > 0)
    require(maxSingleFileBytes > 0 && maxManifestBytes > 0 && maxCompressionRatio > 0)
  }
}

enum class PackageError {
  INVALID_ARCHIVE,
  UNSAFE_PATH,
  DUPLICATE_ENTRY,
  LIMIT_EXCEEDED,
  INVALID_MANIFEST,
  MISSING_RESOURCE,
  REVIEW_MISMATCH,
  INVALID_INSTALLATION_ID,
  IO_ERROR,
}

class ExtensionPackageException(
    val error: PackageError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class BackgroundDeclaration(
    val serviceWorker: String? = null,
    val scripts: List<String> = emptyList(),
    val page: String? = null,
    val persistent: Boolean? = null,
    val type: String? = null,
)

data class ContentScriptDeclaration(
    val matches: List<String>,
    val excludeMatches: List<String>,
    val js: List<String>,
    val css: List<String>,
    val runAt: String?,
    val allFrames: Boolean,
    val matchAboutBlank: Boolean,
    val world: String?,
)

data class ActionDeclaration(val kind: String, val defaultPopup: String?, val defaultTitle: String?)

data class OptionsDeclaration(val page: String, val openInTab: Boolean?)

/** Declared capabilities are recorded for review; none are granted or executed. */
data class ExtensionManifest(
    val manifestVersion: Int,
    val name: String,
    val version: String,
    val description: String?,
    val defaultLocale: String?,
    val permissions: List<String>,
    val hostPermissions: List<String>,
    val optionalPermissions: List<String>,
    val optionalHostPermissions: List<String>,
    val background: BackgroundDeclaration?,
    val contentScripts: List<ContentScriptDeclaration>,
    val action: ActionDeclaration?,
    val optionsPage: String?,
    val optionsUi: OptionsDeclaration?,
    val rawJson: String,
) {
  /** MV2 declares host permissions in permissions; MV3 has a separate field. */
  val requestedHostPatterns: List<String>
    get() = (hostPermissions + permissions.filter(::isHostPattern)).distinct()

  val optionalHostPatterns: List<String>
    get() = (optionalHostPermissions + optionalPermissions.filter(::isHostPattern)).distinct()

  private fun isHostPattern(value: String) = value == "<all_urls>" || value.contains("://")
}

data class ExtensionInspection(
    val manifest: ExtensionManifest,
    val sha256: String,
    val archiveSizeBytes: Long,
    val expandedSizeBytes: Long,
    val entryCount: Int,
    val executionState: ExtensionExecutionState =
        ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING,
)

data class InstalledExtension(
    val installationId: String,
    val manifest: ExtensionManifest,
    val installedAtEpochMillis: Long,
    val archiveSha256: String,
    val packageDirectory: File,
    val executionState: ExtensionExecutionState =
        ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING,
)
