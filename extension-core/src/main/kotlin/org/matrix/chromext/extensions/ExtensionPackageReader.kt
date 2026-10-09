package org.matrix.chromext.extensions

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal class ExtensionPackageReader(private val limits: PackageLimits) {
  fun read(archive: File, destination: File? = null): ExtensionInspection {
    try {
      if (!archive.isFile) archiveError("The selected package is not a file")
      val (sha256, archiveSize) = hashArchive(archive)
      validateZipStructure(archive, limits)
      ZipFile(archive).use { zip ->
        val entries = linkedMapOf<String, ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
          if (entries.size >= limits.maxEntries) limit("Too many ZIP entries")
          val entry = enumeration.nextElement()
          val path = packagePath(entry.name, directory = entry.isDirectory)
          if (entries.put(path, entry) != null) {
            throw ExtensionPackageException(
                PackageError.DUPLICATE_ENTRY, "Duplicate ZIP path: $path")
          }
          if (entry.size > limits.maxSingleFileBytes) limit("ZIP entry is too large: $path")
          if (entry.compressedSize > limits.maxArchiveBytes)
              limit("Compressed ZIP entry is too large")
        }
        entries.forEach { (path, _) ->
          var slash = path.indexOf('/')
          while (slash >= 0) {
            val parent = entries[path.substring(0, slash)]
            if (parent != null && !parent.isDirectory)
                archiveError("A ZIP file is also a directory: ${parent.name}")
            slash = path.indexOf('/', slash + 1)
          }
        }
        val manifestEntry = entries["manifest.json"]
        if (manifestEntry == null || manifestEntry.isDirectory) {
          throw ExtensionPackageException(
              PackageError.INVALID_MANIFEST, "A root manifest.json is required")
        }
        val filePaths = entries.filterValues { !it.isDirectory }.keys
        var expandedBytes = 0L
        var manifestText: String? = null
        entries.forEach { (path, entry) ->
          val file = destination?.let { containedFile(it, path) }
          if (file != null) {
            val directory = if (entry.isDirectory) file else file.parentFile!!
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("Cannot create package directory")
          }
          var fileBytes = 0L
          val crc = CRC32()
          val manifestBuffer = if (path == "manifest.json") ByteArrayOutputStream() else null
          val output = if (file != null && !entry.isDirectory) file.outputStream() else null
          try {
            zip.getInputStream(entry).use { input ->
              val buffer = ByteArray(8192)
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                fileBytes += count
                expandedBytes += count
                if (fileBytes > limits.maxSingleFileBytes)
                    limit("ZIP entry expands beyond the per-file limit: $path")
                if (expandedBytes > limits.maxExpandedBytes)
                    limit("ZIP expands beyond the package limit")
                if (fileBytes.toDouble() / maxOf(entry.compressedSize, 1L) >
                    limits.maxCompressionRatio) {
                  limit("ZIP entry exceeds the compression ratio limit: $path")
                }
                if (manifestBuffer != null && fileBytes > limits.maxManifestBytes)
                    limit("manifest.json is too large")
                crc.update(buffer, 0, count)
                manifestBuffer?.write(buffer, 0, count)
                output?.write(buffer, 0, count)
              }
            }
          } finally {
            output?.close()
          }
          if (entry.isDirectory && fileBytes != 0L)
              archiveError("ZIP directory contains data: $path")
          // ZipFile does not verify CRC itself. Check actual bytes instead of trusting header
          // sizes.
          if (entry.size != fileBytes || entry.crc != crc.value)
              archiveError("Damaged or truncated ZIP entry: $path")
          if (manifestBuffer != null) manifestText = decodeUtf8(manifestBuffer.toByteArray(), path)
        }
        val manifest =
            ManifestParser(filePaths) { path ->
                  val entry =
                      entries[path]
                          ?: throw ExtensionPackageException(
                              PackageError.MISSING_RESOURCE, "Missing resource: $path")
                  zip.getInputStream(entry).use {
                    readBoundedText(it, limits.maxManifestBytes, path)
                  }
                }
                .parse(manifestText!!)
        if (hashArchive(archive) != (sha256 to archiveSize)) {
          throw ExtensionPackageException(
              PackageError.REVIEW_MISMATCH, "The ZIP changed while it was being inspected")
        }
        return ExtensionInspection(manifest, sha256, archiveSize, expandedBytes, entries.size)
      }
    } catch (exception: ExtensionPackageException) {
      throw exception
    } catch (exception: ZipException) {
      throw ExtensionPackageException(
          PackageError.INVALID_ARCHIVE,
          "The file is not a complete, supported ZIP package",
          exception)
    } catch (exception: IOException) {
      throw ExtensionPackageException(
          PackageError.IO_ERROR, "Could not read the extension package", exception)
    } catch (exception: IllegalArgumentException) {
      throw ExtensionPackageException(
          PackageError.INVALID_ARCHIVE, "Invalid ZIP package", exception)
    }
  }

  private fun hashArchive(file: File): Pair<String, Long> {
    if (file.length() > limits.maxArchiveBytes) limit("ZIP package exceeds the archive size limit")
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > limits.maxArchiveBytes) limit("ZIP package exceeds the archive size limit")
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to total
  }

  private fun archiveError(message: String): Nothing =
      throw ExtensionPackageException(PackageError.INVALID_ARCHIVE, message)

  private fun limit(message: String): Nothing =
      throw ExtensionPackageException(PackageError.LIMIT_EXCEEDED, message)
}

internal fun readBoundedText(input: InputStream, maxBytes: Long, label: String): String {
  val output = ByteArrayOutputStream()
  var total = 0L
  val buffer = ByteArray(8192)
  while (true) {
    val count = input.read(buffer)
    if (count < 0) break
    if (count == 0) continue
    total += count
    if (total > maxBytes)
        throw ExtensionPackageException(PackageError.LIMIT_EXCEEDED, "$label is too large")
    output.write(buffer, 0, count)
  }
  return decodeUtf8(output.toByteArray(), label)
}

private fun decodeUtf8(bytes: ByteArray, label: String): String {
  try {
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
  } catch (exception: java.nio.charset.CharacterCodingException) {
    throw ExtensionPackageException(
        PackageError.INVALID_MANIFEST, "$label must be UTF-8", exception)
  }
}
