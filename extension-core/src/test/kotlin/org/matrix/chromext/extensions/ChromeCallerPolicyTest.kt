package org.matrix.chromext.extensions

import org.junit.Assert.*
import org.junit.Test

class ChromeCallerPolicyTest {
  @Test
  fun onlyTheActualChromeUidAndPinnedCertificateAreAccepted() {
    val signed = setOf(ChromeCallerPolicy.SIGNER_SHA256)
    assertTrue(ChromeCallerPolicy.isTrusted(10359, 10400, 10359, signed))
    assertFalse(ChromeCallerPolicy.isTrusted(10358, 10400, 10359, signed))
    assertFalse(ChromeCallerPolicy.isTrusted(10359, 10400, 10359, setOf("fake signer")))
    assertFalse(ChromeCallerPolicy.isTrusted(10359, 10400, null, signed))
  }

  @Test
  fun otherUsersIsolatedRenderersAndSystemUidsAreRejected() {
    val signed = setOf(ChromeCallerPolicy.SIGNER_SHA256)
    for (uid in listOf(-1, 0, 1000, 2000, 99001, 110359)) {
      assertFalse("UID $uid", ChromeCallerPolicy.isTrusted(uid, 10400, 10359, signed))
    }
    assertFalse(ChromeCallerPolicy.isTrusted(110359, 10400, 110359, signed))
  }

  @Test
  fun moduleLocalCallsDoNotRequireAnInstalledChrome() {
    assertTrue(ChromeCallerPolicy.isTrusted(10400, 10400, null, emptySet()))
    assertFalse(ChromeCallerPolicy.isTrusted(-1, -1, null, emptySet()))
  }
}
