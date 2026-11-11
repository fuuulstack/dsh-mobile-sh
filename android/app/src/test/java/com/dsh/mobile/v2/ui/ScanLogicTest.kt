package com.dsh.mobile.v2.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 扫码逻辑 JVM 回归：两个可离线测的纯函数。
 *
 * 1. decodeLuminance：把 zxing 生成的二维码矩阵铺成相机 Y 平面（含 rowStride 填充），
 *    走真实解码路径解回——证明「相机帧 → 解码」这段真的通，而不是只测了解析器。
 * 2. parsePairing：深链契约（dsh://pair?relay=&code=）与兜底格式、非法输入。
 */
class ScanLogicTest {

    // ============================================================
    // decodeLuminance
    // ============================================================

    @Test
    fun `紧凑 Y 平面（rowStride 等于 width）能解回原文`() {
        val text = "dsh://pair?relay=https://relay.example.com&code=12345678"
        val (luma, width, height) = qrLuma(text, rowStride = 0)
        assertEquals(text, decodeLuminance(luma, width, width, height))
    }

    @Test
    fun `带行填充的 Y 平面（rowStride 大于 width）也能解回`() {
        val text = "dsh://pair?relay=https://relay.local.test&code=87654321"
        val (luma, width, height) = qrLuma(text, rowStride = 64)
        assertEquals(text, decodeLuminance(luma, width + 64, width, height))
    }

    @Test
    fun `null 数据与非法参数返回 null 而不抛`() {
        assertNull(decodeLuminance(null, 100, 100, 100))
        assertNull(decodeLuminance(ByteArray(4), 100, 0, 100))
        assertNull(decodeLuminance(ByteArray(4), 90, 100, 100)) // rowStride < width
        assertNull(decodeLuminance(ByteArray(4), 100, 100, 0))
    }

    @Test
    fun `不是二维码内容的数据返回 null`() {
        // 灰度均匀的平面：解码器必然 NotFoundException。
        val flat = ByteArray(128 * 128) { 128.toByte() }
        assertNull(decodeLuminance(flat, 128, 128, 128))
    }

    /**
     * 用 zxing 生成二维码并铺成 Y 平面。
     *
     * @param rowStride 每行额外填充的字节数（相机帧的行跨度通常大于 width）
     */
    private fun qrLuma(text: String, rowStride: Int): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 256, 256,
            mapOf(EncodeHintType.MARGIN to 0),
        )
        val width = matrix.width
        val height = matrix.height
        val stride = width + rowStride
        val luma = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 值域故意给足对比（黑 40 / 白 215），别贴 0/255 的极端。
                luma[y * stride + x] = (if (matrix[x, y]) 40 else 215).toByte()
            }
        }
        return Triple(luma, width, height)
    }

    // ============================================================
    // parsePairing
    // ============================================================

    @Test
    fun `dsh 深链解出 relay 与 code`() {
        val p = parsePairing("dsh://pair?relay=https://relay.example.com&code=12345678")
        assertNotNull(p)
        assertEquals("https://relay.example.com", p!!.relay)
        assertEquals("12345678", p.code)
    }

    @Test
    fun `relay 无协议自动补 https，尾斜杠剥掉`() {
        val p = parsePairing("dsh://pair?relay=relay.example.com/&code=12345678")
        assertEquals("https://relay.example.com", p!!.relay)
    }

    @Test
    fun `query 值做 percent-decode`() {
        val p = parsePairing("dsh://pair?relay=https%3A%2F%2Frelay.example.com&code=12345678")
        assertEquals("https://relay.example.com", p!!.relay)
    }

    @Test
    fun `https 加 8 位 fragment 的兜底格式`() {
        val p = parsePairing("https://relay.example.com/#12345678")
        assertEquals("https://relay.example.com", p!!.relay)
        assertEquals("12345678", p.code)
    }

    @Test
    fun `非 8 位 code 拒绝`() {
        assertNull(parsePairing("dsh://pair?relay=https://relay.example.com&code=1234567"))
        assertNull(parsePairing("dsh://pair?relay=https://relay.example.com&code=123456789"))
        assertNull(parsePairing("https://relay.example.com/#1234"))
    }

    @Test
    fun `缺字段、别的 scheme、乱码都拒绝`() {
        assertNull(parsePairing(null))
        assertNull(parsePairing(""))
        assertNull(parsePairing("dsh://pair?code=12345678"))
        assertNull(parsePairing("dsh://pair?relay=https://relay.example.com"))
        assertNull(parsePairing("https://relay.example.com/"))
        assertNull(parsePairing("weixin://wx/abc"))
        assertNull(parsePairing("not a uri at all %%%"))
    }
}
