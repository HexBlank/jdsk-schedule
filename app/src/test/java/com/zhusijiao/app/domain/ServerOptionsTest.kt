package com.zhusijiao.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerOptionsTest {

    // ===== ServerAddress.normalize =====

    @Test
    fun `normalize keeps valid https host`() {
        assertEquals("https://api.example.com", ServerAddress.normalize(" https://API.Example.Com "))
    }

    @Test
    fun `normalize adds https scheme when missing`() {
        assertEquals("https://api.example.com", ServerAddress.normalize("api.example.com"))
    }

    @Test
    fun `normalize keeps port and drops trailing slash`() {
        assertEquals("https://api.example.com:8443", ServerAddress.normalize("https://api.example.com:8443/"))
    }

    @Test
    fun `normalize allows http for local debug hosts`() {
        assertEquals("http://10.0.2.2:3200", ServerAddress.normalize("http://10.0.2.2:3200"))
        assertEquals("http://localhost:3200", ServerAddress.normalize("localhost:3200"))
    }

    @Test
    fun `normalize rejects http for public host`() {
        assertNull(ServerAddress.normalize("http://api.example.com"))
    }

    @Test
    fun `normalize rejects path query fragment`() {
        assertNull(ServerAddress.normalize("https://api.example.com/api"))
        assertNull(ServerAddress.normalize("https://api.example.com/?x=1"))
        assertNull(ServerAddress.normalize("https://api.example.com/#frag"))
    }

    @Test
    fun `normalize rejects blank ftp userinfo and oversize`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("ftp://api.example.com"))
        assertNull(ServerAddress.normalize("https://user:pass@api.example.com"))
        assertNull(ServerAddress.normalize("https://" + "a".repeat(200) + ".com"))
    }

    // ===== UpdateChannelOptions.downloadBaseFor =====

    @Test
    fun `download base derives from stable manifest`() {
        assertEquals(
            "https://api.example.com/app/download/",
            UpdateChannelOptions.downloadBaseFor("https://api.example.com/app/release.json")
        )
    }

    @Test
    fun `download base derives from channel manifest`() {
        assertEquals(
            "https://api.example.com/app/ch/beta/download/",
            UpdateChannelOptions.downloadBaseFor("https://api.example.com/app/ch/beta/release.json")
        )
    }

    @Test
    fun `download base is null for foreign manifest`() {
        assertNull(UpdateChannelOptions.downloadBaseFor("https://raw.example.com/meta/manifest.json"))
    }

    // ===== UpdateChannelOptions.builtIns =====

    @Test
    fun `builtins point at server routes`() {
        val channels = UpdateChannelOptions.builtIns("https://api.example.com")
        assertEquals(UpdateChannelOptions.STABLE_ID, channels[0].id)
        assertEquals("https://api.example.com/app/release.json", channels[0].manifestUrl)
        assertEquals(UpdateChannelOptions.BETA_ID, channels[1].id)
        assertEquals("https://api.example.com/app/ch/beta/release.json", channels[1].manifestUrl)
    }

    // ===== 自定义通道的持久化（行格式，\u0001 分隔） =====

    @Test
    fun `custom channels roundtrip preserves fields and order`() {
        val list = listOf(
            UpdateChannelOption("c-a1", "内测", "https://beta.example.com/app/release.json"),
            UpdateChannelOption("c-b2", "实验室", "https://lab.example.com/release.json")
        )
        val parsed = UpdateChannelOptions.parseCustom(UpdateChannelOptions.toText(list))
        assertEquals(list, parsed)
    }

    @Test
    fun `custom channels cap at five keeping the latest`() {
        val list = (1..7).map {
            UpdateChannelOption("c-$it", "通道$it", "https://h$it.example.com/app/release.json")
        }
        val parsed = UpdateChannelOptions.parseCustom(UpdateChannelOptions.toText(list))
        assertEquals(UpdateChannelOptions.MAX_CUSTOM, parsed.size)
        assertEquals("通道3", parsed.first().label)
        assertEquals("通道7", parsed.last().label)
    }

    @Test
    fun `custom channels parse drops invalid lines`() {
        val text = "broken-line\nc-1\u0001测试\u0001https://ok.example.com/app/release.json\n\n\u0001\u0001"
        val parsed = UpdateChannelOptions.parseCustom(text)
        assertEquals(1, parsed.size)
        assertEquals("测试", parsed[0].label)
        assertEquals("https://ok.example.com/app/release.json", parsed[0].manifestUrl)
    }
}