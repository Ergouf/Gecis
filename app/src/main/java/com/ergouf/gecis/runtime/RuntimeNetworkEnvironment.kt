package com.ergouf.gecis.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.Uri
import java.io.File
import java.net.InetAddress

/**
 * Projects Android's active network configuration into the small Linux-style surface the
 * embedded glibc runtime expects.
 *
 * VPN/TUN routing is handled by Android at the UID/network layer. Explicit Android HTTP proxy
 * settings additionally need to be projected as standard proxy environment variables because the
 * embedded native process does not use Android's Java ProxySelector.
 *
 * Android's DNS resolver is also network-aware (including VPN/private-DNS routing), while the
 * embedded Termux glibc resolver is not. Resolve Antigravity's known Google endpoints through the
 * active Android Network and pin those answers into the private hosts file before starting agy.
 */
internal object RuntimeNetworkEnvironment {
    private const val RESOLV_CONF = "resolv.conf"
    private const val HOSTS_FILE = "hosts"
    private const val NSSWITCH_CONF = "nsswitch.conf"
    private const val CA_BUNDLE = "cacert.pem"
    private const val CA_ASSET = "runtime/cacert.pem"

    data class Prepared(
        val resolvConf: File,
        val hostsFile: File,
        val nsswitchConf: File,
        val caBundle: File,
        val proxyEnvironment: Map<String, String>,
        val summary: String,
    )

    fun prepare(context: Context): Prepared {
        val root = context.noBackupFilesDir
        val resolvConf = File(root, RESOLV_CONF)
        val hostsFile = File(root, HOSTS_FILE)
        val nsswitchConf = File(root, NSSWITCH_CONF)
        val caBundle = File(root, CA_BUNDLE)
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("无法读取 Android 网络配置")

        val activeNetwork = manager.activeNetwork
        val activeLinkProperties = activeNetwork?.let(manager::getLinkProperties)
        val capabilities = activeNetwork?.let(manager::getNetworkCapabilities)
        val usingVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val dnsServers = linkedSetOf<InetAddress>()
        activeLinkProperties?.dnsServers?.let(dnsServers::addAll)
        if (dnsServers.isEmpty()) {
            for (network in manager.allNetworks) {
                manager.getLinkProperties(network)?.dnsServers?.let(dnsServers::addAll)
                if (dnsServers.isNotEmpty()) break
            }
        }
        if (dnsServers.isEmpty()) {
            throw IllegalStateException("当前网络没有可用 DNS，请检查网络连接")
        }

        val pinnedHosts = resolveAntigravityHosts(activeNetwork)
        writeDns(dnsServers, resolvConf)
        writeHosts(hostsFile, pinnedHosts)
        writeNsswitch(nsswitchConf)
        copyPinnedCaBundle(context, caBundle)

        val proxy = activeLinkProperties?.httpProxy
            ?: manager.allNetworks.asSequence()
                .mapNotNull(manager::getLinkProperties)
                .mapNotNull { it.httpProxy }
                .firstOrNull()
        val proxyEnvironment = buildProxyEnvironment(proxy)
        val proxySummary = describeProxy(proxy)
        val resolvedCount = pinnedHosts.count { it.addresses.isNotEmpty() }
        val failedHosts = pinnedHosts.filter { it.addresses.isEmpty() }.map { it.host }
        val dnsSummary = if (failedHosts.isEmpty()) {
            "AndroidDNS=$resolvedCount/${ANTIGRAVITY_HOSTS.size}"
        } else {
            "AndroidDNS=$resolvedCount/${ANTIGRAVITY_HOSTS.size}，未解析=${failedHosts.joinToString(",")}" 
        }
        val summary =
            "VPN=${if (usingVpn) "是" else "否"}，代理=$proxySummary，$dnsSummary"

        return Prepared(
            resolvConf = resolvConf,
            hostsFile = hostsFile,
            nsswitchConf = nsswitchConf,
            caBundle = caBundle,
            proxyEnvironment = proxyEnvironment,
            summary = summary,
        )
    }

