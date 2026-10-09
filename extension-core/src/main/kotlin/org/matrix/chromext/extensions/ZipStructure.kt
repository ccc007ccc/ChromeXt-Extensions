package org.matrix.chromext.extensions

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Java/Android ZipEntry does not expose Unix mode bits, so inspect the bounded central directory.
 */
internal fun validateZipStructure(archive: File, limits: PackageLimits) {
  fun invalid(message: String): Nothing =
      throw ExtensionPackageException(PackageError.INVALID_ARCHIVE, message)
  fun ushort(file: RandomAccessFile): Int =
      file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
  fun uint(file: RandomAccessFile): Long = ushort(file).toLong() or (ushort(file).toLong() shl 16)
  try {
    RandomAccessFile(archive, "r").use { file ->
      val length = file.length()
      if (length < 22 || uint(file) != 0x04034b50L)
          invalid("A standard ZIP package is required; CRX and prefixed archives are unsupported")
      val minimum = maxOf(0L, length - 65557)
      var end = length - 22
      var found = false
      while (end >= minimum) {
        file.seek(end)
        if (uint(file) == 0x06054b50L) {
          file.seek(end + 20)
          if (end + 22 + ushort(file) == length) {
            found = true
            break
          }
        }
        end--
      }
      if (!found) invalid("ZIP end record is missing or truncated")
      file.seek(end + 4)
      val disk = ushort(file)
      val centralDisk = ushort(file)
      val diskEntries = ushort(file)
      val entries = ushort(file)
      val centralSize = uint(file)
      val centralOffset = uint(file)
      if (disk != 0 || centralDisk != 0 || diskEntries != entries)
          invalid("Multi-volume ZIP packages are unsupported")
      if (entries == 65535 || centralSize == 0xffffffffL || centralOffset == 0xffffffffL)
          invalid("ZIP64 packages are unsupported")
      if (entries > limits.maxEntries)
          throw ExtensionPackageException(PackageError.LIMIT_EXCEEDED, "Too many ZIP entries")
      if (centralOffset + centralSize != end) invalid("Invalid ZIP central directory bounds")
      file.seek(centralOffset)
      val localRanges = mutableListOf<Pair<Long, Long>>()
      repeat(entries) {
        val start = file.filePointer
        if (start + 46 > end || uint(file) != 0x02014b50L)
            invalid("Invalid ZIP central directory entry")
        file.seek(start + 8)
        val flags = ushort(file)
        val method = ushort(file)
        if (flags and 1 != 0 || flags and 64 != 0) invalid("Encrypted ZIP packages are unsupported")
        if (method != 0 && method != 8) invalid("Unsupported ZIP compression method")
        file.seek(start + 20)
        val compressed = uint(file)
        val expanded = uint(file)
        val nameLength = ushort(file)
        val extraLength = ushort(file)
        val commentLength = ushort(file)
        val entryDisk = ushort(file)
        ushort(file) // Internal attributes.
        val attributes = uint(file)
        val localOffset = uint(file)
        if (entryDisk != 0 ||
            compressed == 0xffffffffL ||
            expanded == 0xffffffffL ||
            localOffset == 0xffffffffL) {
          invalid("ZIP64 and multi-volume entries are unsupported")
        }
        val type = ((attributes ushr 16).toInt() and 0xf000)
        if (type != 0 && type != 0x8000 && type != 0x4000) {
          throw ExtensionPackageException(
              PackageError.UNSAFE_PATH, "ZIP symbolic links and special files are forbidden")
        }
        val next = start + 46L + nameLength + extraLength + commentLength
        if (nameLength == 0 || next > end || localOffset + 30 > centralOffset)
            invalid("Invalid ZIP entry bounds")
        val centralName = ByteArray(nameLength)
        file.readFully(centralName)
        val directoryName = centralName.last() == '/'.code.toByte()
        if ((type == 0x4000 && !directoryName) || (type == 0x8000 && directoryName)) {
          invalid("ZIP entry type does not match its filename")
        }
        file.seek(localOffset)
        if (uint(file) != 0x04034b50L) invalid("Invalid ZIP local entry header")
        file.seek(localOffset + 6)
        if (ushort(file) != flags || ushort(file) != method)
            invalid("ZIP local and central headers disagree")
        file.seek(localOffset + 26)
        val localNameLength = ushort(file)
        val localExtraLength = ushort(file)
        val dataEnd = localOffset + 30L + localNameLength + localExtraLength + compressed
        if (dataEnd > centralOffset) {
          invalid("ZIP entry data overlaps its central directory")
        }
        if (localNameLength != nameLength) invalid("ZIP local and central filenames disagree")
        val localName = ByteArray(localNameLength)
        file.readFully(localName)
        if (!localName.contentEquals(centralName))
            invalid("ZIP local and central filenames disagree")
        localRanges.add(localOffset to dataEnd)
        file.seek(next)
      }
      if (file.filePointer != end) invalid("ZIP entry count does not match its central directory")
      val sorted = localRanges.sortedBy { it.first }
      for (index in 1 until sorted.size) {
        if (sorted[index].first < sorted[index - 1].second) invalid("ZIP entries overlap")
      }
    }
  } catch (exception: EOFException) {
    throw ExtensionPackageException(
        PackageError.INVALID_ARCHIVE, "The ZIP package is truncated", exception)
  }
}
