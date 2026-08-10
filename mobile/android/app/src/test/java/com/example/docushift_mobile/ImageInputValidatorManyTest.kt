package com.example.docushift_mobile

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream

/**
 * ImageInputValidator.validateMany 多图门禁测试（真实执行生产代码路径）。
 *
 * 覆盖：空列表（INVALID_ARGS）、超过 20 张（TOO_MANY_FILES）、单文件 MIME 门禁
 * （含失败序号）、200 MiB 总大小上限（TOTAL_SIZE_TOO_LARGE）、以及全部通过时
 * 返回有序 ValidatedImage 列表。
 */
class ImageInputValidatorManyTest {

    private fun mockUris(n: Int): List<Uri> = List(n) { Mockito.mock(Uri::class.java) }

    @Test
    fun validateMany_empty_invalidArgs() {
        val cr = FakeContentResolver().build()
        val res = ImageInputValidator(cr).validateMany(emptyList())
        assertFalse(res.valid)
        assertEquals("INVALID_ARGS", res.errorCode)
        assertNull(res.failedIndex)
    }

    @Test
    fun validateMany_over20_tooManyFiles() {
        val uris = mockUris(21)
        val cr = FakeContentResolver(cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertFalse(res.valid)
        assertEquals("TOO_MANY_FILES", res.errorCode)
    }

    @Test
    fun validateMany_singleUnsupportedMime_rejectedWithIndex() {
        val uris = mockUris(1)
        val cr = FakeContentResolver(mimeType = "image/gif", cursorSize = 1000L).build()
        val res = ImageInputValidator(cr).validateMany(uris)
        assertFalse(res.valid)
        assertEquals("UNSUPPORTED_FORMAT", res.errorCode)
        assertEquals(0, res.failedIndex)
        assertTrue(res.errorMessage!!.contains("第 1 张"))
    }

    @Test
    fun validateMany_secondImageInvalid_reportsCorrectIndex() {
        val uris = mockUris(2)
        // 通过 boundsProvider 让第一张有效、第二张损坏（DECODE_FAILED）来体现序号。
        val resolver = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(resolver)
        var call = 0
        v.boundsProvider = { _ ->
            call++
            if (call == 2) Pair(-1, -1) else Pair(100, 100) // 第二张损坏
        }
        val res = v.validateMany(uris)
        assertFalse(res.valid)
        assertEquals("DECODE_FAILED", res.errorCode)
        assertEquals(1, res.failedIndex)
        assertTrue(res.errorMessage!!.contains("第 2 张"))
    }

    @Test
    fun validateMany_totalSizeOver200MiB_rejected() {
        // 每张 30 MiB（恰好通过单文件门禁），7 张累计 210 MiB > 200 MiB
        val uris = mockUris(7)
        val cr = FakeContentResolver(cursorSize = 30L * 1024 * 1024).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertFalse(res.valid)
        assertEquals("TOTAL_SIZE_TOO_LARGE", res.errorCode)
    }

    @Test
    fun validateMany_validMulti_returnsOrderedImages() {
        val uris = mockUris(3)
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertTrue(res.valid)
        assertNull(res.errorCode)
        assertEquals(3, res.images.size)
        assertEquals(uris[0], res.images[0].uri)
        assertEquals(uris[1], res.images[1].uri)
        assertEquals(uris[2], res.images[2].uri)
    }

    @Test
    fun validateMany_exactly20_passes() {
        // 数量上限为 20：恰好 20 张应全部通过（配合 validateMany_over20_tooManyFiles 形成边界）
        val uris = mockUris(20)
        val cr = FakeContentResolver(mimeType = "image/png", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertTrue(res.valid)
        assertNull(res.errorCode)
        assertEquals(20, res.images.size)
    }

    // ================================================================
    // 第 7 期：BMP 多图混合验证
    // ================================================================

    @Test
    fun validateMany_bmpMime_passes() {
        val uris = mockUris(1)
        val cr = FakeContentResolver(mimeType = "image/bmp", cursorSize = 1000L).build()
        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertTrue(res.valid)
        assertEquals(1, res.images.size)
        assertEquals("image/bmp", res.images[0].mimeType)
    }

    @Test
    fun validateMany_mixedPngJpgBmp_passes() {
        val u1 = Mockito.mock(Uri::class.java)
        val u2 = Mockito.mock(Uri::class.java)
        val u3 = Mockito.mock(Uri::class.java)
        val uris = listOf(u1, u2, u3)

        // 手动构建 ContentResolver：每个 URI 返回不同 MIME
        val cr = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(cr.getType(u1)).thenReturn("image/png")
        Mockito.`when`(cr.getType(u2)).thenReturn("image/jpeg")
        Mockito.`when`(cr.getType(u3)).thenReturn("image/bmp")
        Mockito.`when`(cr.openInputStream(Mockito.any())).thenReturn(ByteArrayInputStream(ByteArray(0)))

        val cursor = Mockito.mock(Cursor::class.java)
        Mockito.`when`(cursor.moveToFirst()).thenReturn(true)
        Mockito.`when`(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        Mockito.`when`(cursor.getString(0)).thenReturn("image.bmp")
        Mockito.`when`(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
        Mockito.`when`(cursor.getLong(1)).thenReturn(3000L)
        Mockito.`when`(
            cr.query(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())
        ).thenReturn(cursor)

        val v = ImageInputValidator(cr)
        v.boundsProvider = { _ -> Pair(100, 100) }
        val res = v.validateMany(uris)
        assertTrue(res.valid)
        assertEquals(3, res.images.size)
        assertEquals("image/png", res.images[0].mimeType)
        assertEquals("image/jpeg", res.images[1].mimeType)
        assertEquals("image/bmp", res.images[2].mimeType)
    }
}
