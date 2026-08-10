package com.example.docushift_mobile

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PdfToPngCoordinator 无设备接线测试（第 4 期）：
 * 真实执行 single-flight（compareAndSet）+ CompletionGuard + onDestroy 竞争逻辑，
 * 以及两步选择流（pickPdf 验证 → pickOutputDirectory → convertPdfToPng）。
 *
 * 通过可注入接缝（validateProvider / convertExecutor / pdfPicker / treePicker /
 * resolver / uriParser / outputDeleter / permissionTaker）在普通 JVM 下驱动
 * 真实协调流程，绕过平台渲染与 SAF UI。
 */
class PdfToPngCoordinatorWiringTest {

    private val pdfUri = Mockito.mock(Uri::class.java)
    private val treeUri = Mockito.mock(Uri::class.java)

    private fun validResult(pageCount: Int = 3) = PdfInputValidator.ValidationResult(
        true, "report.pdf", "application/pdf", 2048L, pageCount, null, null,
    )

    private fun coordinator(): PdfToPngCoordinator {
        val c = PdfToPngCoordinator()   // activity = null（测试路径）
        c.validateProvider = { validResult() }
        // android.jar 桩下 Uri.parse 返回 null → 注入 uriParser 返回受控 mock Uri
        c.uriParser = { s -> if (s.contains("tree")) treeUri else pdfUri }
        c.permissionTaker = { }
        Mockito.`when`(pdfUri.toString()).thenReturn("content://docushift/in.pdf")
        Mockito.`when`(treeUri.toString()).thenReturn("content://docushift/tree/out")
        return c
    }

    // ================================================================
    // pickPdf
    // ================================================================

    @Test
    fun pickPdf_cancel_returnsNull() {
        val c = coordinator()
        c.pdfPicker = PdfToPngCoordinator.PdfPicker { null }
        val r = FakeMethodResult()
        c.pickPdf(r)
        assertTrue(r.completed)
        assertNull(r.successValue.get())
        assertNull(r.errorCode.get())
    }

    @Test
    fun pickPdf_valid_returnsNativeMetadata() {
        val c = coordinator()
        c.pdfPicker = PdfToPngCoordinator.PdfPicker { pdfUri }
        val r = FakeMethodResult()
        c.pickPdf(r)
        @Suppress("UNCHECKED_CAST")
        val map = r.successValue.get() as Map<String, Any?>
        assertEquals("content://docushift/in.pdf", map["uri"])
        assertEquals("report.pdf", map["name"])
        assertEquals(3, map["pageCount"])
        assertEquals(2048L, map["size"])
    }

    @Test
    fun pickPdf_invalid_propagatesStableErrorCode() {
        val c = coordinator()
        c.validateProvider = {
            PdfInputValidator.ValidationResult(
                false, "big.pdf", "application/pdf", 1L, 21, "TOO_MANY_PAGES", "超页数",
            )
        }
        c.pdfPicker = PdfToPngCoordinator.PdfPicker { pdfUri }
        val r = FakeMethodResult()
        c.pickPdf(r)
        assertEquals("TOO_MANY_PAGES", r.errorCode.get())
    }

    // ================================================================
    // pickOutputDirectory
    // ================================================================

