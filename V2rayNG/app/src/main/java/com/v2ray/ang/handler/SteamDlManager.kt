package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads and caches the public SteamDL client configuration
 * (https://files.steamdl.ir/client_config.json): the hijackable game-CDN domain
 * patterns, the cache domain and the cache IP. The same JSON the official
 * Windows/Linux clients consume.
 */
object SteamDlManager {

    private const val TAG = "SteamDlManager"
    private const val CACHE_FILE = "steamdl_config.json"

    data class ServerConfig(
        val cacheDomain: String,
        val cacheIp: String,
        val domains: List<SteamDlRules.DomainEntry>,
    )

    /** Runtime values required by the local SteamDL bridge. */
    data class Runtime(
        val cacheIp: String,
        val cacheDomain: String,
        val token: String,
    )

    @Volatile
    private var cached: ServerConfig? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun cachedOrNull(): ServerConfig? = cached

    /**
     * Synchronous memory/disk load for service and config-generation startup
     * paths. Callers must run it off the main thread.
     */
    @Synchronized
    fun loadFromDiskBlocking(context: Context): ServerConfig? {
        cached?.let { return it }
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.isFile) return null
            parse(file.readText())?.also { cached = it }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to read cached SteamDL config", e)
            null
        }
    }

    /** Fetches the config from SteamDL and persists it for offline startup. */
    suspend fun refresh(context: Context): Result<ServerConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(AppConfig.STEAMDL_CONFIG_URL).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("config HTTP ${response.code}")
                }
                val body = response.body?.string() ?: error("empty config body")
                val config = parse(body) ?: error("invalid config json")
                File(context.filesDir, CACHE_FILE).writeText(body)
                cached = config
                LogUtil.i(TAG, "SteamDL config loaded: ${config.domains.size} domains, cache ${config.cacheIp}")
                config
            }
        }.onFailure { e ->
            LogUtil.e(TAG, "SteamDL config refresh failed", e)
        }
    }

    /** Runtime snapshot for the bridge, or null when no config is available yet. */
    fun runtime(context: Context): Runtime? {
        val config = cachedOrNull() ?: loadFromDiskBlocking(context) ?: return null
        if (config.cacheIp.isBlank() || config.cacheDomain.isBlank()) return null
        val token = MmkvManager.decodeSettingsString(AppConfig.PREF_STEAMDL_TOKEN).orEmpty()
        return Runtime(
            cacheIp = config.cacheIp,
            cacheDomain = config.cacheDomain,
            token = token,
        )
    }

    private fun parse(json: String): ServerConfig? {
        return try {
            val root = JSONObject(json)
            val cacheDomain = root.optString("cache_domain")
            val cacheIp = root.optString("cache_ip")
            val domains = mutableListOf<SteamDlRules.DomainEntry>()
            val array = root.optJSONArray("domains")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val pattern = obj.optString("pattern")
                    if (pattern.isNotBlank()) {
                        domains.add(
                            SteamDlRules.DomainEntry(
                                pattern = pattern,
                                blockHttps = obj.optBoolean("block_https", false),
                            )
                        )
                    }
                }
            }
            if (cacheDomain.isBlank() || cacheIp.isBlank() || domains.isEmpty()) {
                null
            } else {
                ServerConfig(cacheDomain = cacheDomain, cacheIp = cacheIp, domains = domains)
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to parse SteamDL config", e)
            null
        }
    }
}
