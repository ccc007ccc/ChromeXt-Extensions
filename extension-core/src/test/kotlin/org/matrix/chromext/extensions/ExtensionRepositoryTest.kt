package org.matrix.chromext.extensions

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()

  private val minimalManifest =
      """{"manifest_version":3,"name":"A test extension","version":"1.2.3"}"""

  @Test
  fun inspectIsReadOnlyAndImportPersistsMv3MetadataAndResources() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val manifest =
        """{
      "manifest_version":3,"name":"Example","version":"1.2.3",
      "permissions":["storage","tabs"],"host_permissions":["https://example.com/*"],
      "optional_permissions":["bookmarks"],"optional_host_permissions":["https://optional.test/*"],
      "background":{"service_worker":"worker.js","type":"module"},
      "content_scripts":[{"matches":["https://example.com/*"],"js":["content.js"],"css":["content.css"],"run_at":"document_start"}],
      "action":{"default_popup":"ui/popup.html"},"options_ui":{"page":"ui/options.html","open_in_tab":true}
    }"""
    val archive =
        zip(
            manifest,
            "worker.js" to "self.flag = true;",
            "content.js" to "window.flag = true;",
            "content.css" to "body {}",
            "ui/popup.html" to "popup",
            "ui/options.html" to "options")
    val review = repository.inspect(archive)
    assertFalse("Review must not create repository files", root.exists())
    assertEquals(3, review.manifest.manifestVersion)
    assertEquals(listOf("storage", "tabs"), review.manifest.permissions)
    assertEquals(listOf("https://example.com/*"), review.manifest.requestedHostPatterns)
    assertEquals("worker.js", review.manifest.background!!.serviceWorker)
    assertEquals("module", review.manifest.background.type)
    assertEquals("document_start", review.manifest.contentScripts.single().runAt)
    assertEquals("ui/popup.html", review.manifest.action!!.defaultPopup)
    assertEquals(ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING, review.executionState)
    val installed = repository.install(archive, review)
    assertEquals("self.flag = true;", File(installed.packageDirectory, "worker.js").readText())
    assertEquals("popup", File(installed.packageDirectory, "ui/popup.html").readText())
    assertEquals(review.sha256, installed.archiveSha256)
    assertEquals(ExtensionExecutionState.NOT_RUNNABLE_RUNTIME_PENDING, installed.executionState)
    val restarted = ExtensionRepository(root).list().single()
    assertEquals(installed, restarted)
    assertNoStaging(root)
  }

  @Test
  fun mv2CapabilitiesAndLocalizedNameArePreserved() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val manifest =
        """{
      "manifest_version":2,"name":"__MSG_extensionName__","version":"2.0","default_locale":"en_US",
      "permissions":["storage","<all_urls>"],"optional_permissions":["https://optional.test/*"],
      "background":{"scripts":["background.js"],"persistent":false},
      "browser_action":{"default_popup":"popup.html"},"options_page":"options.html"
    }"""
    val archive =
        zip(
            manifest,
            "_locales/en_US/messages.json" to
                """{"extensionName":{"message":"Localized extension"}}""",
            "background.js" to "background",
            "popup.html" to "popup",
            "options.html" to "options")
    val review = repository.inspect(archive)
    assertEquals("Localized extension", review.manifest.name)
    assertEquals(listOf("<all_urls>"), review.manifest.requestedHostPatterns)
    assertEquals(listOf("https://optional.test/*"), review.manifest.optionalHostPatterns)
    assertEquals(false, review.manifest.background!!.persistent)
    assertEquals(listOf("background.js"), review.manifest.background.scripts)
    assertEquals("browser_action", review.manifest.action!!.kind)
    val installed = repository.install(archive, review)
    assertEquals(installed, repository.list().single())
  }

  @Test
  fun sameManifestIdAndSameArchiveAlwaysCreateIndependentInstallations() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val manifest =
        """{"manifest_version":3,"name":"Same name","version":"1","key":"same-key","browser_specific_settings":{"gecko":{"id":"same@example.com"}}}"""
    val archive = zip(manifest, "asset.txt" to "original")
    val review = repository.inspect(archive)
    val first = repository.install(archive, review)
    val second = repository.install(archive, review)
    assertNotEquals(first.installationId, second.installationId)
    assertEquals(2, ExtensionRepository(root).list().size)
    assertTrue(repository.uninstall(first.installationId))
    assertFalse(first.packageDirectory.exists())
    assertEquals("original", File(second.packageDirectory, "asset.txt").readText())
    assertEquals(listOf(second), repository.list())
    assertFalse(repository.uninstall(first.installationId))
  }

  @Test
  fun unsafeZipPathsAreRejected() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    for (path in
        listOf(
            "../escape.js",
            "nested/../../escape.js",
            "/absolute.js",
            "C:/windows.js",
            "nested\\escape.js",
            "./manifest.json",
            "nested/./file.js")) {
      assertError(PackageError.UNSAFE_PATH) {
        repository.inspect(zip(minimalManifest, path to "bad"))
      }
    }
    assertFalse(File(temporary.root, "escape.js").exists())
  }

  @Test
  fun duplicateNormalizedPathsAndFileDirectoryConflictsAreRejected() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    assertError(PackageError.DUPLICATE_ENTRY) {
      repository.inspect(
          zip(minimalManifest, "nested//script.js" to "first", "nested/script.js" to "second"))
    }
    assertError(PackageError.INVALID_ARCHIVE) {
      repository.inspect(zip(minimalManifest, "parent" to "file", "parent/child.js" to "child"))
    }
    assertError(PackageError.DUPLICATE_ENTRY) {
      repository.inspect(zip(minimalManifest, "parent" to "file", "parent/" to ""))
    }
  }

  @Test
  fun archiveEntrySingleFileAndExpandedLimitsAreEnforced() {
    val archive = zip(minimalManifest, "a.txt" to "a".repeat(400), "b.txt" to "b".repeat(400))
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(
              File(temporary.root, "archive-limit"),
              PackageLimits(maxArchiveBytes = archive.length() - 1))
          .inspect(archive)
    }
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(File(temporary.root, "entry-limit"), PackageLimits(maxEntries = 2))
          .inspect(archive)
    }
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(
              File(temporary.root, "file-limit"), PackageLimits(maxSingleFileBytes = 399))
          .inspect(archive)
    }
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(
              File(temporary.root, "expanded-limit"), PackageLimits(maxExpandedBytes = 700))
          .inspect(archive)
    }
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(
              File(temporary.root, "manifest-limit"), PackageLimits(maxManifestBytes = 20))
          .inspect(archive)
    }
  }

  @Test
  fun streamedBytesAreLimitedEvenWhenCentralDirectoryLiesAboutSize() {
    val archive = zip(minimalManifest, "bomb.txt" to "a".repeat(100_000))
    val bytes = archive.readBytes()
    centralEntries(bytes).forEach { (offset, name) ->
      if (name == "bomb.txt") putInt(bytes, offset + 24, 1)
    }
    archive.writeBytes(bytes)
    assertError(PackageError.LIMIT_EXCEEDED) {
      ExtensionRepository(
              File(temporary.root, "repository"), PackageLimits(maxSingleFileBytes = 4096))
          .inspect(archive)
    }
  }

  @Test
  fun centralDirectoryCrcAndSizeAreVerifiedAgainstActualBytes() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val damagedCrc = zip(minimalManifest, "asset.txt" to "actual content")
    val crcBytes = damagedCrc.readBytes()
    centralEntries(crcBytes).forEach { (offset, name) ->
      if (name == "asset.txt") putInt(crcBytes, offset + 16, 0)
    }
    damagedCrc.writeBytes(crcBytes)
    assertError(PackageError.INVALID_ARCHIVE) { repository.inspect(damagedCrc) }
    val damagedSize = zip(minimalManifest, "asset.txt" to "actual content")
    val sizeBytes = damagedSize.readBytes()
    centralEntries(sizeBytes).forEach { (offset, name) ->
      if (name == "asset.txt") putInt(sizeBytes, offset + 24, 1)
    }
    damagedSize.writeBytes(sizeBytes)
    assertError(PackageError.INVALID_ARCHIVE) { repository.inspect(damagedSize) }
  }

  @Test
  fun truncatedAndNonZipArchivesAreRejected() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val archive = zip(minimalManifest)
    archive.writeBytes(archive.readBytes().dropLast(10).toByteArray())
    assertError(PackageError.INVALID_ARCHIVE) { repository.inspect(archive) }
    val text = temporary.newFile("not.zip").apply { writeText("This is not a ZIP") }
    assertError(PackageError.INVALID_ARCHIVE) { repository.inspect(text) }
  }

  @Test
  fun manifestMustBeValidUtf8RootJsonWithValidNameAndVersion() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val badManifests =
        listOf(
            "{}",
            "[]",
            "{",
            """{"manifest_version":1,"name":"Test","version":"1"}""",
            """{"manifest_version":3,"name":" ","version":"1"}""",
            """{"manifest_version":3,"name":"Test","version":"0.0"}""",
            """{"manifest_version":3,"name":"Test","version":"01.2"}""",
            """{"manifest_version":3,"name":"Test","version":"1.65536"}""",
            """{"manifest_version":3,"name":"Test","version":"1.2.3.4.5"}""",
            """{"manifest_version":3,"name":"Test","version":"beta"}""",
            """{"manifest_version":3,"name":"Test","name":"Other","version":"1"}""",
            """{"manifest_version":3,"name":"__MSG_unknown__","version":"1"}""",
            """{"manifest_version":3,"name":"Test","version":"1","permissions":"tabs"}""",
            """{"manifest_version":3,"name":"Test","version":"1","background":{"scripts":["manifest.json"]}}""",
        )
    badManifests.forEach { manifest ->
      assertError(PackageError.INVALID_MANIFEST) { repository.inspect(zip(manifest)) }
    }
    val nested = writeZip(listOf("nested/manifest.json" to minimalManifest.toByteArray()))
    assertError(PackageError.INVALID_MANIFEST) { repository.inspect(nested) }
    val invalidUtf8 = writeZip(listOf("manifest.json" to byteArrayOf(0xC3.toByte(), 0x28)))
    assertError(PackageError.INVALID_MANIFEST) { repository.inspect(invalidUtf8) }
  }

  @Test
  fun referencedScriptsPagesIconsLocalesAndRuleSetsMustExistInThePackage() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val declarations =
        listOf(
            """"background":{"service_worker":"missing.js"}""",
            """"content_scripts":[{"matches":["<all_urls>"],"js":["missing.js"]}]""",
            """"content_scripts":[{"matches":["<all_urls>"],"css":["missing.css"]}]""",
            """"action":{"default_popup":"missing.html"}""",
            """"options_page":"missing.html"""",
            """"options_ui":{"page":"missing.html"}""",
            """"icons":{"16":"missing.png"}""",
            """"devtools_page":"missing.html"""",
            """"default_locale":"en"""",
            """"side_panel":{"default_path":"missing.html"}""",
            """"declarative_net_request":{"rule_resources":[{"id":"rules","enabled":true,"path":"missing.json"}]}""",
            """"web_accessible_resources":[{"resources":["missing/*"],"matches":["<all_urls>"]}]""",
        )
    declarations.forEach { declaration ->
      val manifest = minimalManifest.dropLast(1) + "," + declaration + "}"
      assertError(PackageError.MISSING_RESOURCE) { repository.inspect(zip(manifest)) }
    }
    for (path in
        listOf(
            "../outside.js", "/outside.js", "https://example.com/script.js", "nested\\script.js")) {
      val jsonPath = path.replace("\\", "\\\\")
      val manifest =
          minimalManifest.dropLast(1) + ",\"background\":{\"service_worker\":\"$jsonPath\"}}"
      assertError(PackageError.UNSAFE_PATH) { repository.inspect(zip(manifest)) }
    }
  }

  @Test
  fun changedArchiveOrFailedImportCannotAlterPreviouslyInstalledPackages() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val archive = zip(minimalManifest, "asset.txt" to "stable")
    val review = repository.inspect(archive)
    val original = repository.install(archive, review)
    val different = zip(minimalManifest, "asset.txt" to "changed")
    assertError(PackageError.REVIEW_MISMATCH) { repository.install(different, review) }
    assertNoStaging(root)
    val traversal = zip(minimalManifest, "../outside.txt" to "bad")
    assertError(PackageError.UNSAFE_PATH) { repository.install(traversal, review) }
    assertNoStaging(root)
    val missingResource =
        zip(
            minimalManifest.dropLast(1) + ",\"options_page\":\"missing.html\"}",
            "written-before-error.txt" to "temporary")
    assertError(PackageError.MISSING_RESOURCE) { repository.install(missingResource, review) }
    assertNoStaging(root)
    assertEquals(listOf(original), ExtensionRepository(root).list())
    assertEquals("stable", File(original.packageDirectory, "asset.txt").readText())
    assertEquals(
        listOf(original.installationId),
        root.listFiles()!!.filter { it.isDirectory }.map { it.name })
  }

  @Test
  fun uninstallRejectsPathsAndOneDamagedInstallationDoesNotHideOthers() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val archive = zip(minimalManifest)
    val review = repository.inspect(archive)
    val good = repository.install(archive, review)
    val bad = repository.install(archive, review)
    File(root, "${bad.installationId}/metadata.json").writeText("corrupt")
    assertEquals(listOf(good), ExtensionRepository(root).list())
    for (id in listOf("..", "../outside", "/tmp", "", good.installationId + "/..")) {
      assertError(PackageError.INVALID_INSTALLATION_ID) { repository.uninstall(id) }
    }
    assertTrue(repository.uninstall(bad.installationId))
    assertEquals(listOf(good), repository.list())
  }

  @Test
  fun deepAndDuplicateKeyJsonAreRejectedWithoutParserRecursionFailure() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    val deep =
        minimalManifest.dropLast(1) +
            ",\"unknown\":" +
            "[".repeat(100) +
            "0" +
            "]".repeat(100) +
            "}"
    assertError(PackageError.INVALID_MANIFEST) { repository.inspect(zip(deep)) }
    val manifest =
        """{"manifest_version":3,"name":"__MSG_name__","version":"1","default_locale":"en"}"""
    val archive =
        zip(
            manifest,
            "_locales/en/messages.json" to """{"name":{"message":"First","message":"Second"}}""")
    assertError(PackageError.INVALID_MANIFEST) { repository.inspect(archive) }
  }

  @Test
  fun zipSymlinksSpecialFilesAndPrefixedContainersAreRejected() {
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    for (mode in listOf(0xa1ff, 0x21b6)) {
      val archive = zip(minimalManifest, "link" to "../outside")
      val bytes = archive.readBytes()
      centralEntries(bytes).forEach { (offset, name) ->
        if (name == "link") putInt(bytes, offset + 38, mode shl 16)
      }
      archive.writeBytes(bytes)
      assertError(PackageError.UNSAFE_PATH) { repository.inspect(archive) }
    }
    val prefixed = zip(minimalManifest)
    prefixed.writeBytes("Cr24".toByteArray() + prefixed.readBytes())
    assertError(PackageError.INVALID_ARCHIVE) { repository.inspect(prefixed) }
  }

  @Test
  fun actualCompressionRatioIsBounded() {
    val archive = zip(minimalManifest, "compressible.txt" to "a".repeat(100_000))
    val repository =
        ExtensionRepository(
            File(temporary.root, "repository"), PackageLimits(maxCompressionRatio = 10))
    assertError(PackageError.LIMIT_EXCEEDED) { repository.inspect(archive) }
  }

  @Test(timeout = 5000)
  fun wildcardResourcesCannotCauseRegularExpressionBacktracking() {
    val pattern = "a*".repeat(25) + "b"
    val manifest =
        minimalManifest.dropLast(1) +
            ",\"web_accessible_resources\":[{\"resources\":[\"$pattern\"],\"matches\":[\"<all_urls>\"]}]}"
    val repository = ExtensionRepository(File(temporary.root, "repository"))
    assertError(PackageError.MISSING_RESOURCE) {
      repository.inspect(zip(manifest, "a".repeat(200) to "asset"))
    }
    val valid =
        minimalManifest.dropLast(1) +
            ",\"web_accessible_resources\":[{\"resources\":[\"images/*.png\"],\"matches\":[\"<all_urls>\"]}]}"
    repository.inspect(zip(valid, "images/icon.png" to "asset"))
  }

  @Test(timeout = 5000)
  fun localizationOutputIsBoundedAndCaseInsensitiveDuplicateKeysAreRejected() {
    val repository =
        ExtensionRepository(
            File(temporary.root, "repository"), PackageLimits(maxCompressionRatio = Int.MAX_VALUE))
    val message = """{"a":{"message":"${"a".repeat(500_000)}"}}"""
    val bomb =
        minimalManifest.dropLast(1) +
            ",\"default_locale\":\"en\",\"description\":\"${"__MSG_a__".repeat(1000)}\"}"
    assertError(PackageError.INVALID_MANIFEST) {
      repository.inspect(zip(bomb, "_locales/en/messages.json" to message))
    }
    val localized =
        """{"manifest_version":3,"name":"__MSG_a__","version":"1","default_locale":"en"}"""
    assertError(PackageError.INVALID_MANIFEST) {
      repository.inspect(
          zip(
              localized,
              "_locales/en/messages.json" to
                  """{"a":{"message":"First"},"A":{"message":"Second"}}"""))
    }
  }

  @Test
  fun reviewManifestMustMatchEvenWhenChecksumIsCopiedFromAnotherReview() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val archive = zip(minimalManifest)
    val review = repository.inspect(archive)
    val mismatched = review.copy(manifest = review.manifest.copy(permissions = listOf("tabs")))
    assertError(PackageError.REVIEW_MISMATCH) { repository.install(archive, mismatched) }
    assertTrue(repository.list().isEmpty())
    assertNoStaging(root)
  }

  @Test
  fun failedExtractionAfterWritingEntriesLeavesNoPartialInstallation() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val archive = zip(minimalManifest, "asset.txt" to "actual content")
    val review = repository.inspect(archive)
    val bytes = archive.readBytes()
    centralEntries(bytes).forEach { (offset, name) ->
      if (name == "asset.txt") putInt(bytes, offset + 16, 0)
    }
    archive.writeBytes(bytes)
    assertError(PackageError.INVALID_ARCHIVE) { repository.install(archive, review) }
    assertTrue(repository.list().isEmpty())
    assertNoStaging(root)
  }

  @Test
  fun uninstallNeverFollowsStoredOrBrokenSymlinks() {
    val root = File(temporary.root, "repository")
    val repository = ExtensionRepository(root)
    val archive = zip(minimalManifest, "asset.txt" to "untouched")
    val review = repository.inspect(archive)
    val first = repository.install(archive, review)
    val second = repository.install(archive, review)
    Files.createSymbolicLink(
        File(first.packageDirectory, "other-package").toPath(), second.packageDirectory.toPath())
    Files.createSymbolicLink(
        File(first.packageDirectory, "broken-link").toPath(),
        File(temporary.root, "does-not-exist").toPath())
    assertEquals(listOf(second), repository.list())
    assertTrue(repository.uninstall(first.installationId))
    assertEquals("untouched", File(second.packageDirectory, "asset.txt").readText())
    assertEquals(listOf(second), repository.list())
    val linkId = UUID.randomUUID().toString()
    Files.createSymbolicLink(
        File(root, linkId).toPath(), File(root, second.installationId).toPath())
    assertError(PackageError.INVALID_INSTALLATION_ID) { repository.uninstall(linkId) }
    assertTrue(second.packageDirectory.exists())
  }

  @Test(timeout = 10000)
  fun abandonedStagingIsCleanedAndConcurrentRepositoryInstancesAreSerialized() {
    val root = File(temporary.root, "repository").apply { mkdirs() }
    val abandoned = File(root, ".staging-${UUID.randomUUID()}").apply { mkdirs() }
    File(abandoned, "partial.txt").writeText("interrupted import")
    val archive = zip(minimalManifest, "nested/deeper/asset.txt" to "asset")
    val repository = ExtensionRepository(root)
    assertTrue(repository.list().isEmpty())
    assertFalse(abandoned.exists())
    val review = repository.inspect(archive)
    val executor = Executors.newFixedThreadPool(4)
    try {
      val futures =
          executor.invokeAll(
              (1..4).map { Callable { ExtensionRepository(root).install(archive, review) } })
      val installations = futures.map { it.get(5, TimeUnit.SECONDS) }
      assertEquals(4, installations.map { it.installationId }.toSet().size)
      assertEquals(4, ExtensionRepository(root).list().size)
      installations.forEach {
        assertEquals("asset", File(it.packageDirectory, "nested/deeper/asset.txt").readText())
      }
      assertNoStaging(root)
    } finally {
      executor.shutdownNow()
    }
  }

  private fun zip(manifest: String, vararg entries: Pair<String, String>): File =
      writeZip(
          listOf("manifest.json" to manifest.toByteArray()) +
              entries.map { it.first to it.second.toByteArray() })

  private fun writeZip(entries: List<Pair<String, ByteArray>>): File {
    val file = temporary.newFile()
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((path, bytes) in entries) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return file
  }

  private fun assertError(expected: PackageError, block: () -> Unit) {
    try {
      block()
      fail("Expected $expected")
    } catch (exception: ExtensionPackageException) {
      assertEquals(exception.message, expected, exception.error)
    }
  }

  private fun assertNoStaging(root: File) {
    assertFalse(root.listFiles()!!.any { it.name.startsWith(".staging-") })
  }

  private fun centralEntries(bytes: ByteArray): List<Pair<Int, String>> {
    val entries = mutableListOf<Pair<Int, String>>()
    var offset = 0
    while (offset < bytes.size - 46) {
      if (bytes[offset] == 0x50.toByte() &&
          bytes[offset + 1] == 0x4b.toByte() &&
          bytes[offset + 2] == 1.toByte() &&
          bytes[offset + 3] == 2.toByte()) {
        val length =
            (bytes[offset + 28].toInt() and 255) or ((bytes[offset + 29].toInt() and 255) shl 8)
        val name = String(bytes, offset + 46, length, Charsets.UTF_8)
        entries.add(offset to name)
        offset += 46 + length
      } else offset++
    }
    return entries
  }

  private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0..3) bytes[offset + index] = (value ushr (8 * index)).toByte()
  }
}
