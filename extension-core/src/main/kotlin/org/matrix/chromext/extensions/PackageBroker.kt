package org.matrix.chromext.extensions

import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Fields safe to send across Binder. Private filesystem paths and raw manifest JSON stay local. */
data class PackageDescription(
    @field:SerializedName("installationId") val installationId: String?,
    @field:SerializedName("name") val name: String,
    @field:SerializedName("version") val version: String,
    @field:SerializedName("manifestVersion") val manifestVersion: Int,
    @field:SerializedName("description") val description: String?,
    @field:SerializedName("permissions") val permissions: List<String>,
    @field:SerializedName("hosts") val hosts: List<String>,
    @field:SerializedName("contentHosts") val contentHosts: List<String>,
    @field:SerializedName("optionalPermissions") val optionalPermissions: List<String>,
    @field:SerializedName("executionState")
    val executionState: String = ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING.name,
) {
  companion object {
    fun from(manifest: ExtensionManifest, installationId: String? = null) =
        PackageDescription(
            installationId,
            manifest.name,
            manifest.version,
            manifest.manifestVersion,
            manifest.description,
            manifest.permissions,
            manifest.requestedHostPatterns,
            manifest.contentScripts.flatMap { it.matches }.distinct(),
            (manifest.optionalPermissions + manifest.optionalHostPatterns).distinct())
  }
}

data class PackagePage(
    @field:SerializedName("total") val total: Int,
    @field:SerializedName("offset") val offset: Int,
    @field:SerializedName("entries") val entries: List<PackageDescription>,
)

data class PackageReview(
    @field:SerializedName("token") val token: String,
    @field:SerializedName("sha256") val sha256: String,
    @field:SerializedName("entry") val entry: PackageDescription,
)

/** A review authorizes only its originating UID, once, while its private snapshot is still live. */
class PackageBroker(
    private val repository: ExtensionRepository,
    private val cache: File,
    private val limits: PackageLimits = PackageLimits(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reviewLifetimeMillis: Long = 5 * 60_000,
    private val maxReviews: Int = 4,
) : AutoCloseable {
  private class Pending(val owner: Int, val file: File, val created: Long) {
    var inspection: ExtensionInspection? = null
  }

  private val lock = Any()
  private val pending = mutableMapOf<String, Pending>()
  private var closed = false

  init {
    require(reviewLifetimeMillis > 0 && maxReviews > 0)
    require(cache.isDirectory || cache.mkdirs()) { "Cannot create review cache" }
    // This directory belongs exclusively to the service, which has one broker instance.
    cache
        .listFiles()
        ?.filter { it.name.matches(Regex("review-[0-9a-f-]{36}\\.zip")) }
        ?.forEach { it.delete() }
  }

  fun list(offset: Int, limit: Int): PackagePage {
    checkOpen()
    require(offset >= 0 && limit in 1..32) { "Invalid package page" }
    val all = repository.list()
    return PackagePage(
        all.size,
        offset,
        all.drop(offset).take(limit).map {
          PackageDescription(
              it.installationId,
              it.manifest.name.take(512),
              it.manifest.version,
              it.manifest.manifestVersion,
              null,
              emptyList(),
              emptyList(),
              emptyList(),
              emptyList())
        })
  }

  fun describe(id: String): PackageDescription {
    checkOpen()
    val entry =
        repository.list().firstOrNull { it.installationId == id }
            ?: throw ExtensionPackageException(
                PackageError.MISSING_RESOURCE, "Extension is not installed")
    return PackageDescription.from(entry.manifest, id)
  }

  fun review(owner: Int, source: InputStream): PackageReview =
      source.use { input ->
        val token = UUID.randomUUID().toString()
        val entry =
            synchronized(lock) {
              check(!closed) { "Package service disconnected" }
              expire()
              check(pending.size < maxReviews) { "Too many pending package reviews" }
              Pending(owner, File(cache, "review-$token.zip"), clock()).also { pending[token] = it }
            }
        try {
          entry.file.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
              check(clock() - entry.created < reviewLifetimeMillis) { "Package review expired" }
              val count = input.read(buffer)
              if (count < 0) break
              if (count == 0) continue
              total += count
              if (total > limits.maxArchiveBytes)
                  throw ExtensionPackageException(
                      PackageError.LIMIT_EXCEEDED, "ZIP package exceeds the archive size limit")
              output.write(buffer, 0, count)
            }
          }
          val inspected = repository.inspect(entry.file)
          synchronized(lock) {
            expire()
            check(pending[token] === entry) { "Package review expired" }
            entry.inspection = inspected
          }
          PackageReview(token, inspected.sha256, PackageDescription.from(inspected.manifest))
        } catch (error: Throwable) {
          synchronized(lock) { pending.remove(token) }
          entry.file.delete()
          throw error
        }
      }

  fun commit(owner: Int, token: String): PackageDescription {
    val review = take(owner, token)
    try {
      val installed = repository.install(review.file, checkNotNull(review.inspection))
      return PackageDescription.from(installed.manifest, installed.installationId)
    } finally {
      review.file.delete()
    }
  }

  fun cancel(owner: Int, token: String) {
    take(owner, token).file.delete()
  }

  fun remove(installationId: String): Boolean {
    checkOpen()
    return repository.uninstall(installationId)
  }

  fun <T> withResource(id: String, path: String, open: (File) -> T): T {
    checkOpen()
    return repository.withResource(id, path, open)
  }

  private fun checkOpen() = synchronized(lock) { check(!closed) { "Package service disconnected" } }

  fun pruneExpired() = synchronized(lock) { if (!closed) expire() }

  private fun take(owner: Int, token: String): Pending =
      synchronized(lock) {
        check(!closed) { "Package service disconnected" }
        expire()
        val review =
            pending[token] ?: throw IllegalStateException("Package review expired or already used")
        if (review.owner != owner)
            throw SecurityException("Package review belongs to another caller")
        check(review.inspection != null) { "Package review is still in progress" }
        pending.remove(token)
        review
      }

  private fun expire() {
    val now = clock()
    val expired = pending.filterValues { now - it.created >= reviewLifetimeMillis }
    expired.forEach { (token, value) ->
      pending.remove(token)
      value.file.delete()
    }
  }

  override fun close() =
      synchronized(lock) {
        closed = true
        pending.values.forEach { it.file.delete() }
        pending.clear()
      }
}
