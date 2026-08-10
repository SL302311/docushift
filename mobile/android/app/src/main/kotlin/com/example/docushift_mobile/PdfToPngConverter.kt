package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.OutputStream

/**
 * DocuShift PDF → PNG 转换器（第 4 期实现，第 5 期重构）。
 *
 * 现作为共用栅格化核心 [PdfRasterConverter] 的 PNG 兼容外观：
 * 复用第 5 期抽出的 PDF 逐页渲染 / 资源所有权 / 页数校验 / 失败清理核心，
 * 仅在 [convert] 中传入一个 PNG 输出策略（扩展名 png、MIME image/png、
 * 文件夹后缀 _PNG_、不需要白底、Bitmap.compress(PNG) 编码）。
 *
 * 对外公开 API（[ConvertParams]、[convert] 签名、[PngEncoder]、命名/尺寸函数和端口接口）
 * 与第 4 期完全一致，确保既有测试与原生接线原样回归。
 *
 * 稳定错误码与第 4 期一致：PDF_OPEN_FAILED / PAGE_RENDER_FAILED /
 * OUTPUT_DIR_UNAVAILABLE / OUTPUT_WRITE_FAILED。
 */
object PdfToPngConverter {

    // 端口与参数类型复用共用核心，保持对外名称不变（既有测试按 PdfToPngConverter.Xxx 引用）
    typealias ConvertParams = PdfRasterConverter.ConvertParams
    typealias PdfRendererFactory = PdfRasterConverter.PdfRendererFactory
    typealias PdfRendererPort = PdfRasterConverter.PdfRendererPort
    typealias PagePort = PdfRasterConverter.PagePort
    typealias SubfolderOpener = PdfRasterConverter.SubfolderOpener
    typealias ChildOutputOpener = PdfRasterConverter.ChildOutputOpener
    typealias Clock = PdfRasterConverter.Clock

    /** 把渲染后的 Bitmap 编码为 PNG 字节。 */
    fun interface PngEncoder {
        fun encode(bitmap: Bitmap): ByteArray
    }

    /**
     * 把单个 PDF 的每一页渲染为 PNG 并写入新建的输出子文件夹。
     *
     * @param resolver       内容解析器（默认实现创建子文件夹时用）。
     * @param params         转换参数（pdfUri / 输出树 uri / 显示名 / 已验证页数）。
     * @param createdSink    本次新建的 URI（子文件夹 + 页文件）记录到此，供协调器失败清理。
     * @return `(输出子文件夹 URI 字符串, 所有页 PNG 总字节数)`。
     */
    fun convert(
        resolver: ContentResolver,
        params: ConvertParams,
        createdSink: MutableList<Uri> = mutableListOf(),
        rendererFactory: PdfRendererFactory = PdfRasterConverter.RealPdfRendererFactory(),
        subfolderOpener: SubfolderOpener = PdfRasterConverter.RealSubfolderOpener(resolver),
        childOpener: ChildOutputOpener = PdfRasterConverter.RealChildOutputOpener(resolver, "image/png"),
        encoder: PngEncoder = RealPngEncoder(),
        clock: Clock = Clock { System.currentTimeMillis() },
        pdfOpen: (Uri) -> ParcelFileDescriptor? = { resolver.openFileDescriptor(it, "r") },
        fdClose: (ParcelFileDescriptor) -> Unit = { it.close() },
        bitmapFactory: (Int, Int) -> Bitmap = { w, h ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        },
    ): Pair<String, Long> {
        val pngStrategy = object : PdfRasterConverter.RasterOutputStrategy {
            override val extension: String get() = "png"
            override val mimeType: String get() = "image/png"
            override val folderSuffix: String get() = "_PNG_"
            override val needsWhiteBackground: Boolean get() = false

            override fun encode(bitmap: Bitmap, out: OutputStream, pageIndexOneBased: Int, displayName: String) {
                val bytes = try {
                    encoder.encode(bitmap)
                } catch (e: Exception) {
                    throw Exception(
                        "PAGE_RENDER_FAILED: 第 $pageIndexOneBased 页（$displayName）编码失败：${e.message}",
                    )
                }
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {
                    throw Exception(
                        "OUTPUT_WRITE_FAILED: 第 $pageIndexOneBased 页（$displayName）写入失败：${e.message}",
                    )
                }
            }
        }
        return PdfRasterConverter.convert(
            resolver = resolver,
            params = params,
            strategy = pngStrategy,
            createdSink = createdSink,
            rendererFactory = rendererFactory,
            subfolderOpener = subfolderOpener,
            childOpener = childOpener,
            clock = clock,
            pdfOpen = pdfOpen,
            fdClose = fdClose,
            bitmapFactory = bitmapFactory,
        )
    }

    // ================================================================
    // 命名与尺寸（委托共用核心；保持 PdfToPngConverter.Xxx 名称）
    // ================================================================

    /** 输出子文件夹名称：原文件名_PNG_时间戳。 */
    internal fun buildFolderName(displayName: String, millis: Long): String {
        return PdfRasterConverter.buildFolderName(displayName, "_PNG_", millis)
    }

    /** 页文件名：三位零填充，如 001.png。 */
    internal fun pageFileName(index: Int): String {
        return PdfRasterConverter.pageFileName(index, "png")
    }

    /**
     * 根据 PDF 页点尺寸与清晰度 [dpi]，计算目标 Bitmap 尺寸，并按最长边/总像素上限等比缩放。
     */
    internal fun computeBitmapSize(pageWidthPt: Int, pageHeightPt: Int, dpi: Int): Pair<Int, Int> {
        return PdfRasterConverter.computeBitmapSize(pageWidthPt, pageHeightPt, dpi)
    }

    /** 生产 PNG 编码：Bitmap.compress(PNG)。 */
    private class RealPngEncoder : PngEncoder {
        override fun encode(bitmap: Bitmap): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
    }
}
