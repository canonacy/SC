package com.v2ray.ang.handler

import com.v2ray.ang.dto.V2rayConfig

/**
 * Pure helpers that translate SteamDL server domain patterns into Xray routing
 * rules. Kept free of Android dependencies so the mapping stays JVM-testable.
 *
 * Pattern semantics mirror the official SteamDL client (proxy/patterns.py):
 *  - ".foo.com" matches "foo.com" and every subdomain -> Xray "domain:foo.com"
 *  - "bar.com"  matches exactly                      -> Xray "full:bar.com"
 */
object SteamDlRules {

    data class DomainEntry(
        val pattern: String,
        val blockHttps: Boolean = false,
    )

    fun toXrayDomain(pattern: String): String {
        return if (pattern.startsWith(".")) {
            "domain:${pattern.substring(1)}"
        } else {
            "full:$pattern"
        }
    }

    fun toXrayDomains(patterns: List<String>): List<String> = patterns.map(::toXrayDomain)

    /**
     * Builds the SteamDL routing rules in evaluation order:
     *  1. TLS (443) to block_https patterns -> block (forces the HTTP fallback through the cache)
     *  2. HTTP (80)  to any pattern         -> local SteamDL bridge
     *  3. TLS (443)  to any pattern         -> local SteamDL bridge (raw passthrough to cache:443)
     */
    fun buildRules(
        entries: List<DomainEntry>,
        steamdlTag: String,
        blockTag: String,
    ): List<V2rayConfig.RoutingBean.RulesBean> {
        if (entries.isEmpty()) return emptyList()
        val all = toXrayDomains(entries.map { it.pattern })
        val blocked = toXrayDomains(entries.filter { it.blockHttps }.map { it.pattern })
        return buildList {
            if (blocked.isNotEmpty()) {
                add(
                    V2rayConfig.RoutingBean.RulesBean(
                        network = "tcp",
                        domain = blocked,
                        port = "443",
                        outboundTag = blockTag,
                    )
                )
            }
            add(
                V2rayConfig.RoutingBean.RulesBean(
                    network = "tcp",
                    domain = all,
                    port = "80",
                    outboundTag = steamdlTag,
                )
            )
            add(
                V2rayConfig.RoutingBean.RulesBean(
                    network = "tcp",
                    domain = all,
                    port = "443",
                    outboundTag = steamdlTag,
                )
            )
        }
    }
}
