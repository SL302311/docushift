package com.example.docushift_mobile

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * PdfInputValidator 边界测试（第 4 期）：
 * MIME / 大小三段回退 / 100 MiB 边界 / 页数 0/1/20/21 / 损坏或受保护 PDF 映射。
 *
 * 通过 [FakeContentResolver]（Mockito 受控 ContentResolver）与注入的
 * rendererProbe 在普通 JVM 下驱动真实验证路径。
 */
class PdfInputValidatorTest {

    private val uri = Mockito.mock(Uri::class.java)

    private fun validator(
        displayName: String = "report.pdf",
        mimeType: String = "application/pdf",
        cursorSize: Long? = 12345L,
        includeSizeColumn: Boolean = true,
        pageCount: Int = 3,
        probeThrows: Boolean = false,
    ): PdfInputValidator {
        val cr = FakeContentResolver(
            displayName = displayName,
            mimeType = mimeType,
            cursorSize = cursorSize,
            includeSizeColumn = includeSizeColumn,
        ).build()
        val v = PdfInputValidator(cr)
        v.rendererProbe = {
            if (probeThrows) throw SecurityException("password protected")
            pageCount
        }
        return v
    }

    @Test
    fun wrongMime_unsupportedFormat() {
        val r = validator(mimeType = "image/png").validate(uri)
        assertFalse(r.valid)
        assertEquals("UNSUPPORTED_FORMAT", r.errorCode)
    }

    @Test
    fun unknownSize_fileSizeUnknown() {
        // Cursor 无 SIZE 列，AFD/PFD 均为 null → 三段回退全部失败
        val r = validator(cursorSize = null, includeSizeColumn = false).validate(uri)
        assertFalse(r.valid)
        assertEquals("FILE_SIZE_UNKNOWN", r.errorCode)
    }

    @Test
    fun sizeAtLimit_passesSizeGate() {
        // 恰好 100 MiB → 通过大小门禁（后续页数正常）
        val r = validator(cursorSize = 100L * 1024 * 1024, pageCount = 1).validate(uri)
        assertTrue(r.valid)
    }

    @Test
    fun sizeOverLimit_fileTooLarge() {
        val r = validator(cursorSize = 100L * 1024 * 1024 + 1).validate(uri)
        assertFalse(r.valid)
        assertEquals("FILE_TOO_LARGE", r.errorCode)
    }

    @Test
    fun corruptOrProtectedPdf_pdfOpenFailed() {
        val r = validator(probeThrows = true).validate(uri)
        assertFalse(r.valid)
        assertEquals("PDF_OPEN_FAILED", r.errorCode)
    }

    @Test
    fun zeroPages_pdfOpenFailed() {
        val r = validator(pageCount = 0).validate(uri)
        assertFalse(r.valid)
        assertEquals("PDF_OPEN_FAILED", r.errorCode)
    }

    @Test
    fun onePage_valid() {
        val r = validator(pageCount = 1).validate(uri)
        assertTrue(r.valid)
        assertEquals(1, r.pageCount)
    }

    @Test
    fun twentyPages_valid() {
        val r = validator(pageCount = 20).validate(uri)
        assertTrue(r.valid)
        assertEquals(20, r.pageCount)
    }

    @Test
    fun twentyOnePages_tooManyPages() {
        val r = validator(pageCount = 21).validate(uri)
        assertFalse(r.valid)
        assertEquals("TOO_MANY_PAGES", r.errorCode)
        assertEquals(21, r.pageCount)   // 页数保留在结果中，便于消息展示
    }

    @Test
    fun validPdf_returnsNativeMetadata() {
        val r = validator(displayName = "月报.pdf", cursorSize = 2048L, pageCount = 5).validate(uri)
        assertTrue(r.valid)
        assertEquals("月报.pdf", r.displayName)
        assertEquals("application/pdf", r.mimeType)
        assertEquals(2048L, r.fileSizeBytes)
        assertEquals(5, r.pageCount)
    }

    @Test
    fun baseName_stripsExtension() {
        assertEquals("report", PdfInputValidator.baseName("report.pdf"))
        assertEquals("a.b", PdfInputValidator.baseName("a.b.pdf"))
        assertEquals("noext", PdfInputValidator.baseName("noext"))
        // 隐藏文件（点开头）不去尾
        assertEquals(".hidden", PdfInputValidator.baseName(".hidden"))
    }
}
