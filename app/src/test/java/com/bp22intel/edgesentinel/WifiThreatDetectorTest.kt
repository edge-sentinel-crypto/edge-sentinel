package com.bp22intel.edgesentinel

import com.bp22intel.edgesentinel.data.local.dao.TrustedNetworkDao
import com.bp22intel.edgesentinel.detection.wifi.ObservedAp
import com.bp22intel.edgesentinel.detection.wifi.SecurityType
import com.bp22intel.edgesentinel.detection.wifi.WifiScanSnapshot
import com.bp22intel.edgesentinel.detection.wifi.WifiThreatDetector
import com.bp22intel.edgesentinel.domain.model.WifiThreatType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class WifiThreatDetectorTest {
    private val dao = Proxy.newProxyInstance(
        TrustedNetworkDao::class.java.classLoader, arrayOf(TrustedNetworkDao::class.java)
    ) { _, _, _ -> throw UnsupportedOperationException("not used") } as TrustedNetworkDao
    private val detector = WifiThreatDetector(dao, InMemorySightingDao())

    private fun ap(bssid: String, ssid: String, sec: SecurityType, sig: Int = -70, freq: Int = 2437) =
        ObservedAp(bssid = bssid, ssid = ssid, signalStrength = sig, frequency = freq,
            securityType = sec, capabilities = "")

    private fun snap(vararg aps: ObservedAp) = WifiScanSnapshot(aps.toList())

    private val corpA = ap("aa:aa:aa:aa:aa:01", "CorpNet", SecurityType.WPA2)
    private val corpB = ap("aa:aa:aa:aa:aa:02", "CorpNet", SecurityType.WPA2)

    @Test fun evilTwinNewOpenBssidFires() {
        val history = listOf(snap(corpA, corpB), snap(corpA, corpB))
        val twin = ap("de:ad:be:ef:00:01", "CorpNet", SecurityType.OPEN)
        val results = detector.analyze(snap(corpA, twin), history)
        val et = results.filter { it.threatType == WifiThreatType.EVIL_TWIN }
        assertTrue("open-vs-secured twin must fire", et.isNotEmpty())
        assertTrue(et.first().details.containsKey("security_mismatch"))
    }

    // Round 4 (D2): a BSSID is known after >= 3 scans across >= 2 sessions.
    @Test fun stableMultiApSsidDoesNotFire() {
        val now = System.currentTimeMillis()
        val h = 60L * 60 * 1000
        val history = listOf(
            WifiScanSnapshot(listOf(corpA, corpB), now - 48 * h),
            WifiScanSnapshot(listOf(corpA, corpB), now - 30 * h),
            WifiScanSnapshot(listOf(corpA, corpB), now - 1 * h)
        )
        val results = detector.analyze(WifiScanSnapshot(listOf(corpA, corpB), now), history)
        assertFalse(results.any { it.threatType == WifiThreatType.EVIL_TWIN })
    }

    @Test fun deauthThreeDisconnectsInSixtySecondsFires() {
        val now = System.currentTimeMillis()
        val results = detector.analyze(snap(), emptyList(),
            disconnectTimestamps = listOf(now - 50_000, now - 20_000, now - 1_000))
        assertTrue(results.any { it.threatType == WifiThreatType.DEAUTH_ATTACK })
    }

    @Test fun deauthTwoDisconnectsDoesNotFire() {
        val now = System.currentTimeMillis()
        val results = detector.analyze(snap(), emptyList(),
            disconnectTimestamps = listOf(now - 20_000, now - 1_000, now - 120_000))
        assertFalse(results.any { it.threatType == WifiThreatType.DEAUTH_ATTACK })
    }

    // ── F9 / N5: per-(SSID, BSSID) history ──────────────────────────────────

    private val h = 60L * 60 * 1000
    private fun established(now: Long, vararg aps: ObservedAp) = listOf(
        WifiScanSnapshot(aps.toList(), now - 48 * h),
        WifiScanSnapshot(aps.toList(), now - 30 * h),
        WifiScanSnapshot(aps.toList(), now - 1 * h))

    @Test fun newBssidSameSecurityOnKnownSsidFires() {
        val now = System.currentTimeMillis()
        val rogue = ap("de:ad:be:ef:00:02", "CorpNet", SecurityType.WPA2)
        val results = detector.analyze(
            WifiScanSnapshot(listOf(corpA, corpB, rogue), now), established(now, corpA, corpB))
        val et = results.filter { it.threatType == WifiThreatType.EVIL_TWIN }
        assertTrue("new same-security BSSID on a known SSID must fire", et.isNotEmpty())
        assertFalse("no open/secured mismatch here", et.first().details.containsKey("security_mismatch"))
    }

    @Test fun bssidSeenInThreeScansOver24hDoesNotFire() {
        val now = System.currentTimeMillis()
        val corpC = ap("aa:aa:aa:aa:aa:03", "CorpNet", SecurityType.WPA2)
        val results = detector.analyze(
            WifiScanSnapshot(listOf(corpA, corpB, corpC), now), established(now, corpA, corpB, corpC))
        assertFalse(results.any { it.threatType == WifiThreatType.EVIL_TWIN })
    }

    // Round 4 (D2): 3 scans within ONE session (15 s apart) are not established.
    @Test fun bssidSeenInThreeScansInOneSessionStillFires() {
        val now = System.currentTimeMillis()
        val corpC = ap("aa:aa:aa:aa:aa:03", "CorpNet", SecurityType.WPA2)
        val history = established(now, corpA, corpB) + listOf(
            WifiScanSnapshot(listOf(corpA, corpB, corpC), now - 45_000),
            WifiScanSnapshot(listOf(corpA, corpB, corpC), now - 30_000),
            WifiScanSnapshot(listOf(corpA, corpB, corpC), now - 15_000))
        val results = detector.analyze(WifiScanSnapshot(listOf(corpA, corpB, corpC), now), history)
        assertTrue("3 scans in a single session is not established",
            results.any { it.threatType == WifiThreatType.EVIL_TWIN })
    }

    @Test fun securityChangeOnEstablishedBssidFires() {
        val now = System.currentTimeMillis()
        val corpBwpa3 = ap("aa:aa:aa:aa:aa:02", "CorpNet", SecurityType.WPA3)
        val results = detector.analyze(
            WifiScanSnapshot(listOf(corpA, corpBwpa3), now), established(now, corpA, corpB))
        assertTrue("security change resets establishment",
            results.any { it.threatType == WifiThreatType.EVIL_TWIN })
    }
}
