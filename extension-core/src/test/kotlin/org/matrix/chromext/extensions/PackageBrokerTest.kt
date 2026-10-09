package org.matrix.chromext.extensions

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageBrokerTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun archive(payload: String = "original"): ByteArray =
      ByteArrayOutputStream()
          .also { bytes ->
            ZipOutputStream(bytes).use { zip ->
              mapOf(
                      "manifest.json" to
                          """{"manifest_version":3,"name":"Broker fixture","version":"1.0","permissions":["storage"]}""",
                      "payload.txt" to payload)
                  .forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                  }
            }
          }
          .toByteArray()

  private fun repository() = ExtensionRepository(File(temporary.root, "store"))

  private fun broker(repository: ExtensionRepository = repository()) =
      PackageBroker(repository, File(temporary.root, "reviews"))

  @Test
  fun ipcWireNamesAndPermissionsSurviveSerialization() {
    val gson = Gson()
    // These are wire fields, so R8 may rename JVM members but must retain their JSON names.
    for (type in
        listOf(
            PackageDescription::class.java, PackagePage::class.java, PackageReview::class.java)) {
      type.declaredFields
          .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
          .forEach { assertEquals(it.name, it.getAnnotation(SerializedName::class.java)?.value) }
    }
    val wire =
        """{"token":"review-token","sha256":"digest","entry":{"name":"扩展 fixture","version":"1.0","manifestVersion":3,"permissions":["storage"],"hosts":["https://example.test/*"],"contentHosts":["https://frames.test/*"],"optionalPermissions":["tabs"],"executionState":"NOT_RUNNABLE_RUNTIME_PENDING"}}"""
    val review = gson.fromJson(wire, PackageReview::class.java)
    assertEquals(listOf("storage"), review.entry.permissions)
    assertEquals(listOf("https://example.test/*"), review.entry.hosts)
    assertEquals(JsonParser.parseString(wire), JsonParser.parseString(gson.toJson(review)))
    val page = PackagePage(1, 0, listOf(review.entry.copy(installationId = "installed-id")))
    assertEquals(page, gson.fromJson(gson.toJson(page), PackagePage::class.java))
  }

  @Test
  fun callerBoundReviewsCannotBeStolenOrReplayed() {
    broker().use { broker ->
      val review = broker.review(10101, archive().inputStream())
      assertEquals(0, broker.list(0, 8).total)
      assertThrows(SecurityException::class.java) { broker.commit(10102, review.token) }
      assertThrows(SecurityException::class.java) { broker.cancel(10102, review.token) }
      val installed = broker.commit(10101, review.token)
      assertEquals(1, broker.list(0, 8).total)
      assertEquals(listOf("storage"), broker.describe(installed.installationId!!).permissions)
      assertThrows(IllegalStateException::class.java) { broker.commit(10101, review.token) }
      assertEquals(1, broker.list(0, 8).total)
      assertEquals(
          ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING.name, installed.executionState)
    }
  }

  @Test
  fun cancelledExpiredAndDisconnectedReviewsNeverInstall() {
    var time = 0L
    val cache = File(temporary.root, "reviews")
    val broker = PackageBroker(repository(), cache, clock = { time }, reviewLifetimeMillis = 10)
    val cancelled = broker.review(10001, archive().inputStream())
    broker.cancel(10001, cancelled.token)
    assertThrows(IllegalStateException::class.java) { broker.commit(10001, cancelled.token) }
    val expired = broker.review(10001, archive().inputStream())
    time = 11
    assertThrows(IllegalStateException::class.java) { broker.commit(10001, expired.token) }
    assertTrue(cache.listFiles()!!.isEmpty())
    val disconnected = broker.review(10001, archive().inputStream())
    broker.close()
    assertTrue(cache.listFiles()!!.isEmpty())
    assertThrows(IllegalStateException::class.java) { broker.commit(10001, disconnected.token) }
    assertThrows(IllegalStateException::class.java) { broker.list(0, 8) }
    assertTrue(repository().list().isEmpty())
  }

  @Test
  fun reviewCapacityAndInputSizesAreBoundedAndStreamsClose() {
    val broker = PackageBroker(repository(), File(temporary.root, "reviews"), maxReviews = 1)
    broker.use {
      broker.review(10001, archive().inputStream())
      var closed = false
      val input =
          object : ByteArrayInputStream(archive()) {
            override fun close() {
              closed = true
              super.close()
            }
          }
      assertThrows(IllegalStateException::class.java) { broker.review(10001, input) }
      assertTrue(closed)
    }
    val cache = File(temporary.root, "small-reviews")
    PackageBroker(repository(), cache, PackageLimits(maxArchiveBytes = 10)).use { small ->
      val error =
          assertThrows(ExtensionPackageException::class.java) {
            small.review(10001, archive().inputStream())
          }
      assertEquals(PackageError.LIMIT_EXCEEDED, error.error)
      assertTrue(cache.listFiles()!!.isEmpty())
    }
  }

  @Test
  fun changingThePrivateSnapshotAfterReviewIsRejected() {
    val repo = repository()
    broker(repo).use { broker ->
      val review = broker.review(10001, archive().inputStream())
      File(temporary.root, "reviews").listFiles()!!.single().writeBytes(archive("changed"))
      val error =
          assertThrows(ExtensionPackageException::class.java) { broker.commit(10001, review.token) }
      assertEquals(PackageError.REVIEW_MISMATCH, error.error)
      assertTrue(repo.list().isEmpty())
      assertTrue(File(temporary.root, "reviews").listFiles()!!.isEmpty())
    }
  }

  @Test
  fun resourcesCannotEscapeTheSelectedInstallation() {
    broker().use { broker ->
      val a =
          broker
              .commit(10001, broker.review(10001, archive("a").inputStream()).token)
              .installationId!!
      val b =
          broker
              .commit(10001, broker.review(10001, archive("b").inputStream()).token)
              .installationId!!
      assertEquals("a", broker.withResource(a, "payload.txt") { it.readText() })
      for (path in
          listOf(
              "../metadata.json",
              "../../$b/package/payload.txt",
              "/etc/passwd",
              "..\\payload.txt")) {
        assertThrows(ExtensionPackageException::class.java) {
          broker.withResource(a, path) { fail("Must not open") }
        }
      }
      assertThrows(ExtensionPackageException::class.java) {
        broker.withResource("../$b", "payload.txt") { fail() }
      }
      assertTrue(broker.remove(a))
      assertThrows(ExtensionPackageException::class.java) {
        broker.withResource(a, "payload.txt") { fail() }
      }
      assertEquals("b", broker.withResource(b, "payload.txt") { it.readText() })
    }
  }

  @Test
  fun aSymlinkIntroducedAfterInstallationCannotReadOutsideThePackage() {
    val repo = repository()
    broker(repo).use { broker ->
      val id =
          broker.commit(10001, broker.review(10001, archive().inputStream()).token).installationId!!
      val outside = temporary.newFile("outside.txt").apply { writeText("private") }
      val directory = repo.list().single().packageDirectory
      Files.createSymbolicLink(File(directory, "link.txt").toPath(), outside.toPath())
      assertThrows(ExtensionPackageException::class.java) {
        broker.withResource(id, "link.txt") { fail() }
      }
      assertEquals("private", outside.readText())
    }
  }

  @Test
  fun paginatedListsAndReopenedStoresKeepTheSameInstallation() {
    val repo = repository()
    val id =
        broker(repo).use { broker ->
          val first =
              broker
                  .commit(10001, broker.review(10001, archive().inputStream()).token)
                  .installationId!!
          broker.commit(10001, broker.review(10001, archive().inputStream()).token)
          assertEquals(2, broker.list(0, 1).total)
          assertEquals(1, broker.list(0, 1).entries.size)
          assertNotEquals(
              broker.list(0, 1).entries.single().installationId,
              broker.list(1, 1).entries.single().installationId)
          assertThrows(IllegalArgumentException::class.java) { broker.list(-1, 8) }
          assertThrows(IllegalArgumentException::class.java) { broker.list(0, 1000) }
          first
        }
    broker(repo).use { assertEquals(id, it.describe(id).installationId) }
  }
}
