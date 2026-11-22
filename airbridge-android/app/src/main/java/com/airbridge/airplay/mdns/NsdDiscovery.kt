package com.airbridge.airplay.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.airbridge.airplay.util.AirLog
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * Discovery through Android's NsdManager for `_raop._tcp.` and `_airplay._tcp.`, plus the raw
 * multicast fallback ([RawMdnsBrowser]) because NsdManager loses TXT records on some OEM builds.
 * Holds a Wi-Fi MulticastLock while running. Resolves are serialized: before Android 14 NsdManager
 * rejects concurrent resolves with FAILURE_ALREADY_ACTIVE.
 */
class DiscoveryManager(
    context: Context,
    private val registry: ReceiverRegistry,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val listeners = HashMap<ServiceType, NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<Pair<ServiceType, NsdServiceInfo>>()
    private var resolving = false
    private var rawBrowser: RawMdnsBrowser? = null
    private var expiryThread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        multicastLock = wifi.createMulticastLock("AirBridge-mDNS").apply {
            setReferenceCounted(false)
            acquire()
        }
        for (type in ServiceType.values()) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = AirLog.d(TAG, "NSD browsing $serviceType")
                override fun onDiscoveryStopped(serviceType: String) = AirLog.d(TAG, "NSD stopped $serviceType")
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                    AirLog.w(TAG, "NSD discovery failed for $serviceType ($errorCode); raw mDNS still runs")
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) = enqueueResolve(type, info)
                override fun onServiceLost(info: NsdServiceInfo) = registry.onServiceLost(type, info.serviceName)
            }
            listeners[type] = listener
            try {
                nsd.discoverServices("${type.dnsType}.", NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                AirLog.w(TAG, "NSD discoverServices threw for ${type.dnsType}", e)
            }
        }
        try {
            rawBrowser = RawMdnsBrowser({ registry.onServiceResolved(it) }).also { it.start() }
        } catch (e: Exception) {
            AirLog.w(TAG, "raw mDNS browser unavailable", e)
        }
        expiryThread = Thread({
            while (running) {
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    break
                }
                registry.expire(maxAgeMs = 5 * 60_000L)
            }
        }, "airbridge-mdns-expiry").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        for (l in listeners.values) {
            try {
                nsd.stopServiceDiscovery(l)
            } catch (_: Exception) {
            }
        }
        listeners.clear()
        rawBrowser?.close()
        rawBrowser = null
        expiryThread?.interrupt()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }

    /** Clears results and browses again (pull-to-refresh). */
    fun restart() {
        stop()
        registry.clearDiscovered()
        start()
    }

    private fun enqueueResolve(type: ServiceType, info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(type to info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            val n = resolveQueue.pollFirst()
            if (n == null) resolving = false
            n
        } ?: return
        val (type, info) = next
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && running) {
                    synchronized(resolveQueue) { resolveQueue.addLast(type to serviceInfo) }
                }
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                try {
                    val host = hostOf(serviceInfo)
                    if (host != null) {
                        registry.onServiceResolved(
                            DiscoveredService(
                                type = type,
                                instanceName = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port,
                                txt = TxtRecord.fromAttributes(serviceInfo.attributes),
                                source = "nsd",
                            ),
                        )
                    }
                } catch (e: Exception) {
                    AirLog.w(TAG, "could not use NSD result for ${serviceInfo.serviceName}", e)
                }
                resolveNext()
            }
        })
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        val v4 = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return v4?.hostAddress?.substringBefore('%')
    }

    companion object {
        private const val TAG = "Discovery"
    }
}