    @Test
    fun pickOutputDirectory_cancel_returnsNull_noFilesCreated() {
        val c = coordinator()
        val converted = AtomicBoolean(false)
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, _ ->
            converted.set(true)
            "x" to 0L
        }
        c.treePicker = PdfToPngCoordinator.TreePicker { null }
        val r = FakeMethodResult()
        c.pickOutputDirectory(r)
        assertTrue(r.completed)
        assertNull(r.successValue.get())
        assertNull(r.errorCode.get())
        // 目录取消不触发任何转换/文件创建
        assertEquals(false, converted.get())
    }

    @Test
    fun pickOutputDirectory_success_returnsUri_andTakesPermission() {
        val c = coordinator()
        val permTaken = AtomicBoolean(false)
        c.permissionTaker = { permTaken.set(true) }
        c.treePicker = PdfToPngCoordinator.TreePicker { treeUri }
        val r = FakeMethodResult()
        c.pickOutputDirectory(r)
        assertEquals("content://docushift/tree/out", r.successValue.get())
        assertTrue(permTaken.get())
    }

    // ================================================================
    // convertPdfToPng
    // ================================================================

    @Test
    fun convert_validationBeforeExecutor_failFastWithoutStartingWork() {
        val c = coordinator()
        val converted = AtomicBoolean(false)
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, _ ->
            converted.set(true)
            "x" to 0L
        }
        c.validateProvider = {
            PdfInputValidator.ValidationResult(
                false, "bad.pdf", "application/pdf", 1L, 0, "PDF_OPEN_FAILED", "损坏",
            )
        }
        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r)
        assertEquals("PDF_OPEN_FAILED", r.errorCode.get())
        assertEquals(false, converted.get())
    }

    // ================================================================
    // 第 6 期：导出范围校验与透传
    // ================================================================

    @Test
    fun convert_invalidRange_startGtEnd_invalidPageRange_noExecutor() {
        val c = coordinator() // total = 3
        val converted = AtomicBoolean(false)
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, _ ->
            converted.set(true)
            "x" to 0L
        }
        val r = FakeMethodResult()
        // 4—2 不合法（起始 > 结束）
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r, 4, 2)
        assertEquals("INVALID_PAGE_RANGE", r.errorCode.get())
        // 不启动后台转换、不创建任何输出
        assertEquals(false, converted.get())
    }

    @Test
    fun convert_invalidRange_endGtTotal_invalidPageRange() {
        val c = coordinator() // total = 3
        val r = FakeMethodResult()
        // 结束页超出总页数
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r, 1, 99)
        assertEquals("INVALID_PAGE_RANGE", r.errorCode.get())
    }

    @Test
    fun convert_withRange_passesExactRange_andReturnsActualPageCount() {
        val c = coordinator() // total = 3
        val captured = mutableListOf<PdfToPngConverter.ConvertParams>()
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { params, _ ->
            captured.add(params)
            "content://tree/out/report_PNG_x" to 123L
        }
        val r = FakeMethodResult()
        // 导出 2—3（共 2 页）
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r, 2, 3)
        assertTrue(r.await(3000))
        // 范围原样透传到转换器参数
        assertEquals(1, captured.size)
        assertEquals(2, captured[0].startPage)
        assertEquals(3, captured[0].endPage)
        assertEquals(3, captured[0].pageCount)
        @Suppress("UNCHECKED_CAST")
        val map = r.successValue.get() as Map<String, Any?>
        // 成功结果中的 pageCount 为本次实际导出页数
        assertEquals(2, map["pageCount"])
        assertEquals(123L, map["size"])
    }

    @Test
    fun convert_missingRange_defaultsToFullPage() {
        val c = coordinator() // total = 3
        val captured = mutableListOf<PdfToPngConverter.ConvertParams>()
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { params, _ ->
            captured.add(params)
            "folder" to 1L
        }
        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r) // 不传范围
        assertTrue(r.await(3000))
        assertEquals(1, captured.size)
        assertEquals(1, captured[0].startPage)
        assertEquals(3, captured[0].endPage)
    }

    @Test
    fun convert_success_returnsDirectoryPageCountSize() {
        val c = coordinator()
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { params, sink ->
            // 断言不可变参数来自验证结果
            assertEquals("report.pdf", params.displayName)
            assertEquals(3, params.pageCount)
            assertEquals(pdfUri, params.pdfUri)
            assertEquals(treeUri, params.outputTreeUri)
            sink.add(Mockito.mock(Uri::class.java))
            "content://tree/out/report_PNG_x" to 999L
        }
        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r)
        assertTrue(r.await(3000))
        @Suppress("UNCHECKED_CAST")
        val map = r.successValue.get() as Map<String, Any?>
        assertEquals("content://tree/out/report_PNG_x", map["directoryUri"])
        assertEquals(3, map["pageCount"])
        assertEquals(999L, map["size"])
    }

    @Test
    fun convert_singleFlight_secondCallBusy() {
        val c = coordinator()
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, _ ->
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            "out" to 1L
        }

        val r1 = FakeMethodResult()
        val r2 = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r1)
        assertTrue("executor 应已进入后台", started.await(3, TimeUnit.SECONDS))

        c.convertPdfToPng("content://in.pdf", "content://tree/out", r2)
        assertEquals("BUSY", r2.errorCode.get())

        proceed.countDown()
        assertTrue(r1.await(3000))
        assertNull(r1.errorCode.get())
        assertNotNull(r1.successValue.get())
        assertEquals("BUSY", r2.errorCode.get())
    }

    @Test
    fun convert_failure_cleansCreatedInReverseOrder_keepsOriginalError() {
        val c = coordinator()
        val u1 = Mockito.mock(Uri::class.java)  // 子文件夹
        val u2 = Mockito.mock(Uri::class.java)  // 001.png
        val u3 = Mockito.mock(Uri::class.java)  // 002.png
        val deleted = mutableListOf<Uri>()
        c.outputDeleter = { uri -> deleted.add(uri); true }
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, sink ->
            sink.add(u1); sink.add(u2); sink.add(u3)
            throw Exception("PAGE_RENDER_FAILED: 第 3 页（report.pdf）渲染失败")
        }
        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r)
        assertTrue(r.await(3000))
        assertEquals("PAGE_RENDER_FAILED", r.errorCode.get())
        assertTrue(r.errorMessage.get()!!.contains("第 3 页"))
        // 按相反顺序清理：002.png → 001.png → 子文件夹
        assertEquals(listOf(u3, u2, u1), deleted)
    }

    @Test
    fun convert_failure_cleanupErrorDoesNotOverrideOriginal() {
        val c = coordinator()
        c.outputDeleter = { throw IllegalStateException("delete boom") }
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, sink ->
            sink.add(Mockito.mock(Uri::class.java))
            throw Exception("OUTPUT_WRITE_FAILED: 第 1 页（report.pdf）写入失败")
        }
        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r)
        assertTrue(r.await(3000))
        // 清理异常被吞掉，原始错误码保留
        assertEquals("OUTPUT_WRITE_FAILED", r.errorCode.get())
    }

    @Test
    fun onDestroy_race_withBackground_exactlyOnce() {
        val c = coordinator()
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        c.convertExecutor = PdfToPngCoordinator.ConvertExecutor { _, _ ->
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            "out" to 1L
        }

        val r = FakeMethodResult()
        c.convertPdfToPng("content://in.pdf", "content://tree/out", r)
        assertTrue("executor 应已进入后台", started.await(3, TimeUnit.SECONDS))

        c.onDestroy()
        proceed.countDown()
        assertTrue(r.await(3000))

        val successFired = r.successValue.get() != null
        val destroyedFired = r.errorCode.get() == "DESTROYED"
        assertTrue("结果应恰好完成一次", successFired xor destroyedFired)
        assertTrue(r.completed)
    }

    @Test
    fun pickPdf_busyWhilePending() {
        val c = coordinator()
        val second = FakeMethodResult()
        c.pdfPicker = PdfToPngCoordinator.PdfPicker {
            // pending 占位期间的重入请求 → BUSY（模拟选择器未返回时的第二次调用）
            c.pickPdf(second)
            null
        }
        val first = FakeMethodResult()
        c.pickPdf(first)
        assertEquals("BUSY", second.errorCode.get())
        // 第一个请求正常按取消结算
        assertTrue(first.completed)
        assertNull(first.errorCode.get())
    }

    @Test
    fun pickOutputDirectory_busyWhilePending() {
        val c = coordinator()
        val second = FakeMethodResult()
        c.treePicker = PdfToPngCoordinator.TreePicker {
            c.pickOutputDirectory(second)
            null
        }
        val first = FakeMethodResult()
        c.pickOutputDirectory(first)
        assertEquals("BUSY", second.errorCode.get())
        assertTrue(first.completed)
        assertNull(first.errorCode.get())
    }

    @Test
    fun settle_onEmptyPending_isNoOp() {
        val c = coordinator()
        // 空 pending 时结算与销毁均安全无操作
        c.pdfPickSettle(pdfUri)
        c.treeSettle(treeUri)
        c.onDestroy()
    }
}
