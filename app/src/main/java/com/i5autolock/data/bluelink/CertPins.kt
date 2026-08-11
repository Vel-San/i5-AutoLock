package com.i5autolock.data.bluelink

import okhttp3.CertificatePinner

/**
 * Certificate pinning for the BlueLink hosts. Pinning is a strong defence for an app that controls
 * a real car, but a WRONG pin breaks all connectivity — so pins must be the real SPKI SHA-256
 * hashes for each host. Until they're supplied here, pinning is a no-op (empty pinner).
 *
 * To enable: add `host to listOf("sha256/<base64>", ...)` entries below (backup pin recommended).
 */
object CertPins {
    private val pins: Map<String, List<String>> = emptyMap()

    val enabled: Boolean get() = pins.isNotEmpty()

    fun pinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        pins.forEach { (host, hostPins) -> hostPins.forEach { builder.add(host, it) } }
        return builder.build()
    }
}
