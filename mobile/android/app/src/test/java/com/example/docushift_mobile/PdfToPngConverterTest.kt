package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PdfToPngConverter 无设备测试（第 4 期）：
 * 通过注入端口（PdfRendererFactory / SubfolderOpener / ChildOutputOpener /
 * PngEncoder / bitmapFactory / pdfOpen / fdClose）从生产入口 [PdfToPngConverter.convert]
 * 证明页序、命名、资源释放与失败清理记录。
 */
class PdfToPngConverterTest {

    private val resolver = Mockito.mock(ContentResolver::class.java)
    private val pdfUri = Mockito.mock(Uri::class.java)
    private val treeUri = Mockito.mock(Uri::class.java)
    private val folderUri = Mockito.mock(Uri::class.java)
    private val fd = Mockito.mock(ParcelFileDescriptor::class.java)

    /** 受控单页：记录 render/close 次数，可注入渲染失败。 */
    private class FakePage(
        override val width: Int = 100,
        override val height: Int = 200,
        private val renderThrows: Boolean = false,
        private val events: MutableList<String>,
        private val index: Int,
    ) : PdfToPngConverter.PagePort {
        var closed = 0
        override fun render(bitmap: Bitmap) {
            if (renderThrows) throw IllegalStateException("render boom")
            events.add("render:$index")
        }
        override fun close() {
            closed++
            events.add("closePage:$index")
        }
    }

    /** 受控 renderer：按需产出 FakePage，记录 close 次数。 */
    private class FakeRenderer(
        override val pageCount: Int,
        private val events: MutableList<String>,
        private val failAtPage: Int = -1,
    ) : PdfToPngConverter.PdfRendererPort {
        var closed = 0
        val pages = mutableListOf<FakePage>()
        override fun openPage(index: Int): PdfToPngConverter.PagePort {
            events.add("openPage:$index")
            val p = FakePage(
                renderThrows = index == failAtPage,
                events = events,
                index = index,
            )
            pages.add(p)
            return p
        }
        override fun close() {
            closed++
            events.add("closeRenderer")
        }
    }

    private class Fixture(pageCount: Int, failAtPage: Int = -1) {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(pageCount, events, failAtPage)
        val fileNames = mutableListOf<String>()
        val streams = mutableListOf<ByteArrayOutputStream>()
        val childUris = mutableListOf<Uri>()
        val recycled = mutableListOf<Bitmap>()
        var fdClosed = 0
    }

