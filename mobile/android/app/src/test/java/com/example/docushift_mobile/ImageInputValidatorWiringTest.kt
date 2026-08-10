package com.example.docushift_mobile

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * ImageInputValidator 无设备接线测试：
 * - resolveFileSize 三段回退（SIZE → AFD.length → PFD.statSize）与未知大小拒绝；
 * - validate 的 MIME / 超限 / 未知大小 / 损坏图片(DECODE_FAILED) 门禁；
 * - classifyPixelCount 纯函数边界。
 *
 * 真实执行生产代码路径；ContentResolver / AFD / PFD 由 Mockito 受控。
 */
class ImageInputValidatorWiringTest {

    private val uri = Mockito.mock(Uri::class.java)

    // ================================================================
    // resolveFileSize 三段回退
    // ================================================================

    @Test
    fun resolveFileSize_stage1_sizeColumnUsed() {
        val cr = FakeContentResolver(cursorSize = 12345L, includeSizeColumn = true).build()
        val v = ImageInputValidator(cr)
        assertEquals(12345L, v.resolveFileSize(uri))
    }

    @Test
    fun resolveFileSize_stage2_assetFileDescriptorFallback() {
        val afd = Mockito.mock(AssetFileDescriptor::class.java)
        Mockito.`when`(afd.length).thenReturn(9999L)
        val cr = FakeContentResolver(
            cursorSize = null, includeSizeColumn = false, assetFileDescriptor = afd,
        ).build()
        val v = ImageInputValidator(cr)
        assertEquals(9999L, v.resolveFileSize(uri))
    }

    @Test
    fun resolveFileSize_stage3_parcelFileDescriptorFallback() {
        val pfd = Mockito.mock(ParcelFileDescriptor::class.java)
        Mockito.`when`(pfd.statSize).thenReturn(7777L)
        val cr = FakeContentResolver(
            cursorSize = null, includeSizeColumn = false,
            assetFileDescriptor = null, parcelFileDescriptor = pfd,
        ).build()
        val v = ImageInputValidator(cr)
        assertEquals(7777L, v.resolveFileSize(uri))
    }

    @Test
    fun resolveFileSize_allMissing_returnsZero() {
        val cr = FakeContentResolver(
            cursorSize = null, includeSizeColumn = false,
            assetFileDescriptor = null, parcelFileDescriptor = null,
        ).build()
        val v = ImageInputValidator(cr)
        assertEquals(0L, v.resolveFileSize(uri))
    }

    // ================================================================
    // validate 门禁
    // ================================================================

    @Test
    fun validate_unsupportedMime_rejected() {
        val cr = FakeContentResolver(mimeType = "image/gif", cursorSize = 1000L).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertEquals(false, res.valid)
        assertEquals("UNSUPPORTED_FORMAT", res.errorCode)
    }

    // ================================================================
    // 第 7 期：BMP MIME 通过验证
    // ================================================================

    @Test
    fun validate_bmpMime_passes() {
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validate(uri)
        assertEquals(true, res.valid)
        assertNull(res.errorCode)
    }

    @Test
    fun validate_bmpXMsBmpMime_passes() {
        val cr = FakeContentResolver(mimeType = "image/x-ms-bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validate(uri)
        assertEquals(true, res.valid)
        assertNull(res.errorCode)
    }

    @Test
    fun validate_bmp_40mPixels_passes() {
        // 4,000 万像素恰好通过（8,000 × 5,000 = 40,000,000）
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(8000, 5000) }
        val res = v.validate(uri)
        assertTrue(res.valid)
        assertNull(res.errorCode)
    }