    private fun resolveAntigravityHosts(activeNetwork: Network?): List<ResolvedHost> {
        if (activeNetwork == null) {
            return ANTIGRAVITY_HOSTS.map { ResolvedHost(it, emptyList()) }
        }
        return ANTIGRAVITY_HOSTS.map { host ->
            val addresses = runCatching {
                activeNetwork.getAllByName(host)
                    .asSequence()
                    .filter { it.hostAddress?.isNotBlank() == true }
                    .distinctBy { it.hostAddress }
                    .take(MAX_ADDRESSES_PER_HOST)
                    .toList()
            }.getOrDefault(emptyList())
            ResolvedHost(host, addresses)
        }
    }

    private fun writeDns(dnsServers: Set<InetAddress>, destination: File) {
        val text = buildString {
            dnsServers.take(3).forEach { address ->
                val host = address.hostAddress?.substringBefore('%').orEmpty()
                if (host.isNotBlank()) append("nameserver ").append(host).append('\n')
            }
            append("options timeout:2 attempts:2\n")
        }
        destination.writeText(text)
    }

    private fun writeHosts(destination: File, resolvedHosts: List<ResolvedHost>) {
        destination.writeText(
            buildString {
                append("127.0.0.1 localhost localhost.localdomain\n")
                append("::1 localhost localhost.localdomain ip6-localhost ip6-loopback\n")
                for (resolved in resolvedHosts) {
                    for (address in resolved.addresses) {
                        val ip = address.hostAddress?.substringBefore('%').orEmpty()
                        if (ip.isNotBlank()) {
                            append(ip).append(' ').append(resolved.host).append('\n')
                        }
                    }
                }
            },
        )
    }

    private fun writeNsswitch(destination: File) {
        // Resolve loopback and Android-pre-resolved Antigravity endpoints from the projected hosts
        // file first, then use glibc DNS only as a fallback for names we do not know in advance.
        destination.writeText(
            "hosts: files dns\n" +
                "networks: files dns\n",
        )
    }

    private fun buildProxyEnvironment(proxy: ProxyInfo?): Map<String, String> {
        if (proxy == null) return emptyMap()
        val pac = proxy.pacFileUrl
        if (pac != null && pac != Uri.EMPTY) {
            // Native Go/Linux processes cannot directly execute Android PAC resolution. VPN/TUN
            // traffic still works at the Android network layer, but a PAC-only configuration is
            // surfaced in diagnostics instead of being converted incorrectly.
            return emptyMap()
        }

        val host = proxy.host?.trim().orEmpty()
        val port = proxy.port
        if (host.isBlank() || port <= 0) return emptyMap()

        val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val proxyUrl = "http://$urlHost:$port"
        val exclusions = buildList {
            add("localhost")
            add("127.0.0.1")
            add("::1")
            proxy.exclusionList
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.let(::addAll)
        }.distinct().joinToString(",")

        return mapOf(
            "HTTP_PROXY" to proxyUrl,
            "HTTPS_PROXY" to proxyUrl,
            "http_proxy" to proxyUrl,
            "https_proxy" to proxyUrl,
            "NO_PROXY" to exclusions,
            "no_proxy" to exclusions,
        )
    }

    private fun describeProxy(proxy: ProxyInfo?): String {
        if (proxy == null) return "未检测到系统 HTTP 代理"
        val pac = proxy.pacFileUrl
        if (pac != null && pac != Uri.EMPTY) return "PAC"
        val host = proxy.host?.trim().orEmpty()
        return if (host.isNotBlank() && proxy.port > 0) "$host:${proxy.port}" else "未检测到系统 HTTP 代理"
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

    private data class ResolvedHost(
        val host: String,
        val addresses: List<InetAddress>,
    )

    companion object {
        private const val MAX_ADDRESSES_PER_HOST = 4

        // Endpoints observed in Antigravity/Cloud Code integrations. The runtime still retains DNS
        // fallback for future endpoints, but these critical hosts bypass glibc DNS and therefore
        // follow Android's active VPN/private-DNS resolver exactly.
        private val ANTIGRAVITY_HOSTS = listOf(
            "daily-cloudcode-pa.googleapis.com",
            "cloudcode-pa.googleapis.com",
            "cloudaicompanion.googleapis.com",
            "antigravity-pa.googleapis.com",
            "antigravity.googleapis.com",
            "alkalicore-pa.clients6.google.com",
            "alkalimakersuite-pa.clients6.google.com",
        )
    }
}
