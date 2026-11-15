package com.beichen.polaris.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 令牌算法对拍。
 *
 * 这 8 组样本直接取自上游 Python 实现（edge-tts 7.3.1 `drm.generate_sec_ms_gec_v2`）的输出。
 * 服务端只认这个哈希，算错的表现是 WebSocket 403——在真机上完全看不出是「少了一个异或」
 * 还是「时间取整不对」，所以必须在离线就把每一位钉死。
 */
class SecMsGecTest {

    private val vectors = listOf(
        1763200000L to "D3FE64A6A27FCCFA-D2F4E1ED43481E029BF5565B34083B638EC95540E698264C0C9F9195B2BA5444",
        1763200299L to "87D7813C79DBB460-E9B1714D9DAB346C16F94D2E1571DF28DBED717033EF68978C219F8E46A1947C",
        1700000000L to "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
        1763220000L to "83F1F2EB04CC15CC-B0EF4F244BE013C9EC2D532F2645901A8DE9DD0E935099B1F92EAC0D7288A3D7",
        1794730927L to "F9B76CFBEDD67DB5-6FDBE5A796A1BA8AA8C0EA1290CF5B53744F4704C66877A28DBF49F5512FB6FE",
        1763251200L to "4EA2F8192EF24717-BA58D0FB869C2B7FF1A997C954723E35C0B3A39C2BA0FC88DFC0D350A9931DD4",
    )

    @Test
    fun `令牌与上游输出逐字节一致`() {
        for ((timestamp, expected) in vectors) {
            assertEquals("时间戳 $timestamp", expected, SecMsGec.token(timestamp))
        }
    }

    /** 5 分钟内时间戳必须落在同一个令牌上：取整一旦写错，这里会红。 */
    @Test
    fun `同一五分钟窗口内令牌不变`() {
        val minutes = (1763200000L / 300) * 300
        val first = SecMsGec.token(minutes)
        assertEquals(first, SecMsGec.token(minutes + 299))
        assertEquals("跨窗口必须换令牌", false, first == SecMsGec.token(minutes + 300))
    }
}