    @Test
    fun validate_bmp_over40mPixels_rejected() {
        // 超过 4,000 万像素
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(6325, 6326) } // > 40,000,000
        val res = v.validate(uri)
        assertFalse(res.valid)
        assertEquals("IMAGE_TOO_LARGE", res.errorCode)
    }

    // 30 MiB 边界（第 7 期 R1 返工补测）
    @Test
    fun validate_bmp_30MiB_boundary_passes() {
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 30L * 1024 * 1024).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validate(uri)
        assertTrue(res.valid)
        assertNull(res.errorCode)
    }

    @Test
    fun validate_bmp_over30MiB_rejected() {
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 30L * 1024 * 1024 + 1).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validate(uri)
        assertFalse(res.valid)
        assertEquals("FILE_TOO_LARGE", res.errorCode)
    }

    // 拒绝非法 MIME（第 7 期 R1 返工补测）
    @Test
    fun validate_octetStreamMime_rejected() {
        val cr = FakeContentResolver(mimeType = "application/octet-stream", cursorSize = 1000L).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertFalse(res.valid)
        assertEquals("UNSUPPORTED_FORMAT", res.errorCode)
    }

    @Test
    fun validate_emptyMime_rejected() {
        val cr = FakeContentResolver(mimeType = "", cursorSize = 1000L).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertFalse(res.valid)
        assertEquals("UNSUPPORTED_FORMAT", res.errorCode)
    }

    // BMP bounds 解码失败（第 7 期 R1 返工补测）
    @Test
    fun validate_bmp_boundsDecodeFailure_decodeFailed() {
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(-1, -1) }
        val res = v.validate(uri)
        assertFalse(res.valid)
        assertEquals("DECODE_FAILED", res.errorCode)
    }

    @Test
    fun validate_fileTooLarge_rejected() {
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 30L * 1024 * 1024 + 1).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertEquals(false, res.valid)
        assertEquals("FILE_TOO_LARGE", res.errorCode)
    }

    @Test
    fun validate_unknownSize_rejected() {
        val cr = FakeContentResolver(
            mimeType = "image/png", cursorSize = null, includeSizeColumn = false,
            assetFileDescriptor = null, parcelFileDescriptor = null,
        ).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertEquals(false, res.valid)
        assertEquals("FILE_SIZE_UNKNOWN", res.errorCode)
    }

    @Test
    fun validate_corruptedImage_decodeFailed() {
        // 无真实 BitmapFactory（android.jar 桩），decodeStream 抛 Stub! 被捕获 → DECODE_FAILED
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val res = ImageInputValidator(cr).validate(uri)
        assertEquals(false, res.valid)
        assertEquals("DECODE_FAILED", res.errorCode)
    }

    // ================================================================
    // validate 像素门禁（4000 万上限从生产入口生效）
    // boundsProvider 注入受控宽高，绕过 android.jar 桩 BitmapFactory
    // ================================================================

    @Test
    fun validate_40MPBoundary_passes() {
        // 8000 * 5000 = 40,000,000 恰好等于上限 → 通过
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(8000, 5000) }
        val res = v.validate(uri)
        assertEquals(true, res.valid)
        assertNull(res.errorCode)
    }

    @Test
    fun validate_over40MP_rejected() {
        // 8001 * 5000 = 40,005,000 > 40,000,000 → IMAGE_TOO_LARGE（此前被放行）
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(8001, 5000) }
        val res = v.validate(uri)
        assertEquals(false, res.valid)
        assertEquals("IMAGE_TOO_LARGE", res.errorCode)
    }

    @Test
    fun validate_negativeBounds_decodeFailed() {
        // 非正尺寸经 classifyPixelCount → DECODE_FAILED
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(-1, -1) }
        val res = v.validate(uri)
        assertEquals(false, res.valid)
        assertEquals("DECODE_FAILED", res.errorCode)
    }

    // ================================================================
    // classifyPixelCount 纯函数边界
    // ================================================================

    @Test
    fun classifyPixelCount_negativeDims_isDecodeFailed() {
        assertEquals("DECODE_FAILED", ImageInputValidator.classifyPixelCount(-1, -1))
        assertEquals("DECODE_FAILED", ImageInputValidator.classifyPixelCount(0, 0))
        assertEquals("DECODE_FAILED", ImageInputValidator.classifyPixelCount(100, 0))
    }

    @Test
    fun classifyPixelCount_normal_returnsNull() {
        assertNull(ImageInputValidator.classifyPixelCount(100, 100))
        assertNull(ImageInputValidator.classifyPixelCount(4096, 4096))
    }

    @Test
    fun classifyPixelCount_40MPBoundary_isNull() {
        // 8000 * 5000 = 40,000,000 恰好等于上限
        assertNull(ImageInputValidator.classifyPixelCount(8000, 5000))
    }

    @Test
    fun classifyPixelCount_over40MP_isImageTooLarge() {
        assertEquals("IMAGE_TOO_LARGE", ImageInputValidator.classifyPixelCount(8001, 5000))
        assertEquals("IMAGE_TOO_LARGE", ImageInputValidator.classifyPixelCount(10000, 8000))
    }

    @Test
    fun classifyPixelCount_noLongOverflow_huge() {
        // 接近 Int 上限的宽高乘积不溢出 Long，正确判为超大
        val code = ImageInputValidator.classifyPixelCount(46000, 46000)
        assertEquals("IMAGE_TOO_LARGE", code)
        assertTrue(code != null)
    }
}
