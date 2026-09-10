package com.ergouf.gecis.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetAddress

/**
 * Projects Android's active network configuration into the small Linux-style surface the
 * embedded glibc runtime expects.
 */
internal object RuntimeNetworkEnvironment {
    private const val RESOLV_CONF = "resolv.conf"
    private const val CA_BUNDLE = "cacert.pem"
    private const val CA_ASSET = "runtime/cacert.pem"

    data class Prepared(
        val resolvConf: File,
        val caBundle: File,
    )

    fun prepare(context: Context): Prepared {
        val root = context.noBackupFilesDir
        val resolvConf = File(root, RESOLV_CONF)
        val caBundle = File(root, CA_BUNDLE)

        writeActiveDns(context, resolvConf)
        copyPinnedCaBundle(context, caBundle)
        return Prepared(resolvConf, caBundle)
    }

    private fun writeActiveDns(context: Context, destination: File) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("无法读取 Android 网络配置")

        val dnsServers = linkedSetOf<InetAddress>()
        manager.activeNetwork?.let { network ->
            manager.getLinkProperties(network)?.dnsServers?.let(dnsServers::addAll)
        }
        if (dnsServers.isEmpty()) {
            for (network in manager.allNetworks) {
                manager.getLinkProperties(network)?.dnsServers?.let(dnsServers::addAll)
                if (dnsServers.isNotEmpty()) break
            }
        }
        if (dnsServers.isEmpty()) {
            throw IllegalStateException("当前网络没有可用 DNS，请检查网络连接")
        }

        val text = buildString {
            dnsServers.take(3).forEach { address ->
                val host = address.hostAddress?.substringBefore('%').orEmpty()
                if (host.isNotBlank()) append("nameserver ").append(host).append('\n')
            }
            append("options timeout:2 attempts:2\n")
        }
        destination.writeText(text)
    }

    private fun copyPinnedCaBundle(context: Context, destination: File) {
        try {
            context.assets.open(CA_ASSET).use { input ->
                val temp = File(destination.parentFile, "$CA_BUNDLE.tmp")
                temp.outputStream().use { output -> input.copyTo(output) }
                if (destination.exists() && !destination.delete()) {
                    temp.delete()
                    throw IllegalStateException("无法更新 TLS 根证书")
                }
                if (!temp.renameTo(destination)) {
                    temp.delete()
                    throw IllegalStateException("无法安装 TLS 根证书")
                }
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException(
                "APK 缺少已验证的 TLS 根证书资源；请通过 native staging 构建 Gecis",
                error,
            )
        }
    }
}
