package com.bp22intel.edgesentinel

import com.bp22intel.edgesentinel.domain.model.CellTower
import com.bp22intel.edgesentinel.domain.model.NetworkType

/** Builds a CellTower with sane US-carrier defaults for detector tests. */
fun cell(
    cid: Int,
    type: NetworkType = NetworkType.LTE,
    signal: Int = -95,
    lacTac: Int = 1,
    registered: Boolean = false,
    lastSeen: Long = System.currentTimeMillis(),
    mcc: Int = 310,
    mnc: Int = 260
) = CellTower(
    id = cid.toLong(), cid = cid, lacTac = lacTac, mcc = mcc, mnc = mnc,
    signalStrength = signal, networkType = type, latitude = null, longitude = null,
    firstSeen = lastSeen, lastSeen = lastSeen, timesSeen = 1, isRegistered = registered
)

/** Allocates an instance without running its constructor (no Android Context needed). */
@Suppress("UNCHECKED_CAST")
fun <T> allocateWithoutConstructor(clazz: Class<T>): T {
    val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    f.isAccessible = true
    val unsafe = f.get(null)
    val m = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return m.invoke(unsafe, clazz) as T
}
