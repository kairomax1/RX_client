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
        assertEquals(PublicConfigs.Link(vmess("0"), Kind.Vmess, "9.8.7.6", 443), PublicConfigs.parse(vmess("0")))
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
}
