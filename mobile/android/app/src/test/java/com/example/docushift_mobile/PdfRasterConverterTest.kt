package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PdfRasterConverter 共用核心直接测试（第 5 期）：
 * 从共用转换器生产入口 [PdfRasterConverter.convert] 验证资源唯一所有权、
 * 严格页数校验、createdSink 登记与失败清理逻辑未被 PNG/JPG 外观削弱。
 */
class PdfRasterConverterTest {

    private val resolver = Mockito.mock(ContentResolver::class.java)
    private val pdfUri = Mockito.mock(Uri::class.java)
    private val treeUri = Mockito.mock(Uri::class.java)
    private val folderUri = Mockito.mock(Uri::class.java)
    private val fd = Mockito.mock(ParcelFileDescriptor::class.java)

    /** 受控单页。 */
    private class FakePage(
        override val width: Int = 100,
        override val height: Int = 200,
        private val events: MutableList<String>,
        private val index: Int,
        private val renderThrows: Boolean = false,
    ) : PdfRasterConverter.PagePort {
        var closed = 0
        override fun render(bitmap: Bitmap) {
            if (renderThrows) throw IllegalStateException("boom")
            events.add("render:$index")
        }
        override fun close() {
            closed++
            events.add("closePage:$index")
        }
    }

    private class FakeRenderer(
        override val pageCount: Int,
        private val events: MutableList<String>,
        private val failAtPage: Int = -1,
    ) : PdfRasterConverter.PdfRendererPort {
        var closed = 0
        val pages = mutableListOf<FakePage>()
        override fun openPage(index: Int): PdfRasterConverter.PagePort {
            events.add("openPage:$index")
            val p = FakePage(events = events, index = index, renderThrows = index == failAtPage)
            pages.add(p)
            return p
        }
        override fun close() {
            closed++
            events.add("closeRenderer")
        }
    }

    /** 一个最小 JPG 风格策略（白底 + 写 7 字节）。 */
    private fun testStrategy(needsWhite: Boolean = true) = object : PdfRasterConverter.RasterOutputStrategy {
        override val extension: String get() = "jpg"
        override val mimeType: String get() = "image/jpeg"
        override val folderSuffix: String get() = "_JPG_"
        override val needsWhiteBackground: Boolean get() = needsWhite
        override fun encode(bitmap: Bitmap, out: OutputStream, pageIndexOneBased: Int, displayName: String) {
            out.write(ByteArray(7))
            out.flush()
        }
    }

    private fun mockBitmap(): Bitmap {
        val b = Mockito.mock(Bitmap::class.java)
        Mockito.doAnswer { null }.`when`(b).recycle()
        Mockito.doAnswer { null }.`when`(b).eraseColor(Mockito.anyInt())
        return b
    }

