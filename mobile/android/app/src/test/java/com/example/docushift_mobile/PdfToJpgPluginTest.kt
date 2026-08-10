package com.example.docushift_mobile

import io.flutter.plugin.common.MethodCall
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

/**
 * PdfToJpgPlugin 平台通道测试（第 5 期）：结构性门禁与分发（独立频道）。
 *
 * - pickPdf / pickOutputDirectory → 直接委派 coordinator。
 * - convertPdfToJpg：pdfUri / directoryUri 缺失或为空 → INVALID_ARGS；
 *   合法参数 → coordinator.convertPdfToJpg(pdfUri, directoryUri, result）。
 */
class PdfToJpgPluginTest {

    private val coordinator = Mockito.mock(PdfToJpgCoordinator::class.java)
    private val activity = Mockito.mock(MainActivity::class.java).also {
        Mockito.`when`(it.pdfToJpgCoordinator).thenReturn(coordinator)
    }
    private val plugin = PdfToJpgPlugin(activity)

    @Test
    fun pickPdf_dispatchesToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("pickPdf", null), result)
        Mockito.verify(coordinator).pickPdf(result)
    }

    @Test
    fun pickOutputDirectory_dispatchesToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("pickOutputDirectory", null), result)
        Mockito.verify(coordinator).pickOutputDirectory(result)
    }

    @Test
    fun convert_missingPdfUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("convertPdfToJpg", mapOf("directoryUri" to "content://tree")),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convert_emptyPdfUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("convertPdfToJpg", mapOf("pdfUri" to "", "directoryUri" to "content://tree")),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convert_missingDirectoryUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("convertPdfToJpg", mapOf("pdfUri" to "content://in.pdf")),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convert_validArgs_dispatchesBothUris() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree"),
            ),
            result,
        )
        // 缺省页码参数 → 透传 null，协调器按全页默认处理（全字面量，避免非空参数 NPE）
        Mockito.verify(coordinator)
            .convertPdfToJpg("content://in.pdf", "content://tree", result, null, null, null)
    }

    @Test
    fun convert_withRange_dispatchesExactPages() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf(
                    "pdfUri" to "content://in.pdf",
                    "directoryUri" to "content://tree",
                    "startPage" to 3,
                    "endPage" to 5,
                ),
            ),
            result,
        )
        Mockito.verify(coordinator)
            .convertPdfToJpg("content://in.pdf", "content://tree", result, 3, 5, null)
    }

    @Test
    fun convert_nonIntegerStartPage_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf(
                    "pdfUri" to "content://in.pdf",
                    "directoryUri" to "content://tree",
                    "startPage" to "3", // 字符串非法
                ),
            ),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
        // 非法参数应在分发前失败，协调器完全不应被调用
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun unknownMethod_notImplemented() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("unknown", null), result)
        assertEquals(true, result.completed)
    }

    // ================================================================
    // 第 8 期 R1：resolution 参数解析
    // ================================================================

    @Test
    fun convert_stringResolution_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to "96"),
            ),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun convert_95dpi_invalidRasterResolution() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to 95),
            ),
            result,
        )
        assertEquals("INVALID_RASTER_RESOLUTION", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun convert_145dpi_invalidRasterResolution() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to 145),
            ),
            result,
        )
        assertEquals("INVALID_RASTER_RESOLUTION", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun convert_96dpi_passedToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to 96),
            ),
            result,
        )
        Mockito.verify(coordinator)
            .convertPdfToJpg("content://in.pdf", "content://tree", result, null, null, 96)
    }

    @Test
    fun convert_144dpi_passedToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to 144),
            ),
            result,
        )
        Mockito.verify(coordinator)
            .convertPdfToJpg("content://in.pdf", "content://tree", result, null, null, 144)
    }

    @Test
    fun convert_216dpi_passedToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall(
                "convertPdfToJpg",
                mapOf("pdfUri" to "content://in.pdf", "directoryUri" to "content://tree",
                    "resolution" to 216),
            ),
            result,
        )
        Mockito.verify(coordinator)
            .convertPdfToJpg("content://in.pdf", "content://tree", result, null, null, 216)
    }
}
