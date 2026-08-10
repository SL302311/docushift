package com.example.docushift_mobile

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * ImageToPdfConverter 测试：
 * - [writeWithCounting] 纯函数（正常计数/恰好关闭一次/write/flush/close 失败 → WRITE_FAILED）。
 * - [convertMany] 多图路径（通过可选参数 [bitmapDecoder] / [documentFactory] 注入受控实现，
 *   不依赖 android.graphics.PdfDocument 运行时）：顺序、页数、按页释放、失败关闭文档、
 *   错误含失败序号与显示名、单图兼容子集。
 *
 * 接缝以方法参数注入（非 object 全局可变属性），每个用例自带接缝、互不干扰，
 * 与 README“用例相互独立”的隔离约定一致。
 */
class ImageToPdfConverterTest {

    /** 受控 PDF 文档端口，记录 addPage/write/close 并可在指定页或写入时抛错。 */
    private class FakePdfDocument : ImageToPdfConverter.PdfDocumentPort {
        val pages = mutableListOf<Bitmap>()
        var writeCalled = 0
        var closed = 0
        var failOnAddIndex: Int? = null
        var failWriteMessage: String? = null

        override fun addPage(bitmap: Bitmap) {
            if (failOnAddIndex != null && pages.size == failOnAddIndex) {
                throw RuntimeException("draw boom")
            }
            pages.add(bitmap)
        }

        override fun write(out: OutputStream): Long {
            writeCalled++
            if (failWriteMessage != null) throw Exception("WRITE_FAILED: $failWriteMessage")
            out.write(1)
            return 5L
        }

        override fun close() { closed++ }
    }

    private fun mockBitmap(): Bitmap = Mockito.mock(Bitmap::class.java)
    private fun mockUri(): Uri = Mockito.mock(Uri::class.java).also {
        Mockito.`when`(it.toString()).thenReturn("content://docushift/img.png")
    }

    /**
     * mock Context 必须 stub [Context.getContentResolver]：convertMany 把 cr 传入
     * 非空参数的 bitmapDecoder 前，Kotlin 会插入 Intrinsics.checkNotNull(cr)——
     * 未 stub 时返回 null 会在调用注入 lambda 之前抛无消息 NPE，被包装成 DECODE_FAILED。
     */
    private fun mockContext(): Context = Mockito.mock(Context::class.java).also {
        Mockito.`when`(it.contentResolver)
            .thenReturn(Mockito.mock(android.content.ContentResolver::class.java))
    }

    private fun image(uri: Uri, name: String = "img.png"): ValidatedImage =
        ValidatedImage(uri, name, "image/png", 1000L)

    // ================================================================
    // writeWithCounting（纯函数，原样保留）
    // ================================================================

    /** 记录 close 调用次数，其余行为同 ByteArrayOutputStream。 */
    private open class CloseCountingStream : ByteArrayOutputStream() {
        var closeCount = 0
        override fun close() { closeCount++ }
    }

    @Test
    fun writeWithCounting_normal_returnsByteCount_andDoesNotCloseStream() {
        val out = CloseCountingStream()
        val size = ImageToPdfConverter.writeWithCounting(out) { os ->
            os.write("hello".toByteArray())
        }
        assertEquals(5L, size)
        assertEquals("hello", out.toString("UTF-8"))
        // 关闭由调用方（convertMany）统一负责，本函数不关闭
        assertEquals(0, out.closeCount)
    }

