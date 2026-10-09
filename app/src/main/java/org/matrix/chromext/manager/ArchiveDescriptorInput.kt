package org.matrix.chromext.manager

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** In particular, a document provider's pipe must not keep a Binder thread waiting indefinitely. */
internal class ArchiveDescriptorInput(descriptor: ParcelFileDescriptor) : InputStream() {
  private val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
  private val deadline = SystemClock.elapsedRealtime() + 30_000
  private val poll =
      StructPollfd().apply {
        fd = descriptor.fileDescriptor
        events = OsConstants.POLLIN.toShort()
      }

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    while (true) {
      if (Thread.currentThread().isInterrupted)
          throw InterruptedIOException("Package import cancelled")
      val left = deadline - SystemClock.elapsedRealtime()
      if (left <= 0) throw SocketTimeoutException("The selected ZIP provider stopped responding")
      try {
        if (Os.poll(arrayOf(poll), left.coerceAtMost(1000).toInt()) > 0)
            return source.read(buffer, offset, length)
      } catch (error: ErrnoException) {
        if (error.errno != OsConstants.EINTR)
            throw java.io.IOException("Cannot read selected ZIP", error)
      }
    }
  }

  override fun close() {
    source.close()
  }
}
