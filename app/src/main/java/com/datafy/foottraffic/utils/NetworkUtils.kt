package com.datafy.foottraffic.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

object NetworkUtils {

    /**
     * Gets the device's IP address on the local network
     * Returns the WiFi IP if connected, otherwise tries to find any IPv4 address
     */
    fun getDeviceIpAddress(context: Context): String {
        try {
            // First try to get WiFi IP address
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress

            // Check if WiFi is connected and has valid IP
            if (ipInt != 0) {
                val ipAddress = Formatter.formatIpAddress(ipInt)
                if (ipAddress != "0.0.0.0") {
                    Timber.d("WiFi IP address: $ipAddress")
                    return ipAddress
                }
            }

            // If WiFi IP not available, iterate through all network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val networkInterfaces = Collections.list(interfaces)

            for (networkInterface in networkInterfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)

                for (address in addresses) {
                    // Look for non-loopback IPv4 addresses
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress != "127.0.0.1") {
                            Timber.d("Found IP address: $hostAddress on interface: ${networkInterface.name}")

                            // Prefer addresses that look like local network IPs
                            if (hostAddress.startsWith("192.168.") ||
                                hostAddress.startsWith("10.") ||
                                hostAddress.startsWith("172.")) {
                                return hostAddress
                            }
                        }
                    }
                }
            }

            // If still no address found, try alternative method
            val alternativeIp = getAlternativeIpAddress()
            if (alternativeIp != "127.0.0.1") {
                return alternativeIp
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to get IP address")
        }

        // Return localhost as fallback
        return "127.0.0.1"
    }

    /**
     * Alternative method to get IP address using WiFi manager
     */
    private fun getAlternativeIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback and non-active interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // We want IPv4 addresses only
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null) {
                            Timber.d("Alternative IP found: $hostAddress")
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting alternative IP")
        }

        return "127.0.0.1"
    }

    /**
     * Checks if the device is connected to WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled && wifiManager.connectionInfo.networkId != -1
    }

    /**
     * Gets the WiFi SSID if connected
     */
    fun getWifiSSID(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        return if (wifiInfo.networkId != -1) {
            wifiInfo.ssid?.replace("\"", "") // Remove quotes from SSID
        } else {
            null
        }
    }
}