    @Test
    fun success_closesFdRendererAndPagesExactlyOnce() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(3, events)
        val fdClosed = AtomicBoolean(false)
        val sink = mutableListOf<Uri>()
        PdfRasterConverter.convert(
            resolver = resolver,
            params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3),
            strategy = testStrategy(),
            createdSink = sink,
            rendererFactory = { _ -> renderer },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
            clock = { 0L },
            pdfOpen = { fd },
            fdClose = { fdClosed.set(true) },
            bitmapFactory = { _, _ -> mockBitmap() },
        )
        // 全部资源恰好释放一次
        assertEquals(1, renderer.closed)
        assertTrue(fdClosed.get())
        assertEquals(3, renderer.pages.size)
        assertTrue("每页应被关闭", renderer.pages.all { it.closed == 1 })
        // createdSink：子文件夹 + 3 页
        assertEquals(4, sink.size)
        assertEquals(folderUri, sink[0])
    }

    @Test
    fun renderFailure_stopsAndReleasesAllResources() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(3, events, failAtPage = 1)
        val fdClosed = AtomicBoolean(false)
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3),
                strategy = testStrategy(),
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { fdClosed.set(true) },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出")
        } catch (_: Exception) {
            // 第 3 页未被打开
            assertFalse(events.contains("openPage:2"))
            assertEquals(1, renderer.closed)
            assertTrue(fdClosed.get())
        }
    }

    @Test
    fun pageCountMismatch_rejectedBeforeCreatingOutput() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3),
                strategy = testStrategy(),
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            // 页数校验在创建子文件夹之前
            assertFalse(events.contains("openPage:0"))
            assertEquals(1, renderer.closed)
        }
    }

    @Test
    fun subfolderUnavailable_noPageOpened() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(2, events)
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 2),
                strategy = testStrategy(),
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> null },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
            assertFalse(events.contains("openPage:0"))
        }
    }

    // ================================================================
    // 第 6 期：范围导出（在共用核心直接验证，独立于 PNG/JPG 策略）
    // ================================================================

    @Test
    fun rangeMid_exportsOriginalPageNames_onlySelectedPagesOpened() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        val names = mutableListOf<String>()
        val fdClosed = AtomicBoolean(false)
        val sink = mutableListOf<Uri>()
        val (folder, totalBytes) = PdfRasterConverter.convert(
            resolver = resolver,
            params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 5, 3, 5),
            strategy = testStrategy(),
            createdSink = sink,
            rendererFactory = { _ -> renderer },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = { _, name -> names.add(name); Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
            clock = { 0L },
            pdfOpen = { fd },
            fdClose = { fdClosed.set(true) },
            bitmapFactory = { _, _ -> mockBitmap() },
        )
        // 文件名沿用原 PDF 页码：003/004/005（pageFileName 收 0-based 索引）
        assertEquals(listOf("003.jpg", "004.jpg", "005.jpg"), names)
        assertEquals(3 * 7L, totalBytes)
        // 只打开第 3/4/5 页（0-based 索引 2/3/4），未打开第 1/2 页
        assertFalse(events.contains("openPage:0"))
        assertFalse(events.contains("openPage:1"))
        assertTrue(events.contains("openPage:2"))
        assertEquals(4, sink.size)
        assertEquals(folderUri, sink[0])
    }

    @Test
    fun invalidRange_startGtEnd_noOutput_noExecutor() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        val sink = mutableListOf<Uri>()
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 5, 4, 2),
                strategy = testStrategy(),
                createdSink = sink,
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
        }
        // 归一化后仍 startPage > endPage：不创建任何 URI、不打开任何页
        assertFalse(events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, renderer.closed)
    }

    // ================================================================
    // 第 6 期返工：startPage / endPage 越界严格拒绝（不夹紧）
    // ================================================================

    @Test
    fun invalidRange_startPageZero_invalidPageRange_noOutput() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        val sink = mutableListOf<Uri>()
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 5, 0, 5),
                strategy = testStrategy(),
                createdSink = sink,
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
            // 报错信息包含原始非法值
            assertTrue(e.message!!.contains("起始 0"))
        }
        // 不创建任何 URI、不打开任何页
        assertFalse(events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, renderer.closed)
    }

    @Test
    fun invalidRange_startPageNegative_invalidPageRange_noOutput() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        val sink = mutableListOf<Uri>()
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 5, -3, 5),
                strategy = testStrategy(),
                createdSink = sink,
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
            assertTrue(e.message!!.contains("起始 -3"))
        }
        assertFalse(events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, renderer.closed)
    }

    @Test
    fun invalidRange_endPageBeyondTotal_invalidPageRange_noOutput() {
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(5, events)
        val sink = mutableListOf<Uri>()
        try {
            PdfRasterConverter.convert(
                resolver = resolver,
                params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 5, 1, 6),
                strategy = testStrategy(),
                createdSink = sink,
                rendererFactory = { _ -> renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            org.junit.Assert.fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
            assertTrue(e.message!!.contains("结束 6"))
            assertTrue(e.message!!.contains("PDF 共 5 页"))
        }
        assertFalse(events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, renderer.closed)
    }

    // ================================================================
    // 第 8 期 R1：三档清晰度尺寸 + 非法值拒绝 + 不创建输出
    // ================================================================

    @Test
    fun computeBitmapSize_96dpi_a4Portrait() {
        // A4 595x842 pt → 96 dpi：96/72=1.333 → (793, 1122)
        val (w, h) = PdfRasterConverter.computeBitmapSize(595, 842, 96)
        assertEquals(793, w)
        assertEquals(1122, h)
    }

    @Test
    fun computeBitmapSize_144dpi_a4Portrait() {
        val (w, h) = PdfRasterConverter.computeBitmapSize(595, 842, 144)
        assertEquals(1190, w)
        assertEquals(1684, h)
    }

    @Test
    fun computeBitmapSize_216dpi_a4Portrait() {
        // A4 595x842 pt → 216 dpi：216/72=3.0 → (1785, 2526)
        val (w, h) = PdfRasterConverter.computeBitmapSize(595, 842, 216)
        assertEquals(1785, w)
        assertEquals(2526, h)
    }

    @Test
    fun computeBitmapSize_96dpi_landscape() {
        // 横页 842x595 pt → 96 dpi：(1122, 793)
        val (w, h) = PdfRasterConverter.computeBitmapSize(842, 595, 96)
        assertEquals(1122, w)
        assertEquals(793, h)
    }

    @Test
    fun computeBitmapSize_216dpi_landscape() {
        val (w, h) = PdfRasterConverter.computeBitmapSize(842, 595, 216)
        assertEquals(2526, w)
        assertEquals(1785, h)
    }

    @Test
    fun computeBitmapSize_216dpi_capsLongestEdgeAt4096() {
        // 3000x1500 pt → 216 dpi：6000x3000 > 4096 → capped
        val (w, h) = PdfRasterConverter.computeBitmapSize(3000, 1500, 216)
        assertEquals(4096, w)
        assertTrue(w.toLong() * h.toLong() <= 16_000_000L)
    }

    @Test
    fun computeBitmapSize_96dpi_capsTotalPixels() {
        val (w, h) = PdfRasterConverter.computeBitmapSize(2500, 2500, 96)
        assertTrue(w.toLong() * h.toLong() <= 16_000_000L)
        assertTrue(w >= 1)
        assertTrue(h >= 1)
    }

    @Test
    fun rasterResolution_isValid_rejectsNonstandardValues() {
        assertFalse(RasterResolution.isValid(95))
        assertFalse(RasterResolution.isValid(145))
        assertFalse(RasterResolution.isValid(217))
        assertFalse(RasterResolution.isValid(0))
        assertFalse(RasterResolution.isValid(-1))
        assertTrue(RasterResolution.isValid(96))
        assertTrue(RasterResolution.isValid(144))
        assertTrue(RasterResolution.isValid(216))
    }

    @Test
    fun rasterResolution_fromDpi_returnsCorrectEnum() {
        assertEquals(RasterResolution.LOW, RasterResolution.fromDpi(96))
        assertEquals(RasterResolution.STANDARD, RasterResolution.fromDpi(144))
        assertEquals(RasterResolution.HIGH, RasterResolution.fromDpi(216))
        assertEquals(null, RasterResolution.fromDpi(95))
        assertEquals(null, RasterResolution.fromDpi(0))
    }

    @Test
    fun rasterResolution_defaultIsStandard144() {
        assertEquals(144, RasterResolution.DEFAULT.dpi)
        assertEquals(RasterResolution.STANDARD, RasterResolution.DEFAULT)
    }

    @Test
    fun convert_defaultResolution_uses144dpi_regression() {
        val bmSizes = mutableListOf<Pair<Int, Int>>()
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(3, events)
        val fdClosed = AtomicBoolean(false)
        val sink = mutableListOf<Uri>()
        PdfRasterConverter.convert(
            resolver = resolver,
            params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3),
            strategy = testStrategy(),
            createdSink = sink,
            rendererFactory = { _ -> renderer },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
            clock = { 0L },
            pdfOpen = { fd },
            fdClose = { fdClosed.set(true) },
            bitmapFactory = { w, h -> bmSizes.add(Pair(w, h)); mockBitmap() },
        )
        // 默认 resolution 未显式指定 → 应为 144 dpi
        // FakePage 的 size 是 100x200 pt → 144dpi: (200, 400)
        assertEquals(3, bmSizes.size)
        bmSizes.forEach { (w, h) ->
            assertEquals(200, w)
            assertEquals(400, h)
        }
    }

    @Test
    fun convert_96dpi_passes96ToComputeBitmapSize() {
        val bmSizes = mutableListOf<Pair<Int, Int>>()
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(3, events)
        val fdClosed = AtomicBoolean(false)
        val sink = mutableListOf<Uri>()
        PdfRasterConverter.convert(
            resolver = resolver,
            params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3,
                resolution = RasterResolution.LOW),
            strategy = testStrategy(),
            createdSink = sink,
            rendererFactory = { _ -> renderer },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
            clock = { 0L },
            pdfOpen = { fd },
            fdClose = { fdClosed.set(true) },
            bitmapFactory = { w, h -> bmSizes.add(Pair(w, h)); mockBitmap() },
        )
        // FakePage 100x200 pt → 96dpi: (133, 266)   (100*96/72=133, 200*96/72=266)
        assertEquals(3, bmSizes.size)
        bmSizes.forEach { (w, h) ->
            assertEquals(133, w)
            assertEquals(266, h)
        }
    }

    @Test
    fun convert_216dpi_passes216ToComputeBitmapSize() {
        val bmSizes = mutableListOf<Pair<Int, Int>>()
        val events = mutableListOf<String>()
        val renderer = FakeRenderer(3, events)
        val fdClosed = AtomicBoolean(false)
        val sink = mutableListOf<Uri>()
        PdfRasterConverter.convert(
            resolver = resolver,
            params = PdfRasterConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 3,
                resolution = RasterResolution.HIGH),
            strategy = testStrategy(),
            createdSink = sink,
            rendererFactory = { _ -> renderer },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = { _, _ -> Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) },
            clock = { 0L },
            pdfOpen = { fd },
            fdClose = { fdClosed.set(true) },
            bitmapFactory = { w, h -> bmSizes.add(Pair(w, h)); mockBitmap() },
        )
        // FakePage 100x200 pt → 216dpi: (300, 600)   (100*3=300, 200*3=600)
        assertEquals(3, bmSizes.size)
        bmSizes.forEach { (w, h) ->
            assertEquals(300, w)
            assertEquals(600, h)
        }
    }
}
