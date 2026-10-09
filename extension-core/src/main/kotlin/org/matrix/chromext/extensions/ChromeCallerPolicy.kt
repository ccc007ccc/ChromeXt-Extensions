package org.matrix.chromext.extensions

/** All inputs come from Binder/PackageManager, never from an IPC request's claimed identity. */
object ChromeCallerPolicy {
  const val PACKAGE = "com.android.chrome"
  // Google Chrome's Android signing certificate, verified against the Chrome 154 baseline APK.
  const val SIGNER_SHA256 = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"

  fun isTrusted(
      callerUid: Int,
      moduleUid: Int,
      chromeUid: Int?,
      chromeSigningHistory: Set<String>
  ): Boolean {
    if (callerUid < 10_000 || moduleUid < 10_000) return false
    if (callerUid == moduleUid) return true
    if (callerUid / 100_000 != moduleUid / 100_000) return false
    return callerUid == chromeUid && SIGNER_SHA256 in chromeSigningHistory
  }
}
