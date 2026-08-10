package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.OutputStream

/**
 * DocuShift PDF → JPG 转换器（第 5 期新增）。
 *
 * 与 [PdfToPngConverter] 同构，复用共用栅格化核心 [PdfRasterConverter]，
 * 仅在 [convert] 中传入一个 JPG 输出策略：
 * - 扩展名 jpg、MIME image/jpeg、文件夹后缀 _JPG_；
 * - 渲染前以不透明白色预填充 Bitmap（JPEG 无透明通道，避免透明/未绘制区变黑）；
 * - 编码用 `Bitmap.compress(JPEG, 85, stream)`，固定质量 **85**，检查返回值。
 *
 * 对外公开 API（[ConvertParams]、[convert] 签名、[JpegStreamWriter]、命名/尺寸函数和端口接口）
 * 与 PDF→PNG 同构，便于共用测试夹具与协调器接线。
 *
 * 稳定错误码与第 4/5 期一致：PDF_OPEN_FAILED / PAGE_RENDER_FAILED /
 * OUTPUT_DIR_UNAVAILABLE / OUTPUT_WRITE_FAILED。
 */
object PdfToJpgConverter {

    typealias ConvertParams = PdfRasterConverter.ConvertParams
    typealias PdfRendererFactory = PdfRasterConverter.PdfRendererFactory
    typealias PdfRendererPort = PdfRasterConverter.PdfRendererPort
    typealias PagePort = PdfRasterConverter.PagePort
    typealias SubfolderOpener = PdfRasterConverter.SubfolderOpener
    typealias ChildOutputOpener = PdfRasterConverter.ChildOutputOpener
    typealias Clock = PdfRasterConverter.Clock

    /** 把渲染后的 Bitmap 编码为 JPG 并写入 [out]，返回 `Bitmap.compress` 的成功与否。 */
    fun interface JpegStreamWriter {
        fun compress(bitmap: Bitmap, out: OutputStream): Boolean
    }

    /**
     * 把单个 PDF 的每一页渲染为 JPG 并写入新建的输出子文件夹。
     *
     * @param resolver       内容解析器（默认实现创建子文件夹时用）。
     * @param params         转换参数（pdfUri / 输出树 uri / 显示名 / 已验证页数）。
     * @param createdSink    本次新建的 URI（子文件夹 + 页文件）记录到此，供协调器失败清理。
     * @return `(输出子文件夹 URI 字符串, 所有页 JPG 总字节数)`。
     */
    fun convert(
        resolver: ContentResolver,
        params: ConvertParams,
        createdSink: MutableList<Uri> = mutableListOf(),
        rendererFactory: PdfRendererFactory = PdfRasterConverter.RealPdfRendererFactory(),
        subfolderOpener: SubfolderOpener = PdfRasterConverter.RealSubfolderOpener(resolver),
        childOpener: ChildOutputOpener = PdfRasterConverter.RealChildOutputOpener(resolver, "image/jpeg"),
        jpegWriter: JpegStreamWriter = RealJpgStreamWriter(),
        clock: Clock = Clock { System.currentTimeMillis() },
        pdfOpen: (Uri) -> ParcelFileDescriptor? = { resolver.openFileDescriptor(it, "r") },
        fdClose: (ParcelFileDescriptor) -> Unit = { it.close() },
        bitmapFactory: (Int, Int) -> Bitmap = { w, h ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        },
    ): Pair<String, Long> {
        val jpgStrategy = object : PdfRasterConverter.RasterOutputStrategy {
            override val extension: String get() = "jpg"
            override val mimeType: String get() = "image/jpeg"
            override val folderSuffix: String get() = "_JPG_"
            override val needsWhiteBackground: Boolean get() = true

            override fun encode(bitmap: Bitmap, out: OutputStream, pageIndexOneBased: Int, displayName: String) {
                val ok = try {
                    jpegWriter.compress(bitmap, out)
                } catch (e: java.io.IOException) {
                    // 编码过程中的写入失败（I/O）→ OUTPUT_WRITE_FAILED
                    throw Exception(
                        "OUTPUT_WRITE_FAILED: 第 $pageIndexOneBased 页（$displayName）写入失败：${e.message}",
                    )
                } catch (e: Exception) {
                    throw Exception(
                        "PAGE_RENDER_FAILED: 第 $pageIndexOneBased 页（$displayName）编码失败：${e.message}",
                    )
                }
                if (!ok) {
                    throw Exception(
                        "OUTPUT_WRITE_FAILED: 第 $pageIndexOneBased 页（$displayName）JPEG 编码失败（compress 返回 false）",
                    )
                }
                try {
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
            strategy = jpgStrategy,
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
    // 命名与尺寸（委托共用核心；保持 PdfToJpgConverter.Xxx 名称）
    // ================================================================

    /** 输出子文件夹名称：原文件名_JPG_时间戳。 */
    internal fun buildFolderName(displayName: String, millis: Long): String {
        return PdfRasterConverter.buildFolderName(displayName, "_JPG_", millis)
    }

    /** 页文件名：三位零填充，如 001.jpg。 */
    internal fun pageFileName(index: Int): String {
        return PdfRasterConverter.pageFileName(index, "jpg")
    }

    /**
     * 根据 PDF 页点尺寸与清晰度 [dpi]，计算目标 Bitmap 尺寸，并按最长边/总像素上限等比缩放。
     */
    internal fun computeBitmapSize(pageWidthPt: Int, pageHeightPt: Int, dpi: Int): Pair<Int, Int> {
        return PdfRasterConverter.computeBitmapSize(pageWidthPt, pageHeightPt, dpi)
    }

    /** 生产 JPG 编码：Bitmap.compress(JPEG, 85)。 */
    internal class RealJpgStreamWriter : JpegStreamWriter {
        override fun compress(bitmap: Bitmap, out: OutputStream): Boolean {
            return bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
    }
}
