package vn.livesub.translate

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

data class DiscoveredServer(val url: String, val name: String, val model: String?)

/**
 * Tự tìm server dịch trong mạng nội bộ qua mDNS/Bonjour (`_livesub._tcp`), do
 * `server/livesub_server.py` quảng bá bằng `dns-sd` của macOS.
 */
class ServerDiscovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Server tìm thấy gần nhất; null khi chưa thấy hoặc server vừa tắt. Dừng tìm khi huỷ collect. */
    fun servers(): Flow<DiscoveredServer?> = callbackFlow {
        trySend(null)
        var current: DiscoveredServer? = null
        // Android < 14 chỉ resolve được một service mỗi lúc; chỉ cần server đầu tiên.
        var resolving = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (current != null || resolving) return
                resolving = true
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        resolving = false
                        val host = ipv4(resolved) ?: return
                        val model = resolved.attributes["model"]?.toString(Charsets.UTF_8)
                        current = DiscoveredServer("http://$host:${resolved.port}", resolved.serviceName, model)
                        Log.i(TAG, "found $current")
                        trySend(current)
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w(TAG, "resolve failed $errorCode")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceName == current?.name) {
                    current = null
                    trySend(null)
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }.distinctUntilChanged()

    private fun ipv4(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else @Suppress("DEPRECATION") listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
    }

    companion object {
        private const val TAG = "ServerDiscovery"
        const val SERVICE_TYPE = "_livesub._tcp"
    }
}
