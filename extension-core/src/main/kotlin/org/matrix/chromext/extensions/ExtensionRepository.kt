package org.matrix.chromext.extensions

import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Stores independent ZIP imports. Manifest keys and browser_specific_settings IDs never identify an
 * installation. Installing the same archive twice creates two independent packages.
 *
 * The caller keeps the selected ZIP in its own private cache while showing [inspect] for review,
 * then passes that exact review to [install]. Package import does not grant permissions or start
 * scripts.
 */
class ExtensionRepository(
    rootDirectory: File,
    private val limits: PackageLimits = PackageLimits()
) {
  private val root = rootDirectory.canonicalFile
  private val reader = ExtensionPackageReader(limits)

  /** Reads the archive without creating or changing repository files. */
  fun inspect(archive: File): ExtensionInspection = reader.read(archive)

  /**
   * Copies into a private staging directory, validates the reviewed bytes, then publishes
   * atomically.
   */
  fun install(archive: File, reviewed: ExtensionInspection): InstalledExtension =
      withStoreLock(createRoot = true) { installLocked(archive, reviewed) }

  private fun installLocked(archive: File, reviewed: ExtensionInspection): InstalledExtension {
    var staging: File? = null
    var failure: Throwable? = null
    try {
      if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create extension storage")
      val installationId = UUID.randomUUID().toString()
      val destination = File(root, installationId)
      if (destination.exists()) throw IOException("Installation ID collision")
      val stage = File(root, ".staging-${UUID.randomUUID()}")
      if (!stage.mkdir()) throw IOException("Cannot create package staging directory")
      staging = stage
      val snapshot = File(stage, "archive.zip")
      archive.inputStream().use { input ->
        snapshot.outputStream().use { output ->
          val buffer = ByteArray(8192)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limits.maxArchiveBytes) {
              throw ExtensionPackageException(
                  PackageError.LIMIT_EXCEEDED, "ZIP package exceeds the archive size limit")
            }
            output.write(buffer, 0, count)
          }
          output.fd.sync()
        }
      }
      val packageDirectory = File(stage, "package")
      if (!packageDirectory.mkdir()) throw IOException("Cannot create package directory")
      val validated = reader.read(snapshot, packageDirectory)
      if (validated.sha256 != reviewed.sha256 || validated.manifest != reviewed.manifest) {
        throw ExtensionPackageException(
            PackageError.REVIEW_MISMATCH,
            "The ZIP changed after review. Review it again before importing.")
      }
      val installedAt = System.currentTimeMillis()
      val metadata =
          JsonObject().apply {
            addProperty("format_version", 1)
            addProperty("installation_id", installationId)
            addProperty("archive_sha256", validated.sha256)
            addProperty("installed_at_epoch_millis", installedAt)
            addProperty(
                "execution_state", ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING.name)
          }
      File(stage, "metadata.json").outputStream().use { output ->
        output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        output.fd.sync()
      }
      if (!snapshot.delete()) throw IOException("Cannot remove staging archive")
      if (!stage.renameTo(destination)) throw IOException("Cannot publish extension installation")
      staging = null
      return InstalledExtension(
          installationId,
          validated.manifest,
          installedAt,
          validated.sha256,
          File(destination, "package"),
      )
    } catch (exception: ExtensionPackageException) {
      failure = exception
      throw exception
    } catch (exception: IOException) {
      val wrapped =
          ExtensionPackageException(
              PackageError.IO_ERROR, "Could not store the extension package", exception)
      failure = wrapped
      throw wrapped
    } finally {
      try {
        staging?.let(::deleteTree)
      } catch (cleanup: IOException) {
        if (failure != null) failure.addSuppressed(cleanup)
        else
            throw ExtensionPackageException(
                PackageError.IO_ERROR, "Could not clean package staging", cleanup)
      }
    }
  }

  /**
   * Invalid or incomplete installations are excluded; one damaged package cannot hide other
   * imports.
   */
  fun list(): List<InstalledExtension> = withStoreLock(createRoot = false) { listLocked() }

  private fun listLocked(): List<InstalledExtension> {
    return root
        .listFiles()
        ?.mapNotNull { directory ->
          if (!validInstallationId(directory.name) ||
              !directory.isDirectory ||
              !isDirectChild(directory)) {
            return@mapNotNull null
          }
          try {
            val metadataFile = containedFile(directory, "metadata.json")
            val metadata =
                metadataFile.inputStream().use {
                  parseJsonObject(
                      readBoundedText(it, limits.maxManifestBytes, "metadata.json"),
                      "metadata.json")
                }
            if (metadata.get("format_version")?.asInt != 1 ||
                metadata.get("installation_id")?.asString != directory.name) {
              return@mapNotNull null
            }
            val sha256 = metadata.get("archive_sha256")?.asString ?: return@mapNotNull null
            if (!Regex("[0-9a-f]{64}").matches(sha256)) return@mapNotNull null
            val installedAt =
                metadata.get("installed_at_epoch_millis")?.asLong ?: return@mapNotNull null
            if (installedAt < 0) return@mapNotNull null
            val packageDirectory = containedFile(directory, "package")
            if (packageDirectory.canonicalFile != packageDirectory.absoluteFile ||
                !packageDirectory.isDirectory) {
              return@mapNotNull null
            }
            val paths = storedResourcePaths(packageDirectory)
            val manifest =
                ManifestParser(paths) { path ->
                      containedFile(packageDirectory, path).inputStream().use {
                        readBoundedText(it, limits.maxManifestBytes, path)
                      }
                    }
                    .parse(
                        containedFile(packageDirectory, "manifest.json").inputStream().use {
                          readBoundedText(it, limits.maxManifestBytes, "manifest.json")
                        })
            InstalledExtension(directory.name, manifest, installedAt, sha256, packageDirectory)
          } catch (_: IOException) {
            null
          } catch (_: ExtensionPackageException) {
            null
          } catch (_: RuntimeException) {
            null
          }
        }
        ?.sortedWith(
            compareByDescending<InstalledExtension> { it.installedAtEpochMillis }
                .thenBy { it.installationId }) ?: emptyList()
  }

  /**
   * Only a generated installation ID is accepted; names and manifest IDs are never filesystem
   * paths.
   */
  fun uninstall(installationId: String): Boolean {
    if (!validInstallationId(installationId)) {
      throw ExtensionPackageException(
          PackageError.INVALID_INSTALLATION_ID, "Invalid extension installation ID")
    }
    return withStoreLock(createRoot = false) { uninstallLocked(installationId) }
  }

  private fun uninstallLocked(installationId: String): Boolean {
    val directory = File(root, installationId)
    if (!isDirectChild(directory)) {
      throw ExtensionPackageException(
          PackageError.INVALID_INSTALLATION_ID, "Installation path leaves extension storage")
    }
    if (!directory.exists()) return false
    try {
      deleteTree(directory)
      return true
    } catch (exception: IOException) {
      throw ExtensionPackageException(
          PackageError.IO_ERROR, "Could not remove the extension package", exception)
    }
  }

  private fun storedResourcePaths(packageDirectory: File): Set<String> {
    val paths = mutableSetOf<String>()
    var entries = 0L
    var totalBytes = 0L
    fun visit(directory: File, prefix: String) {
      val children = directory.listFiles() ?: throw IOException("Cannot read package directory")
      for (file in children) {
        // ZIP entries may omit their parent directories; allow the bounded implicit directories.
        if (++entries > limits.maxEntries.toLong() * 65)
            throw IOException("Stored package has too many entries")
        if (file.canonicalFile != file.absoluteFile)
            throw IOException("Stored package contains a symbolic link")
        val path = packagePath(prefix + file.name, file.isDirectory)
        if (file.isDirectory) visit(file, "$path/")
        else {
          if (!file.isFile || file.length() > limits.maxSingleFileBytes)
              throw IOException("Invalid stored package file")
          totalBytes += file.length()
          if (totalBytes > limits.maxExpandedBytes)
              throw IOException("Stored package exceeds its size limit")
          paths.add(path)
          if (paths.size > limits.maxEntries) throw IOException("Stored package has too many files")
        }
      }
    }
    visit(packageDirectory, "")
    return paths
  }

  private fun isDirectChild(file: File): Boolean =
      file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == root

  private fun validInstallationId(id: String): Boolean =
      Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id)

  /**
   * One JVM monitor avoids OverlappingFileLockException between Activity/repository instances. The
   * OS lock also spans processes and is released on process death. Consequently, staging
   * directories found after acquiring it are abandoned, never a concurrent install's active work.
   */
  private fun <T> withStoreLock(createRoot: Boolean, operation: () -> T): T =
      synchronized(processStoreLock) {
        try {
          if (!root.exists() && !createRoot) return@synchronized operation()
          if (!root.isDirectory && !root.mkdirs())
              throw IOException("Cannot create extension storage")
          val lockFile = containedFile(root, ".store-lock")
          if (lockFile.canonicalFile != lockFile.absoluteFile)
              throw IOException("Invalid repository lock file")
          RandomAccessFile(lockFile, "rw").use { handle ->
            val lock = handle.channel.lock()
            try {
              val children = root.listFiles() ?: throw IOException("Cannot list extension storage")
              children
                  .filter {
                    it.name.startsWith(".staging-") &&
                        validInstallationId(it.name.removePrefix(".staging-"))
                  }
                  .forEach(::deleteTree)
              operation()
            } finally {
              lock.release()
            }
          }
        } catch (exception: IOException) {
          throw ExtensionPackageException(
              PackageError.IO_ERROR, "Could not access extension storage", exception)
        }
      }

  private companion object {
    val processStoreLock = Any()
  }
}