    @Test
    fun writeWithCounting_writeThrows_writeFailed_andDoesNotCloseStream() {
        val out = object : CloseCountingStream() {
            override fun write(b: Int) { throw IOException("boom") }
        }
        try {
            ImageToPdfConverter.writeWithCounting(out) { os -> os.write(1) }
            fail("应抛 WRITE_FAILED")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 WRITE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("WRITE_FAILED"),
            )
        }
        assertEquals(0, out.closeCount)
    }

    @Test
    fun writeWithCounting_flushThrows_writeFailed_andDoesNotCloseStream() {
        val out = object : CloseCountingStream() {
            override fun flush() { throw IOException("flush boom") }
        }
        try {
            ImageToPdfConverter.writeWithCounting(out) { os -> os.write("ok".toByteArray()) }
            fail("应抛 WRITE_FAILED")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 WRITE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("WRITE_FAILED"),
            )
        }
        assertEquals(0, out.closeCount)
    }

    @Test
    fun writeWithCounting_countsAllThreeWriteMethods() {
        val out = CloseCountingStream()
        val size = ImageToPdfConverter.writeWithCounting(out) { os ->
            os.write(1)
            os.write("ab".toByteArray())
            os.write("cd".toByteArray(), 0, 2)
        }
        // 1 + 2 + 2 = 5 字节，三种 write 均被计数
        assertEquals(5L, size)
        assertEquals(0, out.closeCount)
    }

    // ================================================================
    // convertMany（多图，接缝以参数注入）
    // ================================================================

    @Test
    fun convertMany_ordersPages_andReleasesEachBitmapOnce() {
        val b1 = mockBitmap(); val b2 = mockBitmap(); val b3 = mockBitmap()
        val bmps = listOf(b1, b2, b3)
        var i = 0

        val doc = FakePdfDocument()
        val images = listOf(image(mockUri(), "a.png"), image(mockUri(), "b.png"), image(mockUri(), "c.png"))
        val ctx = mockContext()
        val outUri = mockUri()
        val (path, size) = ImageToPdfConverter.convertMany(
            ctx, images, outUri,
            { ByteArrayOutputStream() },
            { _, _ -> bmps[i++] },
            { doc },
        )

        assertEquals(3, doc.pages.size)
        assertEquals(b1, doc.pages[0])
        assertEquals(b2, doc.pages[1])
        assertEquals(b3, doc.pages[2])
        assertEquals(1, doc.writeCalled)
        assertEquals(1, doc.closed)
        assertEquals(outUri.toString(), path)
        assertEquals(5L, size)
        Mockito.verify(b1).recycle()
        Mockito.verify(b2).recycle()
        Mockito.verify(b3).recycle()
    }

    @Test
    fun convertMany_singleImage_regression() {
        val b1 = mockBitmap()
        val doc = FakePdfDocument()

        val ctx = mockContext()
        val (path, size) = ImageToPdfConverter.convertMany(
            ctx, listOf(image(mockUri(), "one.png")), mockUri(),
            { ByteArrayOutputStream() },
            { _, _ -> b1 },
            { doc },
        )

        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.writeCalled)
        assertEquals(5L, size)
        Mockito.verify(b1).recycle()
    }

    @Test
    fun convertMany_decodeFailure_stopsLoop_closesDoc_andReportsIndex() {
        val b1 = mockBitmap()
        var call = 0
        val doc = FakePdfDocument()

        val ctx = mockContext()
        try {
            ImageToPdfConverter.convertMany(
                ctx,
                listOf(image(mockUri(), "a.png"), image(mockUri(), "b.png"), image(mockUri(), "c.png")),
                mockUri(),
                { ByteArrayOutputStream() },
                { _, _ ->
                    call++
                    if (call == 2) throw Exception("DECODE_FAILED: corrupted")
                    b1
                },
                { doc },
            )
            fail("第二张解码失败应抛异常")
        } catch (e: Exception) {
            assertTrue("消息应以 DECODE_FAILED 开头，实际: ${e.message}", e.message!!.startsWith("DECODE_FAILED"))
            assertTrue("错误应含失败序号与显示名，实际: ${e.message}", e.message!!.contains("第 2 张"))
        }
        // 仅第一张被加入；循环在第二张失败处停止；文档已关闭
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.closed)
        Mockito.verify(b1).recycle()
    }

    @Test
    fun convertMany_writeFailure_writeFailed_andClosesDoc() {
        val doc = FakePdfDocument()
        doc.failWriteMessage = "disk full"

        val ctx = mockContext()
        try {
            ImageToPdfConverter.convertMany(
                ctx, listOf(image(mockUri())), mockUri(),
                { ByteArrayOutputStream() },
                { _, _ -> mockBitmap() },
                { doc },
            )
            fail("写入失败应抛异常")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 WRITE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("WRITE_FAILED"),
            )
        }
        assertEquals(1, doc.closed)
    }

    // ================================================================
    // convertMany 输出流恰好关闭一次（统一所有权，覆盖真实关闭行为）
    // ================================================================

    @Test
    fun convertMany_closesOutputStreamExactlyOnce_onSuccess() {
        val out = CloseCountingStream()
        val doc = FakePdfDocument()
        val (_, size) = ImageToPdfConverter.convertMany(
            mockContext(), listOf(image(mockUri())), mockUri(),
            { out },
            { _, _ -> mockBitmap() },
            { doc },
        )
        assertEquals(5L, size)
        assertEquals(1, out.closeCount)
    }

    @Test
    fun convertMany_closesOutputStreamExactlyOnce_onWriteFailure() {
        val out = CloseCountingStream()
        val doc = FakePdfDocument()
        doc.failWriteMessage = "disk full"
        try {
            ImageToPdfConverter.convertMany(
                mockContext(), listOf(image(mockUri())), mockUri(),
                { out },
                { _, _ -> mockBitmap() },
                { doc },
            )
            fail("写入失败应抛异常")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 WRITE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("WRITE_FAILED"),
            )
        }
        assertEquals(1, out.closeCount)
    }

    @Test
    fun convertMany_closeFailure_mapsToWriteFailed() {
        val out = object : CloseCountingStream() {
            override fun close() { closeCount++; throw IOException("close boom") }
        }
        val doc = FakePdfDocument()
        try {
            ImageToPdfConverter.convertMany(
                mockContext(), listOf(image(mockUri())), mockUri(),
                { out },
                { _, _ -> mockBitmap() },
                { doc },
            )
            fail("关闭失败应抛 WRITE_FAILED")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 WRITE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("WRITE_FAILED"),
            )
        }
        assertEquals(1, out.closeCount)
    }

    @Test
    fun convertMany_addPageFailure_stopsLoop_closesDoc_reportsIndex() {
        val b1 = mockBitmap()
        val b2 = mockBitmap()
        val doc = FakePdfDocument()
        doc.failOnAddIndex = 1   // 第二张（index 1）绘制失败
        val ctx = mockContext()
        try {
            ImageToPdfConverter.convertMany(
                ctx,
                listOf(image(mockUri(), "a.png"), image(mockUri(), "b.png"), image(mockUri(), "c.png")),
                mockUri(),
                { ByteArrayOutputStream() },
                { _, _ -> if (doc.pages.size == 0) b1 else b2 },
                { doc },
            )
            fail("第二张绘制失败应抛异常")
        } catch (e: Exception) {
            assertTrue(
                "消息应以 ADD_PAGE_FAILED 开头，实际: ${e.message}",
                e.message!!.startsWith("ADD_PAGE_FAILED"),
            )
            assertTrue(
                "错误应含失败序号与显示名，实际: ${e.message}",
                e.message!!.contains("第 2 张"),
            )
        }
        // 仅第一张被加入；循环在第二张失败处停止；文档已关闭
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.closed)
        Mockito.verify(b1).recycle()
        Mockito.verify(b2).recycle()   // 失败图片也回收
    }

    @Test
    fun decodeOrientedBitmap_rotated_recyclesOriginalBitmap() {
        val original = mockBitmap()
        val oriented = mockBitmap()
        val result = ImageToPdfConverter.decodeOrientedBitmap(
            Mockito.mock(android.content.ContentResolver::class.java),
            mockUri(),
            decode = { _, _ -> original },
            readOrientation = { _, _ -> android.media.ExifInterface.ORIENTATION_ROTATE_90 },
            createOriented = { _, _ -> oriented },
        )
        assertEquals(oriented, result)
        // 生成新 Bitmap 后原图被回收；新图由 convertMany 回收
        Mockito.verify(original).recycle()
        Mockito.verify(oriented, Mockito.never()).recycle()
    }

    // ================================================================
    // 第 7 期：BMP 通过 convertMany，混合格式按序生成页
    // ================================================================

    @Test
    fun convertMany_mixedPngJpgBmp_inOrder() {
        val b1 = mockBitmap(); val b2 = mockBitmap(); val b3 = mockBitmap()
        val bmps = listOf(b1, b2, b3)
        var i = 0

        val doc = FakePdfDocument()
        val images = listOf(
            image(mockUri(), "a.png"),
            image(mockUri(), "b.jpg"),
            image(mockUri(), "c.bmp"),  // BMP 排在最后
        )
        val ctx = mockContext()
        val outUri = mockUri()
        val (path, size) = ImageToPdfConverter.convertMany(
            ctx, images, outUri,
            { ByteArrayOutputStream() },
            { _, _ -> bmps[i++] },
            { doc },
        )

        assertEquals(3, doc.pages.size)
        assertEquals(b1, doc.pages[0])  // PNG
        assertEquals(b2, doc.pages[1])  // JPG
        assertEquals(b3, doc.pages[2])  // BMP
        assertEquals(1, doc.writeCalled)
        assertEquals(outUri.toString(), path)
        assertEquals(5L, size)
        Mockito.verify(b1).recycle()
        Mockito.verify(b2).recycle()
        Mockito.verify(b3).recycle()
    }

    @Test
    fun convertMany_bmpSecondImageDecodeFailure_stopsAndReportsIndex() {
        val b1 = mockBitmap()
        val b2 = mockBitmap() // will not be used
        var call = 0
        val doc = FakePdfDocument()

        val ctx = mockContext()
        try {
            ImageToPdfConverter.convertMany(
                ctx,
                listOf(
                    image(mockUri(), "first.png"),
                    image(mockUri(), "second.bmp"),  // BMP 第二张，解码失败
                    image(mockUri(), "third.png"),
                ),
                mockUri(),
                { ByteArrayOutputStream() },
                { _, _ ->
                    call++
                    if (call == 2) throw Exception("DECODE_FAILED: corrupted")
                    b1
                },
                { doc },
            )
            fail("BMP 第二张解码失败应抛异常")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("DECODE_FAILED"))
            assertTrue(e.message!!.contains("第 2 张"))
            assertTrue(e.message!!.contains("second.bmp"))
        }
        // 仅第一张被加入 PDF；循环在 BMP 失败处停止；文档已关闭
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.closed)
        Mockito.verify(b1).recycle()
        // b2 未创建（decode 失败之前就已抛异常），不回收
    }

    @Test
    fun decodeOrientedBitmap_bmpExifNormal_noRotation() {
        // BMP 无 EXIF，方向默认为 ORIENTATION_NORMAL → 返回原图，不创建旋转副本
        val bitmap = mockBitmap()
        val result = ImageToPdfConverter.decodeOrientedBitmap(
            Mockito.mock(ContentResolver::class.java),
            mockUri(),
            decode = { _, _ -> bitmap },
            readOrientation = { _, _ -> android.media.ExifInterface.ORIENTATION_NORMAL },
            createOriented = { _, _ -> mockBitmap() },
        )
        assertEquals(bitmap, result)
        // BMP 正常方向：原图不回收，不创建新 Bitmap
        Mockito.verify(bitmap, Mockito.never()).recycle()
    }
}
