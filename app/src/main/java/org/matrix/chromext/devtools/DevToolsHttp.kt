package org.matrix.chromext.devtools

import android.net.LocalSocket
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException

/** CDP discovery uses byte lengths, which must not be confused with UTF-16 character counts. */
fun devToolsGet(path: String): String {
  require(path in setOf("/json", "/json/version"))
  return LocalSocket().use { socket ->
    connectDevTools(socket)
    socket.soTimeout = 3000
    socket.outputStream.write(
        "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII))
    val input = BufferedInputStream(socket.inputStream)
    val header = java.io.ByteArrayOutputStream()
    while (true) {
      val byte = input.read()
      if (byte < 0) throw EOFException("CDP closed during HTTP headers")
      header.write(byte)
      if (header.size() > 16 * 1024) throw IOException("CDP HTTP headers exceed limit")
      if (byte == 10 && header.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
    }
    val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
    check(lines.first().split(' ').getOrNull(1) == "200") { "CDP HTTP discovery failed" }
    val lengths = lines.filter { it.startsWith("content-length:", true) }
    val length =
        lengths.singleOrNull()?.substringAfter(':')?.trim()?.toIntOrNull()
            ?: throw IOException("CDP response has no unambiguous length")
    if (length !in 0..(4 * 1024 * 1024)) throw IOException("CDP discovery response exceeds limit")
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
      val count = input.read(body, read, length - read)
      if (count < 0) throw EOFException("CDP closed during HTTP body")
      read += count
    }
    body.toString(Charsets.UTF_8)
  }
}
