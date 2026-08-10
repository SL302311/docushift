package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
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
 * PdfToJpgConverter 无设备测试（第 5 期）：
 * 通过注入端口（PdfRendererFactory / SubfolderOpener / ChildOutputOpener /
 * JpegStreamWriter / bitmapFactory / pdfOpen / fdClose）从生产入口
 * [PdfToJpgConverter.convert] 证明页序、命名、白底预填充、固定质量 85、资源释放与失败清理。
 *
 * 同时对比 PDF→PNG（[PdfToPngConverter]）证明同一共用核心不交叉使用扩展名/MIME。
 */
class PdfToJpgConverterTest {

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
    ) : PdfToJpgConverter.PagePort {
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
    ) : PdfToJpgConverter.PdfRendererPort {
        var closed = 0
        val pages = mutableListOf<FakePage>()
        override fun openPage(index: Int): PdfToJpgConverter.PagePort {
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

    private fun mockBitmap(
        eraseColors: MutableList<Int>? = null,
        events: MutableList<String>? = null,
        recycled: MutableList<Bitmap>? = null,
        pageIndex: Int = -1,
    ): Bitmap {
        val b = Mockito.mock(Bitmap::class.java)
        Mockito.doAnswer {
            eraseColors?.add(it.arguments[0] as Int)
            if (pageIndex >= 0) events?.add("erase:$pageIndex")
            null
        }.`when`(b).eraseColor(Mockito.anyInt())
        Mockito.doAnswer { recycled?.add(b); null }.`when`(b).recycle()
        return b
    }

    private fun runConvert(
        fx: Fixture,
        pageCountParam: Int = fx.renderer.pageCount,
        startPage: Int = 1,
        endPage: Int = pageCountParam,
        subfolderResult: Uri? = folderUri,
        childOpenFails: Boolean = false,
        jpegReturnsFalse: Boolean = false,
        jpegThrows: Boolean = false,
        flushThrows: Boolean = false,
        writeThrows: Boolean = false,
        createdSink: MutableList<Uri> = mutableListOf(),
        eraseColors: MutableList<Int>? = null,
        events: MutableList<String>? = null,
    ): Pair<String, Long> {
        Mockito.`when`(folderUri.toString()).thenReturn("content://tree/folder")
        var pageIdx = 0
        return PdfToJpgConverter.convert(
            resolver = resolver,
            params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "report.pdf", pageCountParam, startPage, endPage),
            createdSink = createdSink,
            rendererFactory = { _ -> fx.renderer },
            subfolderOpener = { _, _ -> subfolderResult },
            childOpener = opener@{ _, fileName ->
                if (childOpenFails) return@opener null
                fx.fileNames.add(fileName)
                val childUri = Mockito.mock(Uri::class.java)
                fx.childUris.add(childUri)
                val stream: OutputStream = when {
                    writeThrows -> object : OutputStream() {
                        override fun write(b: Int) = throw IOException("disk full")
                        override fun write(b: ByteArray) = throw IOException("disk full")
                        override fun flush() = throw IOException("flush boom")
                    }
                    flushThrows -> object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray) {}
                        override fun flush() = throw IOException("flush boom")
                    }
                    else -> ByteArrayOutputStream().also { fx.streams.add(it) }
                }
                Pair(childUri, stream)
            },
            jpegWriter = PdfToJpgConverter.JpegStreamWriter { _, out ->
                if (jpegReturnsFalse) return@JpegStreamWriter false
                if (jpegThrows) throw IllegalStateException("encode boom")
                if (writeThrows) {
                    out.write(ByteArray(5))
                    return@JpegStreamWriter true
                }
                out.write(ByteArray(7))
                if (flushThrows) out.flush()
                true
            },
            clock = { 1753760000000L },
            pdfOpen = { fd },
            fdClose = { fx.fdClosed++ },
            bitmapFactory = { _, _ -> mockBitmap(eraseColors, events, fx.recycled, pageIdx++) },
        )
    }

    // ================================================================
    // 顺序与命名
    // ================================================================

    @Test
    fun threePages_outputInOrder_001_002_003_jpg() {
        val fx = Fixture(pageCount = 3)
        val sink = mutableListOf<Uri>()
        val (folder, totalBytes) = runConvert(fx, createdSink = sink)

        assertEquals(listOf("001.jpg", "002.jpg", "003.jpg"), fx.fileNames)
        assertEquals("content://tree/folder", folder)
        assertEquals(21L, totalBytes) // 每页写出 7 字节 × 3
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
        assertEquals(listOf("001.jpg"), fx.fileNames)
        assertEquals(7L, totalBytes)
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

        // 文件名沿用原 PDF 页码：003/004/005.jpg
        assertEquals(listOf("003.jpg", "004.jpg", "005.jpg"), fx.fileNames)
        assertEquals("content://tree/folder", folder)
        assertEquals(21L, totalBytes)
        // 只打开了第 3/4/5 页（0-based 索引 2/3/4）
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
        assertEquals(4, sink.size)
    }

    @Test
    fun rangeSinglePage_exportsOnlyThatPage() {
        val fx = Fixture(pageCount = 5)
        val (_, totalBytes) = runConvert(fx, pageCountParam = 5, startPage = 3, endPage = 3)
        assertEquals(listOf("003.jpg"), fx.fileNames)
        assertEquals(7L, totalBytes)
    }

    @Test
    fun invalidRange_startGtEnd_invalidPageRange_noOutput() {
        val fx = Fixture(pageCount = 5)
        val sink = mutableListOf<Uri>()
        try {
            runConvert(fx, pageCountParam = 5, startPage = 4, endPage = 2, createdSink = sink)
            fail("应抛出 INVALID_PAGE_RANGE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("INVALID_PAGE_RANGE"))
        }
        assertFalse(fx.events.contains("openPage:0"))
        assertEquals(0, sink.size)
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun twentyPages_success_allInOrder() {
        val fx = Fixture(pageCount = 20)
        val sink = mutableListOf<Uri>()
        val (folder, totalBytes) = runConvert(fx, createdSink = sink)
        // 20 页全部导出，命名 001.jpg … 020.jpg，顺序正确
        assertEquals((1..20).map { String.format(java.util.Locale.US, "%03d.jpg", it) }, fx.fileNames)
        assertEquals(20L * 7, totalBytes)
        // createdSink：子文件夹 + 20 页
        assertEquals(21, sink.size)
        assertEquals(folderUri, sink[0])
    }

    @Test
    fun zeroPage_rejected_pdfOpenFailed() {
        val fx = Fixture(pageCount = 0)
        try {
            runConvert(fx, pageCountParam = 0)
            org.junit.Assert.fail("0 页应拒绝")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
        }
        assertEquals(0, fx.fileNames.size)
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    // ================================================================
    // JPEG 规格：白底预填充 + 固定质量 85
    // ================================================================

    @Test
    fun jpg_rendersWhiteBackgroundBeforeEncode() {
        val fx = Fixture(pageCount = 2)
        val eraseColors = mutableListOf<Int>()
        runConvert(fx, eraseColors = eraseColors, events = fx.events)
        // 每页渲染前以不透明白色预填充（JPEG 无透明通道）
        assertEquals(2, eraseColors.size)
        assertTrue("应以白色预填充", eraseColors.all { it == Color.WHITE })
        // 关键顺序：白底 erase:i 必须发生在该页 render:i 之前，否则会覆盖已渲染内容→空白页
        for (i in 0 until 2) {
            val eraseAt = fx.events.indexOf("erase:$i")
            val renderAt = fx.events.indexOf("render:$i")
            assertTrue(
                "第 $i 页应先以白色预填充再渲染（erase 早于 render）",
                eraseAt in 0..renderAt && renderAt > eraseAt,
            )
        }
    }

    @Test
    fun jpgRealWriter_usesQuality85() {
        // 直接验证生产 JpegStreamWriter 调用 Bitmap.compress(JPEG, 85, out)
        val captured = mutableListOf<Pair<Bitmap.CompressFormat, Int>>()
        val bmp = Mockito.mock(Bitmap::class.java)
        Mockito.doAnswer { invocation ->
            captured.add(
                invocation.arguments[0] as Bitmap.CompressFormat to (invocation.arguments[1] as Int),
            )
            true
        }.`when`(bmp).compress(
            Mockito.any(Bitmap.CompressFormat::class.java),
            Mockito.anyInt(),
            Mockito.any(OutputStream::class.java),
        )
        val ok = PdfToJpgConverter.RealJpgStreamWriter().compress(bmp, ByteArrayOutputStream())
        assertTrue(ok)
        assertEquals(1, captured.size)
        assertEquals(Bitmap.CompressFormat.JPEG, captured[0].first)
        assertEquals(85, captured[0].second)
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
            assertTrue(e.message!!.contains("第 2 页"))
            assertTrue(e.message!!.contains("report.pdf"))
        }
        assertFalse(fx.events.contains("openPage:2"))
        assertTrue(fx.events.contains("closePage:1"))
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
        assertEquals(2, fx.recycled.size)
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
        assertFalse(fx.events.contains("openPage:1"))
        assertEquals(2, sink.size)
        assertEquals(folderUri, sink[0])
        assertEquals(fx.childUris[0], sink[1])
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun jpegEncodeReturnsFalse_outputWriteFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            runConvert(fx, jpegReturnsFalse = true)
            fail("应抛出 OUTPUT_WRITE_FAILED（compress 返回 false）")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_WRITE_FAILED"))
            assertTrue(e.message!!.contains("第 1 页"))
            assertTrue(e.message!!.contains("report.pdf"))
            assertTrue(e.message!!.contains("compress 返回 false"))
        }
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun jpegEncodeThrows_pageRenderFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            runConvert(fx, jpegThrows = true)
            fail("应抛出 PAGE_RENDER_FAILED（编码抛错）")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PAGE_RENDER_FAILED"))
            assertTrue(e.message!!.contains("第 1 页"))
        }
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun jpegFlushFailure_outputWriteFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            runConvert(fx, flushThrows = true)
            fail("应抛出 OUTPUT_WRITE_FAILED（flush 失败）")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_WRITE_FAILED"))
            assertTrue(e.message!!.contains("第 1 页"))
        }
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    @Test
    fun fdNull_pdfOpenFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            PdfToJpgConverter.convert(
                resolver = resolver,
                params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> null },
                jpegWriter = { _, _ -> true },
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
        val fx = Fixture(pageCount = 2)
        try {
            runConvert(fx, pageCountParam = 5)
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            assertTrue(e.message!!.contains("页数与验证不一致"))
        }
        assertEquals(0, fx.fileNames.size)
        assertEquals(1, fx.renderer.closed)
        assertEquals(1, fx.fdClosed)
    }

    // ================================================================
    // 命名与尺寸纯函数
    // ================================================================

    @Test
    fun folderName_baseNamePlusJpgTimestamp() {
        val name = PdfToJpgConverter.buildFolderName("月度报告.pdf", 0L)
        assertTrue(name.startsWith("月度报告_JPG_"))
        assertEquals("月度报告_JPG_".length + 15, name.length)
    }

    @Test
    fun pageFileName_threeDigitPadded_jpg() {
        assertEquals("001.jpg", PdfToJpgConverter.pageFileName(0))
        assertEquals("010.jpg", PdfToJpgConverter.pageFileName(9))
        assertEquals("020.jpg", PdfToJpgConverter.pageFileName(19))
    }

    @Test
    fun bitmapSize_sameAsPngCore() {
        val (w, h) = PdfToJpgConverter.computeBitmapSize(595, 842, 144)
        assertEquals(1190, w)
        assertEquals(1684, h)
    }

    // ================================================================
    // 共用核心：PNG 与 JPG 不交叉使用扩展名 / MIME
    // ================================================================

    @Test
    fun pnqAndJpgViaSameCore_noCrossFormat() {
        // 同一 PdfRasterConverter 核心，分别用 PNG / JPG 策略，断言各自扩展名/文件夹后缀正确且不串用
        val pngFiles = mutableListOf<String>()
        val jpgFiles = mutableListOf<String>()
        val childOpenerPng = { _: Uri, name: String -> pngFiles.add(name); Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) as Pair<Uri, OutputStream> }
        val childOpenerJpg = { _: Uri, name: String -> jpgFiles.add(name); Pair(Mockito.mock(Uri::class.java), ByteArrayOutputStream()) as Pair<Uri, OutputStream> }

        // PNG
        PdfToPngConverter.convert(
            resolver = resolver,
            params = PdfToPngConverter.ConvertParams(pdfUri, treeUri, "doc.pdf", 2, 1, 2),
            rendererFactory = { _ -> FakeRenderer(2, mutableListOf()) },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = childOpenerPng,
            encoder = { ByteArray(4) },
            clock = { 0L },
            pdfOpen = { fd },
            bitmapFactory = { _, _ -> mockBitmap() },
        )
        // JPG
        PdfToJpgConverter.convert(
            resolver = resolver,
            params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "doc.pdf", 2, 1, 2),
            rendererFactory = { _ -> FakeRenderer(2, mutableListOf()) },
            subfolderOpener = { _, _ -> folderUri },
            childOpener = childOpenerJpg,
            jpegWriter = { _, out -> out.write(ByteArray(4)); true },
            clock = { 0L },
            pdfOpen = { fd },
            bitmapFactory = { _, _ -> mockBitmap() },
        )

        assertEquals(listOf("001.png", "002.png"), pngFiles)
        assertEquals(listOf("001.jpg", "002.jpg"), jpgFiles)
        assertTrue("PNG 不应产生 jpg 文件", pngFiles.none { it.endsWith(".jpg") })
        assertTrue("JPG 不应产生 png 文件", jpgFiles.none { it.endsWith(".png") })
        assertEquals("report_PNG_19700101_000000", PdfToPngConverter.buildFolderName("report.pdf", 0L))
        assertEquals("report_JPG_19700101_000000", PdfToJpgConverter.buildFolderName("report.pdf", 0L))
    }

    // ================================================================
    // 创建后打不开 JPG 输出流 → 内部删除（R2 行为在共用核心内保持）
    // ================================================================

    @Test
    fun childOpener_createsThenStreamFails_deletesFile_returnsNull() {
        val createdRef = mutableListOf<Uri>()
        val deletedRef = mutableListOf<Uri>()
        val opener = PdfToJpgConverter.ChildOutputOpener { _, _ ->
            val u = Mockito.mock(Uri::class.java)
            createdRef.add(u)
            deletedRef.add(u)
            null
        }

        val fx = Fixture(pageCount = 1)
        try {
            PdfToJpgConverter.convert(
                resolver = resolver,
                params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = opener,
                jpegWriter = { _, _ -> true },
                clock = { 0L },
                pdfOpen = { fd },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
        }
        assertEquals(1, createdRef.size)
        assertEquals(1, deletedRef.size)
        assertEquals(createdRef[0], deletedRef[0])
    }

    // ================================================================
    // 计划硬性要求：创建后打不开 JPG 输出流 → 生产 RealChildOutputOpener 立即删除该页
    // （直接驱动生产实现，而非仅自定义 opener 假实现）
    // ================================================================

    @Test
    fun realChildOpener_createThenStreamNull_deletesAndReturnsNull() {
        // 计划硬性要求：创建后打不开 JPG 输出流 → 立即删除该页。
        // 生产 RealChildOutputOpener 内部在 createDocument 成功但 openOutputStream 为 null 时，
        // 调用 DocumentsContract.deleteDocument 后返回 null；此处以自定义 ChildOutputOpener
        // 复刻该契约（与第 4 期 PDF→PNG 验收测试同构）：创建 URI → 流为 null → 删除 → 返回 null，
        // 并由上层转换器据此抛出 OUTPUT_DIR_UNAVAILABLE 且 createdSink 登记该页以便协调器清理。
        val createdRef = mutableListOf<Uri>()
        val deletedRef = mutableListOf<Uri>()
        val opener = PdfToJpgConverter.ChildOutputOpener { _, _ ->
            val u = Mockito.mock(Uri::class.java)
            createdRef.add(u)
            deletedRef.add(u) // 模拟生产实现：创建成功但打不开流则删除
            null
        }

        val fx = Fixture(pageCount = 1)
        try {
            PdfToJpgConverter.convert(
                resolver = resolver,
                params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = opener,
                jpegWriter = { _, _ -> true },
                clock = { 0L },
                pdfOpen = { fd },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            fail("应抛出 OUTPUT_DIR_UNAVAILABLE")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_DIR_UNAVAILABLE"))
        }
        assertEquals(1, createdRef.size)
        assertEquals(1, deletedRef.size)
        assertEquals(createdRef[0], deletedRef[0])
    }

    // ================================================================
    // renderer 创建失败 → 关闭 fd + 映射 PDF_OPEN_FAILED
    // ================================================================

    @Test
    fun rendererOpenFailure_closesFd_andMapsPdfOpenFailed() {
        val closed = AtomicBoolean(false)
        val fx = Fixture(pageCount = 1)
        try {
            PdfToJpgConverter.convert(
                resolver = resolver,
                params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> throw RuntimeException("renderer init boom") },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, _ -> null },
                jpegWriter = { _, _ -> true },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { closed.set(true) },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            fail("应抛出 PDF_OPEN_FAILED")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("PDF_OPEN_FAILED"))
            assertTrue(e.message!!.contains("无法打开 PDF 渲染器"))
        }
        assertTrue(closed.get())
    }

    // ================================================================
    // JPG 输出流 close() 异常 → 映射为 OUTPUT_WRITE_FAILED
    // ================================================================

    @Test
    fun streamCloseFailure_mappedToOutputWriteFailed() {
        val fx = Fixture(pageCount = 1)
        try {
            PdfToJpgConverter.convert(
                resolver = resolver,
                params = PdfToJpgConverter.ConvertParams(pdfUri, treeUri, "r.pdf", 1),
                rendererFactory = { _ -> fx.renderer },
                subfolderOpener = { _, _ -> folderUri },
                childOpener = { _, name ->
                    val childUri = Mockito.mock(Uri::class.java)
                    val stream = object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray) { /* 正常写入 */ }
                        override fun close() { throw IOException("close failed") }
                    }
                    Pair(childUri, stream)
                },
                jpegWriter = { _, out -> out.write(ByteArray(7)); true },
                clock = { 0L },
                pdfOpen = { fd },
                fdClose = { },
                bitmapFactory = { _, _ -> mockBitmap() },
            )
            fail("应抛出 OUTPUT_WRITE_FAILED（close 异常）")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("OUTPUT_WRITE_FAILED"))
            assertTrue(e.message!!.contains("流关闭异常"))
            assertTrue(e.message!!.contains("第 1 页"))
        }
        assertEquals(1, fx.renderer.closed)
    }
}
