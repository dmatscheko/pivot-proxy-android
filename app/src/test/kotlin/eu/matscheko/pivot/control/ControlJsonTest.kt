package eu.matscheko.pivot.control

import eu.matscheko.pivot.ServerState
import eu.matscheko.pivot.VpnState
import eu.matscheko.pivot.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlJsonTest {

    @Test
    fun configJsonOmitsPasswordsButKeepsUsernames() {
        val json = ControlJson.configJson(
            AppSettings(
                egressUsername = "alice",
                egressPassword = "s3cret",
                upstreamUsername = "bob",
                upstreamPassword = "hunter2",
            ),
        )
        assertFalse("password must not leak", json.contains("s3cret"))
        assertFalse("password must not leak", json.contains("hunter2"))
        assertFalse(json.contains("egress_pass"))
        assertFalse(json.contains("upstream_pass"))
        assertTrue(json.contains("\"egress_user\":\"alice\""))
        assertTrue(json.contains("\"upstream_user\":\"bob\""))
    }

    @Test
    fun configJsonUsesConfigureExtraKeysAndTypes() {
        val json = ControlJson.configJson(AppSettings())
        // ints unquoted, booleans unquoted, strings quoted — keys match CONFIGURE extras.
        assertTrue(json.contains("\"egress_port\":1080"))
        assertTrue(json.contains("\"upstream_port\":8080"))
        assertTrue(json.contains("\"dns_over_proxy\":true"))
        assertTrue(json.contains("\"upstream_type\":\"socks5\""))
        assertTrue(json.contains("\"app_filter_mode\":\"off\""))
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun configJsonSerializesAppListAsArrayAndEscapesStrings() {
        val json = ControlJson.configJson(
            AppSettings(
                appList = setOf("com.example.a", "com.example.b"),
                bypassDomains = "a.com\nb\"c",
            ),
        )
        assertTrue(json.contains("\"app_list\":[\"com.example.a\",\"com.example.b\"]"))
        // newline and quote are escaped so the result is valid JSON.
        assertTrue(json.contains("a.com\\nb\\\"c"))
    }

    @Test
    fun vpnStatusDistinguishesStoppedFromPermissionRequired() {
        assertEquals("stopped", ControlJson.vpnStatus(VpnState.Off, consentGranted = true))
        assertEquals("permission_required", ControlJson.vpnStatus(VpnState.Off, consentGranted = false))
        assertEquals("starting", ControlJson.vpnStatus(VpnState.Starting, consentGranted = true))
        assertEquals(
            "running",
            ControlJson.vpnStatus(
                VpnState.Running(upstream = "127.0.0.1:8080", dnsOverProxy = true, upstreamType = "socks5"),
                consentGranted = true,
            ),
        )
        assertEquals("error", ControlJson.vpnStatus(VpnState.Error("boom"), consentGranted = true))
    }

    @Test
    fun egressStatusMapsEachState() {
        assertEquals("stopped", ControlJson.egressStatus(ServerState.Off))
        assertEquals("starting", ControlJson.egressStatus(ServerState.Starting))
        assertEquals("running", ControlJson.egressStatus(ServerState.Running(listOf("0.0.0.0"), 1080, 0)))
        assertEquals("error", ControlJson.egressStatus(ServerState.Error("boom")))
    }

    @Test
    fun statusJsonReportsBothEngines() {
        val json = ControlJson.statusJson(
            vpn = VpnState.Off,
            egress = ServerState.Running(listOf("0.0.0.0"), 1080, 0),
            vpnConsentGranted = false,
        )
        assertEquals("{\"vpn\":\"permission_required\",\"egress\":\"running\"}", json)
    }
}