    private fun runConvert(
        fx: Fixture,
        pageCountParam: Int = fx.renderer.pageCount,
        startPage: Int = 1,
        endPage: Int = pageCountParam,
        subfolderResult: Uri? = folderUri,
        childOpenFails: Boolean = false,
        writeThrows: Boolean = false,
        createdSink: MutableList<Uri> = mutableListOf(),
    ): Pair<String, Long> {
        Mockito.`when`(folderUri.toString()).thenReturn("content://tree/folder")
        return PdfToPngConverter.convert(
            resolver = resolver,
            params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "report.pdf", pageCountParam, startPage, endPage),
            createdSink = createdSink,
            rendererFactory = { _ -> fx.renderer },
            subfolderOpener = { _, _ -> subfolderResult },
            childOpener = opener@{ _, fileName ->
                if (childOpenFails) return@opener null
                fx.fileNames.add(fileName)
                val childUri = Mockito.mock(Uri::class.java)
                fx.childUris.add(childUri)
                val stream: OutputStream = if (writeThrows) {
                    object : OutputStream() {
                        override fun write(b: Int) = throw IOException("disk full")
                        override fun write(b: ByteArray) = throw IOException("disk full")
                    }
                } else {
                    ByteArrayOutputStream().also { fx.streams.add(it) }
                }
                Pair(childUri, stream)
            },
            encoder = { _ -> ByteArray(10) },
            clock = { 1753760000000L },
            pdfOpen = { fd },
            fdClose = { fx.fdClosed++ },
            bitmapFactory = { _, _ ->
                Mockito.mock(Bitmap::class.java).also { bmp ->
                    Mockito.doAnswer {
                        fx.recycled.add(bmp)
                        null
                    }.`when`(bmp).recycle()
                }
            },
        )
    }

    // ================================================================
    // 顺序与命名
    // ================================================================

    @Test
    fun threePages_outputInOrder_001_002_003() {
        val fx = Fixture(pageCount = 3)
        val sink = mutableListOf<Uri>()
        val (folder, totalBytes) = runConvert(fx, createdSink = sink)

        assertEquals(listOf("001.png", "002.png", "003.png"), fx.fileNames)
        assertEquals("content://tree/folder", folder)
        assertEquals(30L, totalBytes)
        // 逐页：open → render → closePage，严格按页序
        assertEquals(
            listOf(
                "openPage:0", "render:0", "closePage:0",
                "openPage:1", "render:1", "closePage:1",
                "openPage:2", "render:2", "closePage:2",
                "closeRenderer",
            ),
            fx.events,
        )
        // createdSink：子文件夹 + 3 个页文件
        assertEquals(4, sink.size)
        assertEquals(folderUri, sink[0])
        assertEquals(fx.childUris, sink.subList(1, 4))
    }

    @Test
    fun singlePage_compatible() {
        val fx = Fixture(pageCount = 1)
        val (_, totalBytes) = runConvert(fx)
        assertEquals(listOf("001.png"), fx.fileNames)
        assertEquals(10L, totalBytes)
    }

    // ================================================================
    // 范围导出（第 6 期）
    // ================================================================

    @Test
    fun rangeMid_exportsOriginalPageNames_onlySelectedPagesOpened() {
        // 5 页 PDF，导出 3—5（1-based 闭区间）
        val fx = Fixture(pageCount = 5)
        val sink = mutableListOf<Uri>()
        val (folder, totalBytes) = runConvert(fx, pageCountParam = 5, startPage = 3, endPage = 5, createdSink = sink)

        // 文件名沿用原 PDF 页码：003/004/005
        assertEquals(listOf("003.png", "004.png", "005.png"), fx.fileNames)
        assertEquals("content://tree/folder", folder)
        assertEquals(30L, totalBytes)
        // 只打开了第 3/4/5 页（0-based 索引 2/3/4），未打开第 1/2 页
        assertEquals(
            listOf(
                "openPage:2", "render:2", "closePage:2",
                "openPage:3", "render:3", "closePage:3",
                "openPage:4", "render:4", "closePage:4",
                "closeRenderer",
            ),
            fx.events,
        )
        assertFalse(fx.events.contains("openPage:0"))
        assertFalse(fx.events.contains("openPage:1"))
        // createdSink：子文件夹 + 3 个页文件
        assertEquals(4, sink.size)
    }

    @Test
    fun rangeSinglePage_exportsOnlyThatPage() {
        val fx = Fixture(pageCount = 5)
        val (_, totalBytes) = runConvert(fx, pageCountParam = 5, startPage = 3, endPage = 3)
        assertEquals(listOf("003.png"), fx.fileNames)
        assertEquals(10L, totalBytes)
    }

    @Test
    fun invalidRange_startGtEnd_invalidPageRange_noOutput() {
        // 归一化后仍 startPage > endPage → INVALID_PAGE_RANGE，且不创建任何输出
        val fx = Fixture(pageCount = 5)
        val sink = mutableListOf<Uri>()
        try {
            runConvert(fx, pageCountParam = 5, startPage = 4, endPage = 2, createdSink = sink)
            fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
        }
        // 未打开任何页；未创建子文件夹/页文件
        assertFalse(fx.events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    // ================================================================
    // 失败路径与资源释放
    // ================================================================

    @Test
    fun renderFailure_midway_stopsAndReleases() {
        val fx = Fixture(pageCount = 3, failAtPage = 1)
        val sink = mutableListOf<Uri>()
        try {
            runConvert(fx, createdSink = sink)
            fail("应抛出 PAGE_RENDER_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PAGE_RENDER_FAILED"))
            // 页级错误包含序号与显示名
            assertTrue(e.message!!.contains("第 2 页"))
            assertTrue(e.message!!.contains("report.pdf"))
        }
        // 第 3 页未被打开（停止后续页）
        assertFalse(fx.events.contains("openPage:2"))
        // 当前页与 renderer 已释放
        assertTrue(fx.events.contains("closePage:1"))
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
        // 第 2 页 bitmap 已回收（两页各建一张，全部回收）
        assertEquals(2, fx.recycled.size)
        // createdSink 只含子文件夹 + 第 1 页（失败页未加入）
        assertEquals(2, sink.size)
    }

    @Test
    fun subfolderUnavailable_outputDirUnavailable() {
        val fx = Fixture(pageCount = 1)
        try {
            runConvert(fx, subfolderResult = null)
            fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
        }
        // 未打开任何页；renderer/fd 已释放
        assertFalse(fx.events.contains("openPage:0"))
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun childOpenFailure_outputDirUnavailable() {
        val fx = Fixture(pageCount = 1)
        try {
            runConvert(fx, childOpenFails = true)
            fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
        }
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun writeFailure_outputWriteFailed_withPageAndName() {
        val fx = Fixture(pageCount = 2)
        val sink = mutableListOf<Uri>()
        try {
            runConvert(fx, writeThrows = true, createdSink = sink)
            fail("应抛出 OUTPUT_WRITE_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_WRITE_FAILED"))
            assertTrue(e.message!!.contains("第 1 页"))
            assertTrue(e.message!!.contains("report.pdf"))
        }
        // 停止后续页；写失败页 URI 在写入前已登记（子文件夹 + 失败页）
        assertFalse(fx.events.contains("openPage:1"))
        assertEquals(2, sink.size)
        assertEquals(folderUri, sink[0])
        // 失败页 URI 在清理列表中，Coordinator 将按相反顺序删除
        assertEquals(fx.childUris[0], sink[1])
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun fdNull_pdfOpenFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            PdfToPngConverter.convert(
                resolver = resolver,
                params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> null },
                encoder = { _ -> ByteArray(1) },
                clock = { 0L },
                pdfOpen = { null },
            )
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
        }
    }

    @Test
    fun pageCountMismatch_more_rejected_pdfOpenFailed() {
        // renderer 实际页数超过验证时页数 → 拒绝
        val fx = Fixture(pageCount = 5)
        try {
            runConvert(fx, pageCountParam = 3)
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            assertTrue(e.message!!.contains("页数与验证不一致"))
        }
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun pageCountMismatch_fewer_rejected_pdfOpenFailed() {
        // renderer 实际页数比验证时少 → 同样拒绝，防止输出数与宣称不一致
        val fx = Fixture(pageCount = 2)
        try {
            runConvert(fx, pageCountParam = 5)
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            assertTrue(e.message!!.contains("页数与验证不一致"))
        }
        // 不创建输出子文件夹（页数校验在创建子文件夹之前）
        assertEquals(0, fx.fileNames.size)
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    // ================================================================
    // 命名与尺寸纯函数
    // ================================================================

    @Test
    fun folderName_baseNamePlusTimestamp() {
        val name = PdfToPngConverter.buildFolderName("月度报告.pdf", 0L)
        assertTrue(name.startsWith("月度报告_PNG_"))
        // 时间戳段为 yyyyMMdd_HHmmss（15 字符）
        assertEquals("月度报告_PNG_".length + 15, name.length)
    }

    @Test
    fun pageFileName_threeDigitPadded() {
        assertEquals("001.png", PdfToPngConverter.pageFileName(0))
        assertEquals("010.png", PdfToPngConverter.pageFileName(9))
        assertEquals("020.png", PdfToPngConverter.pageFileName(19))
    }

    @Test
    fun bitmapSize_doublesAt144dpi() {
        // A4: 595 x 842 pt → 144dpi 下 2 倍
        val (w, h) = PdfToPngConverter.computeBitmapSize(595, 842, 144)
        assertEquals(1190, w)
        assertEquals(1684, h)
    }

    @Test
    fun bitmapSize_capsLongestEdgeAt4096() {
        // 3000 pt → 2x = 6000 > 4096 → 等比缩到最长边 4096
        val (w, h) = PdfToPngConverter.computeBitmapSize(3000, 1500, 144)
        assertEquals(4096, w)
        assertTrue(h in 2040..2056)
        assertTrue(w.toLong() * h.toLong() <= 16_000_000L)
    }

    @Test
    fun bitmapSize_capsTotalPixels() {
        // 正方形大页：4096x4096 = 1677 万 > 1600 万 → 继续下调
        val (w, h) = PdfToPngConverter.computeBitmapSize(2500, 2500, 144)
        assertTrue(w.toLong() * h.toLong() <= 16_000_000L)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }

    // ================================================================
    // R2：childOpener 创建文档后打不开输出流 → 内部删除
    // ================================================================

    @Test
    fun childOpener_createsThenStreamFails_deletesFile_returnsNull() {
        // 模拟 RealChildOutputOpener 打不开流时内部删除：创建 URI→流为 null→删除 URI→返回 null
        val createdRef = mutableListOf<Uri>()
        val deletedRef = mutableListOf<Uri>()
        val opener = PdfToPngConverter.ChildOutputOpener { _, _ ->
            val u = Mockito.mock(Uri::class.java)
            createdRef.add(u)
            // 流失败 → 内部删除后返回 null
            deletedRef.add(u)
            null
        }

        val fx = Fixture(pageCount = 1)
        try {
            PdfToPngConverter.convert(
                resolver = resolver,
                params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = opener,
                encoder = { _ -> ByteArray(1) },
                clock = { 0L },
                pdfOpen = { fd },
                bitmapFactory = { _, _ ->
                    Mockito.mock(Bitmap::class.java).also {
                        Mockito.doAnswer {}.`when`(it).recycle()
                    }
                },
            )
            fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
        }
        // 已创建→已删除，不应残留到 createdSink
        assertEquals(1, createdRef.size)
        assertEquals(1, deletedRef.size)
        assertEquals(createdRef[0], deletedRef[0])
    }

    // ================================================================
    // R3：renderer 创建失败 → 关闭 fd + 映射 PDF_OPEN_FAILED
    // ================================================================

    @Test
    fun rendererOpenFailure_closesFd_andMapsPdfOpenFailed() {
        val closed = AtomicBoolean(false)
        val fx = Fixture(pageCount = 1)
        try {
            PdfToPngConverter.convert(
                resolver = resolver,
                params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> throw RuntimeException("renderer init boom") },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> null },
                encoder = { _ -> ByteArray(1) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { closed.set(true) },
                bitmapFactory = { _, _ ->
                    Mockito.mock(Bitmap::class.java).also {
                        Mockito.doAnswer {}.`when`(it).recycle()
                    }
                },
            )
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            assertTrue(e.message!!.contains("无法打开 PDF 渲染器"))
        }
        // fd 被关闭（不泄漏）
        assertTrue(closed.get())
    }

    // ================================================================
    // R5：输出流 close() 异常 → 映射为 OUTPUT_WRITE_FAILED
    // ================================================================

    @Test
    fun streamCloseFailure_mappedToOutputWriteFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            PdfToPngConverter.convert(
                resolver = resolver,
                params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, name ->
                    val childUri = Mockito.mock(Uri::class.java)
                    // 输出流在 close() 时抛异常
                    val stream = object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray) { /* 正常写入 */ }
                        override fun close() { throw IOException("close failed") }
                    }
                    Pair(childUri, stream)
                },
                encoder = { _ -> ByteArray(10) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ ->
                    Mockito.mock(Bitmap::class.java).also {
                        Mockito.doAnswer {}.`when`(it).recycle()
                    }
                },
            )
            fail("应抛出 OUTPUT_WRITE_FAILED（close 异常）")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_WRITE_FAILED"))
            assertTrue(e.message!!.contains("流关闭异常"))
            assertTrue(e.message!!.contains("第 1 页"))
        }
        // renderer 和 fd 仍被释放（外层 finally）
        assertEquals(1, fx.renderer.closed)
    }
}
