package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteamDlRulesTest {

    @Test
    fun dotPatternBecomesSubdomainMatcher() {
        assertEquals("domain:cdn.blizzard.com", SteamDlRules.toXrayDomain(".cdn.blizzard.com"))
    }

    @Test
    fun exactPatternBecomesFullMatcher() {
        assertEquals("full:dist.blizzard.com", SteamDlRules.toXrayDomain("dist.blizzard.com"))
    }

    @Test
    fun buildRulesContainsBlockHttpsRuleFirst() {
        val entries = listOf(
            SteamDlRules.DomainEntry("lancache.steamcontent.com", blockHttps = false),
            SteamDlRules.DomainEntry("cdn1.epicgames.com", blockHttps = true),
        )
        val rules = SteamDlRules.buildRules(entries, "steamdl", "block")

        assertEquals(3, rules.size)

        // 1. block_https patterns on 443 -> block, evaluated first
        assertEquals("block", rules[0].outboundTag)
        assertEquals("443", rules[0].port)
        assertEquals(listOf("full:cdn1.epicgames.com"), rules[0].domain)
        assertEquals("tcp", rules[0].network)

        // 2. every pattern on 80 -> bridge
        assertEquals("steamdl", rules[1].outboundTag)
        assertEquals("80", rules[1].port)
        assertEquals(
            listOf("full:lancache.steamcontent.com", "full:cdn1.epicgames.com"),
            rules[1].domain,
        )

        // 3. every pattern on 443 -> bridge
        assertEquals("steamdl", rules[2].outboundTag)
        assertEquals("443", rules[2].port)
        assertEquals(2, rules[2].domain?.size)
    }

    @Test
    fun buildRulesWithoutBlockHttpsEntriesSkipsBlockRule() {
        val entries = listOf(
            SteamDlRules.DomainEntry(".dyn.riotcdn.net", blockHttps = false),
        )
        val rules = SteamDlRules.buildRules(entries, "steamdl", "block")

        assertEquals(2, rules.size)
        assertTrue(rules.none { it.outboundTag == "block" })
    }

    @Test
    fun buildRulesOnEmptyEntriesReturnsNoRules() {
        assertTrue(SteamDlRules.buildRules(emptyList(), "steamdl", "block").isEmpty())
    }
}
