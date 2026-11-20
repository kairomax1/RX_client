package com.v2ray.ang.handler

import com.v2ray.ang.handler.PublicConfigs.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class PublicConfigsTest {

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun base64IsReadInBothAlphabetsWithOrWithoutPadding() {
        assertEquals("aes-256-gcm:pass", String(PublicConfigs.decodeBase64(b64("aes-256-gcm:pass"))!!))
        assertEquals("aes-256-gcm:pass", String(PublicConfigs.decodeBase64(b64("aes-256-gcm:pass").trimEnd('='))!!))
        assertEquals(listOf<Byte>(-5, -1), PublicConfigs.decodeBase64("-_8")!!.toList())
        assertEquals(listOf<Byte>(-5, -1), PublicConfigs.decodeBase64("+/8=")!!.toList())
        assertNull(PublicConfigs.decodeBase64("not base64!"))
    }

    @Test
    fun vlessNeedsTlsOrRealityAndCertificateChecking() {
        val good = PublicConfigs.parse("vless://id@1.2.3.4:443?security=reality&sni=a.com&pbk=x#name")
        assertEquals(PublicConfigs.Link("vless://id@1.2.3.4:443?security=reality&sni=a.com&pbk=x#name", Kind.Vless, "1.2.3.4", 443), good)
        assertNotNull(PublicConfigs.parse("vless://id@[2001:db8::1]:8443?security=tls"))
        assertEquals("2001:db8::1", PublicConfigs.parse("vless://id@[2001:db8::1]:8443?security=tls")!!.host)
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:80?security=none&type=ws"), "no encryption at all")
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:80?type=ws"))
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:443?security=tls&allowInsecure=1"))
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:443?security=tls&insecure=true"))
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:443?security=tls&sni=x.workers.dev"), "exit would be Cloudflare's")
        assertNull(PublicConfigs.parse("vless://id@127.0.0.1:443?security=tls"))
        assertNull(PublicConfigs.parse("vless://id@1.2.3.4:99999?security=tls"))
    }

    @Test
    fun trojanAndHysteriaAndWireGuard() {
        assertNotNull(PublicConfigs.parse("trojan://pw@host.example:443?sni=host.example"))
        assertNull(PublicConfigs.parse("trojan://pw@host.example:443?security=none"))
        assertNull(PublicConfigs.parse("trojan://pw@host.example:443?allowInsecure=1"))
        assertEquals(Kind.Hysteria, PublicConfigs.parse("hy2://pw@host.example:443,5000-6000/?sni=a.com")!!.kind)
        assertEquals(443, PublicConfigs.parse("hysteria2://pw@host.example:443,5000-6000?sni=a.com")!!.port)
        assertNull(PublicConfigs.parse("hysteria2://pw@host.example:443?insecure=1"))
        assertEquals(Kind.WireGuard, PublicConfigs.parse("wireguard://key@5.6.7.8:51820?publickey=x&address=10.0.0.2/32")!!.kind)
    }

    @Test
    fun whatTheCoreCannotRunOrIsPlainIsRefused() {
        for (link in listOf("socks://x@1.2.3.4:1080", "http://1.2.3.4:8080", "ssr://abc", "tuic://id:pw@1.2.3.4:443", "hello", "")) {
            assertNull(PublicConfigs.parse(link), link)
        }
    }

    @Test
    fun vmessNeedsTheModernHandshake() {
        fun vmess(aid: String, extra: String = "") =
            "vmess://" + b64("""{"v":"2","ps":"n","add":"9.8.7.6","port":"443","id":"u","aid":"$aid","net":"ws","tls":"tls"$extra}""")
        assertEquals(PublicConfigs.Link(vmess("0"), Kind.Vmess, "9.8.7.6", 443, direct = false), PublicConfigs.parse(vmess("0")))
        assertNull(PublicConfigs.parse(vmess("64")))
        assertNull(PublicConfigs.parse(vmess("0", ""","allowInsecure":"1"""")))
        assertNull(PublicConfigs.parse("vmess://" + b64("""{"add":"9.8.7.6","port":80,"aid":0,"scy":"none"}""")))
        assertNotNull(PublicConfigs.parse("vmess://" + b64("""{"add":"9.8.7.6","port":80,"aid":0,"scy":"auto"}""")))
        assertNull(PublicConfigs.parse("vmess://not-json"))
    }

    @Test
    fun shadowsocksNeedsACipherThatAuthenticates() {
        assertEquals(Kind.Shadowsocks, PublicConfigs.parse("ss://" + b64("aes-256-gcm:pw") + "@1.2.3.4:8388#n")!!.kind)
        assertEquals(8388, PublicConfigs.parse("ss://" + b64("chacha20-ietf-poly1305:pw@1.2.3.4:8388") + "#n")!!.port)
        assertNotNull(PublicConfigs.parse("ss://2022-blake3-aes-128-gcm:key@1.2.3.4:8388"))
        assertNull(PublicConfigs.parse("ss://" + b64("aes-256-cfb:pw") + "@1.2.3.4:8388"))
        assertNull(PublicConfigs.parse("ss://" + b64("rc4-md5:pw") + "@1.2.3.4:8388"))
        assertNull(PublicConfigs.parse("ss://" + b64("none:pw") + "@1.2.3.4:8388"))
    }

    @Test
    fun eachServerOnceAndTheSortsTakeTurns() {
        val lines = sequenceOf(
            "vless://a@1.1.1.1:443?security=tls#one",
            "vless://b@1.1.1.1:443?security=tls#same server again",
            "vless://c@2.2.2.2:443?security=tls",
            "trojan://p@3.3.3.3:443",
            "socks://x@4.4.4.4:1080",
            "hysteria2://p@5.5.5.5:443",
        )
        val all = PublicConfigs.select(lines, Kind.entries.toSet())
        assertEquals(listOf("1.1.1.1", "3.3.3.3", "5.5.5.5", "2.2.2.2"), all.map { it.host })
        assertEquals(listOf("3.3.3.3"), PublicConfigs.select(lines, setOf(Kind.Trojan)).map { it.host })
    }

    @Test
    fun aListMayBePlainOrOneBase64Block() {
        val plain = "# note\nvless://a@1.1.1.1:443?security=tls\n\ntrojan://p@3.3.3.3:443\n"
        assertEquals(2, PublicConfigs.toLines(plain).size)
        assertEquals(2, PublicConfigs.toLines(b64(plain).chunked(40).joinToString("\n")).size)
        assertEquals(0, PublicConfigs.toLines("<html>blocked</html>").size)
    }

    @Test
    fun gameModeLeavesOutWebTransports() {
        val lines = sequenceOf(
            "vless://a@1.1.1.1:443?security=tls&type=ws&host=a.com",
            "vless://b@2.2.2.2:443?security=reality&type=tcp",
            "trojan://p@3.3.3.3:443?type=grpc",
            "hysteria2://p@5.5.5.5:443",
            "ss://" + b64("aes-256-gcm:pw") + "@6.6.6.6:8388",
        )
        assertEquals(listOf("2.2.2.2", "5.5.5.5", "6.6.6.6"), PublicConfigs.select(lines, Kind.entries.toSet(), game = true).map { it.host })
        assertEquals(5, PublicConfigs.select(lines, Kind.entries.toSet()).size)
    }

    @Test
    fun aServerSeenBeforeIsNotTakenAgain() {
        val seen = HashSet<String>()
        assertEquals(1, PublicConfigs.select(sequenceOf("trojan://p@3.3.3.3:443"), Kind.entries.toSet(), seen = seen).size)
        assertEquals(0, PublicConfigs.select(sequenceOf("trojan://q@3.3.3.3:443#again"), Kind.entries.toSet(), seen = seen).size)
    }

    @Test
    fun cloudflareAddressesAreRecognised() {
        fun v4(text: String) = text.split('.').map { it.toInt().toByte() }.toByteArray()
        for (address in listOf("104.16.0.1", "104.27.255.255", "172.67.1.1", "188.114.97.3", "162.159.192.1", "198.41.200.9")) {
            assertEquals(true, PublicConfigs.isCloudflare(v4(address)), address)
        }
        for (address in listOf("104.32.0.1", "172.80.0.1", "8.8.8.8", "188.114.112.1", "5.6.7.8", "198.41.127.255")) {
            assertEquals(false, PublicConfigs.isCloudflare(v4(address)), address)
        }
        val six = ByteArray(16).also { it[0] = 0x26; it[1] = 0x06; it[2] = 0x47; it[3] = 0x00 }
        assertEquals(true, PublicConfigs.isCloudflare(six))
        assertEquals(false, PublicConfigs.isCloudflare(ByteArray(16).also { it[0] = 0x20; it[1] = 0x01 }))
    }

    @Test
    fun flagsAndSources() {
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", PublicConfigs.flag("DE"))
        assertEquals("\uD83C\uDF10", PublicConfigs.flag(""))
        assertEquals("\uD83C\uDF10", PublicConfigs.flag("d1"))
        assertEquals(PublicConfigs.SOURCES.size, PublicConfigs.SOURCES.map { it.address }.toSet().size, "no source twice")
        assertEquals("https://cdn.jsdelivr.net/gh/a/b@main/c.txt", PublicConfigs.Source("a/b", "main", "c.txt").mirror)
        assertEquals(true, PublicConfigs.SOURCES.any { it.isTelegram })
    }

    @Test
    fun telegramHtmlChannelsAreParsedCleanly() {
        val html = """
            <div class="tgme_widget_message_text js-message_text" dir="auto">
              <code>vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=reality&amp;sni=a.com&amp;pbk=abc#ch1</code><br/>
              trojan://secret@5.6.7.8:443?sni=b.com#ch2
            </div>
        """.trimIndent()
        val lines = PublicConfigs.toLines(html)
        assertEquals(2, lines.size)
        assertEquals(
            "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=reality&sni=a.com&pbk=abc#ch1",
            lines[0],
        )
        assertEquals("trojan://secret@5.6.7.8:443?sni=b.com#ch2", lines[1])
    }

    @Test
    fun antiCensorshipProtocolsAreRankedFirstWithinEachKind() {
        val lines = sequenceOf(
            "vless://a@1.1.1.1:443?security=tls&type=ws&host=a.com#ws",
            "vless://b@2.2.2.2:443?security=reality&sni=a.com&pbk=x&flow=xtls-rprx-vision#reality",
            "vless://c@3.3.3.3:443?security=tls&type=xhttp&host=a.com#xhttp",
            "hy2://p@4.4.4.4:443?sni=a.com#plain",
            "hy2://p@5.5.5.5:443?sni=a.com&obfs=salamander&obfs-password=pw#salamander",
        )
        val vlessHosts = PublicConfigs.select(lines, setOf(Kind.Vless)).map { it.host }
        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1"), vlessHosts)

        val hyHosts = PublicConfigs.select(lines, setOf(Kind.Hysteria)).map { it.host }
        assertEquals(listOf("5.5.5.5", "4.4.4.4"), hyHosts)
    }

    @Test
    fun fragmentGroupIsHiddenAsInternalGroup() {
        assertEquals(true, PublicConfigs.isInternalGroup(FragmentVpn.GROUP_ID))
        assertEquals(true, FragmentVpn.isFragmentProfile(FragmentVpn.PROFILE_GUID))
    }
}
