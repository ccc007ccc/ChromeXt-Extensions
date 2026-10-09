package org.matrix.chromext.extensions

import java.io.File

internal fun packagePath(raw: String, directory: Boolean = false): String {
  if (raw.length > 4096) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Package path is too long")
  }
  if (raw.isEmpty() ||
      raw.startsWith('/') ||
      raw.contains('\\') ||
      raw.contains(':') ||
      raw.any { it.code < 32 || it.code == 127 }) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Unsafe package path: $raw")
  }
  val pieces = raw.split('/')
  if (pieces.any { it == "." || it == ".." }) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Unsafe package path: $raw")
  }
  if (!directory && raw.endsWith('/')) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Expected a file path: $raw")
  }
  val normalized = pieces.filter { it.isNotEmpty() }.joinToString("/")
  if (normalized.isEmpty()) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Empty package path")
  }
  if (normalized.toByteArray(Charsets.UTF_8).size > 4096 ||
      pieces.count { it.isNotEmpty() } > 64 ||
      pieces.any { it.toByteArray(Charsets.UTF_8).size > 255 }) {
    throw ExtensionPackageException(
        PackageError.UNSAFE_PATH, "Package path is too long or deeply nested")
  }
  return normalized
}

internal fun containedFile(root: File, relative: String): File {
  val file = File(root, relative)
  val prefix = root.canonicalPath + File.separator
  if (!file.canonicalPath.startsWith(prefix)) {
    throw ExtensionPackageException(PackageError.UNSAFE_PATH, "Path leaves its package directory")
  }
  return file
}

/** Do not follow links, including links introduced outside the package importer. */
internal fun deleteTree(file: File) {
  if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
    file.listFiles()?.forEach(::deleteTree)
  }
  // File.exists() follows links, so attempt deletion even for a broken symbolic link.
  if (!file.delete() && file.exists()) {
    throw java.io.IOException("Could not remove ${file.name}")
  }
}
