package org.matrix.chromext.manager

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import org.matrix.chromext.extensions.ChromeCallerPolicy
import org.matrix.chromext.extensions.ExtensionPackageException
import org.matrix.chromext.extensions.ExtensionRepository
import org.matrix.chromext.extensions.PackageBroker
import org.matrix.chromext.manager.ipc.IExtensionStore

/** The module UID owns packages; each operation authenticates the real Binder caller. */
class ExtensionStoreService : Service() {
  private lateinit var broker: PackageBroker
  private val gson = Gson()
  private val expiry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

  override fun onCreate() {
    super.onCreate()
    broker =
        PackageBroker(
            ExtensionRepository(File(filesDir, "extensions")),
            File(cacheDir, "broker-reviews"),
            clock = { android.os.SystemClock.elapsedRealtime() })
    expiry.scheduleWithFixedDelay(
        { broker.pruneExpired() }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
  }

  override fun onBind(intent: Intent): IBinder? = if (intent.action == ACTION) binder else null

  override fun onDestroy() {
    expiry.shutdownNow()
    broker.close()
    super.onDestroy()
  }

  private val binder =
      object : IExtensionStore.Stub() {
        override fun protocolVersion(): Int = authorized { 1 }

        override fun list(offset: Int, limit: Int): String = authorized {
          encode(broker.list(offset, limit))
        }

        override fun describe(installationId: String): String = authorized {
          encode(broker.describe(installationId))
        }

        override fun review(archive: ParcelFileDescriptor): String =
            archive.use {
              authorized { uid ->
                val review = ArchiveDescriptorInput(archive).use { broker.review(uid, it) }
                try {
                  encode(review)
                } catch (error: Exception) {
                  broker.cancel(uid, review.token)
                  throw error
                }
              }
            }

        override fun commit(reviewToken: String): String = authorized {
          encode(broker.commit(it, reviewToken))
        }

        override fun cancel(reviewToken: String) = authorized { broker.cancel(it, reviewToken) }

        override fun remove(installationId: String): Boolean = authorized {
          broker.remove(installationId)
        }

        override fun openResource(
            installationId: String,
            relativePath: String
        ): ParcelFileDescriptor = authorized {
          broker.withResource(installationId, relativePath) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
          }
        }
      }

  private fun encode(value: Any): String =
      gson.toJson(value).also {
        // Binder's buffer is shared by transactions. Reject oversized reviews instead of truncating
        // permissions.
        require(it.length <= 160_000) {
          "Package metadata is too large to display; use a smaller manifest"
        }
      }

  private fun <T> authorized(operation: (Int) -> T): T {
    val uid = Binder.getCallingUid()
    val identity = Binder.clearCallingIdentity()
    val trusted =
        try {
          if (uid == Process.myUid()) true
          else {
            val chromeUid = packageManager.getPackageUid(ChromeCallerPolicy.PACKAGE, 0)
            @Suppress("DEPRECATION")
            val info =
                packageManager.getPackageInfo(
                    ChromeCallerPolicy.PACKAGE,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val signatures =
                if (Build.VERSION.SDK_INT >= 28) {
                  info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners
                    else it.signingCertificateHistory
                  }
                } else info.signatures
            val digests =
                signatures
                    .orEmpty()
                    .map { signature ->
                      MessageDigest.getInstance("SHA-256")
                          .digest(signature.toByteArray())
                          .joinToString("") { "%02x".format(it) }
                    }
                    .toSet()
            ChromeCallerPolicy.isTrusted(uid, Process.myUid(), chromeUid, digests)
          }
        } catch (_: PackageManager.NameNotFoundException) {
          false
        } finally {
          Binder.restoreCallingIdentity(identity)
        }
    if (!trusted)
        throw SecurityException("Only this module and the signed Chrome host may manage packages")
    try {
      return operation(uid)
    } catch (error: ExtensionPackageException) {
      throw IllegalArgumentException("${error.error.name}: ${error.message}")
    } catch (error: java.io.IOException) {
      throw IllegalStateException("Cannot access extension package storage")
    }
  }

  companion object {
    const val ACTION = "io.github.ccc007ccc.chromextextensions.BIND_EXTENSION_STORE"
  }
}
