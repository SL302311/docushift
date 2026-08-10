package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DocuShift — 清晰度预设（第 8 期）。
 *
 * 三档且仅三档：96 dpi（低清）、144 dpi（标准，默认）、216 dpi（高精）。
 * 改变页面渲染尺寸，不影响输出目录命名、页码范围、JPG 质量 85 或逐页 Bitmap 回收。
 */
enum class RasterResolution(val dpi: Int, val label: String) {
    LOW(96, "低清"),
    STANDARD(144, "标准"),
    HIGH(216, "高精");

    companion object {
        val DEFAULT: RasterResolution = STANDARD

        fun fromDpi(dpi: Int): RasterResolution? = entries.find { it.dpi == dpi }

        fun isValid(dpi: Int): Boolean = entries.any { it.dpi == dpi }
    }
}

/**
 * DocuShift — 共用 PDF 栅格化核心（第 5 期新增，第 8 期扩展清晰度）。
 *
 * 抽自第 4 期 PDF→PNG 的逐页流程，作为 PNG 与 JPG 共用的生产入口。
 * 本核心只负责「把 PDF 逐页渲染为位图并写进 SAF 输出子文件夹」，
 * 不关心具体编码格式；编码差异由注入的 [RasterOutputStrategy] 收敛。
 *
 * 稳定错误码（异常消息前缀，与第 4 期一致）：
 * - `PDF_OPEN_FAILED:` — PDF 无法打开 / 页数校验不一致
 * - `PAGE_RENDER_FAILED:` — 某一页渲染或编码失败
 * - `OUTPUT_DIR_UNAVAILABLE:` — 输出目录不可用（无法创建子文件夹或页文件）
 * - `OUTPUT_WRITE_FAILED:` — 字节写入 / 流关闭异常
 *
 * 设计要点（与 ImageToPdfConverter / 第 4 期一致）：
 * - 所有 [android.graphics] 调用（PdfRenderer / Page / Bitmap / 压缩）全部走可注入端口，
 *   无设备测试可注入受控实现证明顺序、资源释放与失败清理。
 * - 主流程统一所有权：PdfRenderer、ParcelFileDescriptor、页面与输出流均由本函数
 *   单一 finally 负责关闭，成功与异常路径恰好释放一次。
 * - 一次只持有一张 Bitmap、一次只打开一页；逐页渲染后写文件并立即回收。
 * - 任一页渲染或写入失败 → 停止后续页、释放当前资源、向上抛出（消息含页码与显示名）。
 * - 新建的 URI（子文件夹 + 页文件）记录到 [createdSink]，由协调器在失败时按相反顺序清理。
 * - 总字节数通过 [CountingOutputStream] 累计写出的真实字节，成功页才计入。
 */
object PdfRasterConverter {

    /** 渲染 DPI（按清晰度预设传入，第 8 期前硬编码 144）。 */
    private const val DEFAULT_DPI = 144

    /** 单页最长边上限（px）。 */
    private const val MAX_EDGE = 4096

    /** 单页总像素上限。 */
    private const val MAX_PIXELS = 16_000_000

    data class ConvertParams(
        val pdfUri: Uri,
        val outputTreeUri: Uri,
        val displayName: String,
        /**
         * PDF 总页数（已通过 [PdfInputValidator] 验证）。用于严格校验：
         * 渲染器实际页数与之一致，且导出范围不能超出它。
         */
        val pageCount: Int,
        /**
         * 导出范围（1-based 闭区间）。默认 [startPage]=1、[endPage]=pageCount 表示导出全部页；
         * 仅当调用方（协调器）显式传入不同值时才裁剪范围。
         * 计划要求：文件名沿用原 PDF 页码，故范围只影响「打开哪些页」，
         * 不影响命名/错误信息中的页码。
         */
        val startPage: Int = 1,
        val endPage: Int = pageCount,
        /**
         * 清晰度预设（第 8 期），默认 144 dpi（标准），保持第 4—7 期输出效果不变。
         * 仅允许 [RasterResolution] 三档；在创建任何输出之前由核心校验。
         */
        val resolution: RasterResolution = RasterResolution.DEFAULT,
    )

    /**
     * 输出格式策略：把「编码差异」收敛为一处显式契约。
     * - [extension]：页文件扩展名（不含点），如 "png" / "jpg"。
     * - [mimeType]：子文件夹下页文件创建用的 MIME，如 "image/png" / "image/jpeg"。
     * - [folderSuffix]：输出子文件夹名后缀，如 "_PNG_" / "_JPG_"（夹在基名与时间戳之间）。
     * - [needsWhiteBackground]：渲染前是否以不透明白色预填充 Bitmap（JPEG 无透明通道）。
     * - [encode]：把渲染后的 Bitmap 按本格式编码并写入 [out]；失败抛异常，消息含页码与显示名。
     */
    interface RasterOutputStrategy {
        val extension: String
        val mimeType: String
        val folderSuffix: String
        val needsWhiteBackground: Boolean
        fun encode(bitmap: Bitmap, out: OutputStream, pageIndexOneBased: Int, displayName: String)
    }

    // ================================================================
    // 可注入端口（无设备测试用；默认走真实实现）
    // ================================================================

    /** 由文件描述符打开 PdfRenderer 的工厂。 */
    fun interface PdfRendererFactory {
        fun open(fd: ParcelFileDescriptor): PdfRendererPort
    }

    /** PdfRenderer 抽象。 */
    interface PdfRendererPort {
        val pageCount: Int
        fun openPage(index: Int): PagePort
        fun close()
    }

    /** 单页抽象。 */
    interface PagePort {
        val width: Int   // point 单位（1/72 inch）
        val height: Int
        fun render(bitmap: Bitmap)
        fun close()
    }

    /** 在输出目录内创建子文件夹，返回子文件夹 URI；不可用返回 null。 */
    fun interface SubfolderOpener {
        fun open(treeUri: Uri, folderName: String): Uri?
    }

    /** 在子文件夹内创建页文件，返回 (childUri, OutputStream)；不可用返回 null。 */
    fun interface ChildOutputOpener {
        fun open(folderUri: Uri, fileName: String): Pair<Uri, OutputStream>?
    }

    /** 时间戳供给（用于输出子文件夹命名），测试可注入固定值。 */
    fun interface Clock {
        fun millis(): Long
    }

    /**
     * 把单个 PDF 的每一页渲染为位图并写入新建的输出子文件夹。
     *
     * @param resolver       内容解析器（默认实现创建子文件夹时用）。
     * @param params         转换参数（pdfUri / 输出树 uri / 显示名 / 已验证页数）。
     * @param strategy       输出格式策略（PNG / JPG）。
     * @param createdSink    本次新建的 URI（子文件夹 + 页文件）记录到此，供协调器失败清理。
     * @return `(输出子文件夹 URI 字符串, 所有页总字节数)`。
     */
    fun convert(
        resolver: ContentResolver,
        params: ConvertParams,
        strategy: RasterOutputStrategy,
        createdSink: MutableList<Uri> = mutableListOf(),
        rendererFactory: PdfRendererFactory = RealPdfRendererFactory(),
        subfolderOpener: SubfolderOpener = RealSubfolderOpener(resolver),
        childOpener: ChildOutputOpener = RealChildOutputOpener(resolver, strategy.mimeType),
        clock: Clock = Clock { System.currentTimeMillis() },
        pdfOpen: (Uri) -> ParcelFileDescriptor? = { resolver.openFileDescriptor(it, "r") },
        fdClose: (ParcelFileDescriptor) -> Unit = { it.close() },
        bitmapFactory: (Int, Int) -> Bitmap = { w, h ->
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        },
    ): Pair<String, Long> {
        // 1. 打开 PDF 文件描述符
        val fd = pdfOpen(params.pdfUri)
            ?: throw Exception("PDF_OPEN_FAILED: 无法打开 PDF 文件描述符")
        // 将 renderer 创建纳入资源所有权：打开失败时恰好关闭 fd 并返回稳定错误码
        val renderer = try {
            rendererFactory.open(fd)
        } catch (e: Exception) {
            try {
                fdClose(fd)
            } catch (_: Exception) {
            }
            throw Exception("PDF_OPEN_FAILED: 无法打开 PDF 渲染器：${e.message}")
        }
        try {
            val total = renderer.pageCount
            // 校验器已确认页数；此处再防护一次：严格相等防止二次校验后文件变少/变多
            if (total < 1 || total != params.pageCount || total > 20) {
                throw Exception(
                    "PDF_OPEN_FAILED: PDF 页数与验证不一致（期望 ${params.pageCount}，实际 $total）",
                )
            }

            // 2. 严格校验导出范围（1-based 闭区间），在创建输出目录之前完成。
            //    使用调用方原始输入值，不做夹紧（clamp）——任何越界都直接拒绝，不创建输出。
            //    协调器与 Flutter 交互态纠正可以保留，但核心防御必须严格。
            val startPage = params.startPage
            val endPage = params.endPage
            if (startPage < 1 || endPage > total || startPage > endPage) {
                throw Exception(
                    "INVALID_PAGE_RANGE: 页码范围不合法（起始 $startPage，结束 $endPage，" +
                        "PDF 共 $total 页，${params.displayName}）",
                )
            }
            val exportCount = endPage - startPage + 1

            // 2.5. 清晰度校验（第 8 期）：必须在创建任何输出之前完成。
            //       虽然 ConvertParams.resolution 已为枚举类型（类型安全），
            //       但计划要求核心层仍需显式校验以作防御。
            if (!RasterResolution.isValid(params.resolution.dpi)) {
                throw Exception(
                    "INVALID_RASTER_RESOLUTION: 不支持的清晰度 ${params.resolution.dpi}",
                )
            }

            // 3. 新建输出子文件夹：原文件名_<suffix>_时间戳
            val folderName = buildFolderName(params.displayName, strategy.folderSuffix, clock.millis())
            val folderUri = subfolderOpener.open(params.outputTreeUri, folderName)
                ?: throw Exception("OUTPUT_DIR_UNAVAILABLE: 无法在输出目录创建子文件夹 $folderName")
            createdSink.add(folderUri)

            // 4. 唯一的范围循环：只打开 [startPage, endPage] 内的页，文件名沿用原 PDF 页码。
            var totalBytes = 0L
            for (pos in 0 until exportCount) {
                val pageIndex = startPage - 1 + pos // 0-based 实际 PDF 页索引
                val pageNumber = startPage + pos // 1-based 原 PDF 页码（命名/错误/计数都用它）
                val page = renderer.openPage(pageIndex)
                try {
                    // 计算目标尺寸（按清晰度预设缩放），并按上限等比缩放
                    val (bw, bh) = computeBitmapSize(page.width, page.height, params.resolution.dpi)
                    val bitmap = bitmapFactory(bw, bh)
                    // JPEG 无透明通道：渲染【前】以不透明白色预填充，保证透明/未绘制区不发黑；
                    // 白底必须在 page.render 之前完成，否则会覆盖已渲染的页面内容（空白页）。
                    if (strategy.needsWhiteBackground) {
                        bitmap.eraseColor(Color.WHITE)
                    }
                    try {
                        try {
                            page.render(bitmap)
                        } catch (e: Exception) {
                            throw Exception(
                                "PAGE_RENDER_FAILED: 第 $pageNumber 页（${params.displayName}）渲染失败：${e.message}",
                            )
                        }

                        // 先创建页文件并登记 URI，再编码写入（编码直接写流，必须先有流）
                        // 文件名沿用原 PDF 页码（如导出 3—5 得到 003.png / 004.png / 005.png）；
                        // pageFileName 约定收 0-based 索引，故用 pageIndex（= pageNumber-1）。
                        val fileName = pageFileName(pageIndex, strategy.extension)
                        val opened = childOpener.open(folderUri, fileName)
                            ?: throw Exception("OUTPUT_DIR_UNAVAILABLE: 无法创建页文件 $fileName")
                        val childUri = opened.first
                        val stream = opened.second
                        // 页文件已创建，立刻登记 URI；写失败时不再丢失该页面，由 Coordinator 统一清理
                        createdSink.add(childUri)

                        // 包裹计数流以准确累计本页写出字节（成功页才计入 totalBytes）
                        val counting = CountingOutputStream(stream)
                        try {
                            // 编码并写入（编码失败/写出失败由策略映射为稳定错误码，消息含页码与显示名）
                            strategy.encode(bitmap, counting, pageNumber, params.displayName)
                            totalBytes += counting.count
                        } finally {
                            // 输出流只关闭一次；关闭失败映射为 OUTPUT_WRITE_FAILED（不掩盖已成功写入）
                            try {
                                counting.close()
                            } catch (e: Exception) {
                                throw Exception(
                                    "OUTPUT_WRITE_FAILED: 第 $pageNumber 页（${params.displayName}）流关闭异常：${e.message}",
                                )
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            return Pair(folderUri.toString(), totalBytes)
        } finally {
            try {
                renderer.close()
            } catch (_: Exception) {
            }
            try {
                fdClose(fd)
            } catch (_: Exception) {
            }
        }
    }

    // ================================================================
    // 命名与尺寸（PNG / JPG 共用）
    // ================================================================

    /** 输出子文件夹名称：原文件名_<suffix>_时间戳。使用 UTC 保证跨环境确定性。 */
    internal fun buildFolderName(displayName: String, suffix: String, millis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val ts = fmt.format(Date(millis))
        return "${PdfInputValidator.baseName(displayName)}$suffix$ts"
    }

    /** 页文件名：三位零填充，如 001.png / 001.jpg。 */
    internal fun pageFileName(index: Int, extension: String): String {
        return String.format(Locale.US, "%03d.$extension", index + 1)
    }

    /**
     * 根据 PDF 页点尺寸与清晰度预设 [dpi]，计算目标 Bitmap 尺寸，并按最长边/总像素上限等比缩放。
     *
     * PDF 内部坐标单位是 point（1/72 inch），像素尺寸 = point × (dpi / 72)。
     * 第 8 期前硬编码 144 dpi；现接受 [dpi] 参数，三档预设为 96/144/216。
     */
    internal fun computeBitmapSize(pageWidthPt: Int, pageHeightPt: Int, dpi: Int): Pair<Int, Int> {
        val scale = dpi / 72.0
        var w = maxOf(1, (pageWidthPt * scale).toInt())
        var h = maxOf(1, (pageHeightPt * scale).toInt())

        // 按最长边约束
        val longest = maxOf(w, h)
        if (longest > MAX_EDGE) {
            val s = MAX_EDGE.toDouble() / longest.toDouble()
            w = maxOf(1, (w * s).toInt())
            h = maxOf(1, (h * s).toInt())
        }
        // 按总像素约束
        while (w.toLong() * h.toLong() > MAX_PIXELS) {
            w = maxOf(1, (w * 0.9).toInt())
            h = maxOf(1, (h * 0.9).toInt())
        }
        return Pair(w, h)
    }

    // ================================================================
    // 生产实现
    // ================================================================

    internal class RealPdfRendererFactory : PdfRendererFactory {
        override fun open(fd: ParcelFileDescriptor): PdfRendererPort = object : PdfRendererPort {
            private val renderer = PdfRenderer(fd)
            override val pageCount: Int get() = renderer.pageCount
            override fun openPage(index: Int): PagePort {
                val page = renderer.openPage(index)
                return object : PagePort {
                    override val width: Int get() = page.width
                    override val height: Int get() = page.height
                    override fun render(bitmap: Bitmap) {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                    override fun close() = page.close()
                }
            }
            override fun close() = renderer.close()
        }
    }

    /** 生产子文件夹创建：在树 URI 下用 DocumentsContract 创建目录（MIME_TYPE_DIR）。 */
    internal class RealSubfolderOpener(private val resolver: ContentResolver) : SubfolderOpener {
        override fun open(treeUri: Uri, folderName: String): Uri? = try {
            val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
            )
            android.provider.DocumentsContract.createDocument(
                resolver,
                parentDoc,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                folderName,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 生产页文件创建：在子文件夹 URI 下用 DocumentsContract 创建 [mimeType] 文件。
     * 创建文档成功但无法打开输出流时，尽力删除已创建的文档再返回 null。
     */
    internal class RealChildOutputOpener(
        private val resolver: ContentResolver,
        private val mimeType: String,
    ) : ChildOutputOpener {
        override fun open(folderUri: Uri, fileName: String): Pair<Uri, OutputStream>? = try {
            val childUri = android.provider.DocumentsContract.createDocument(
                resolver, folderUri, mimeType, fileName,
            ) ?: return null
            val stream = try {
                resolver.openOutputStream(childUri)
            } catch (_: Exception) {
                null
            }
            if (stream == null) {
                // 已创建文档无法打开输出流，尽力删除
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(resolver, childUri)
                }
                return null
            }
            Pair(childUri, stream)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 累计写出字节数的包装输出流，用于准确统计每页写出字节（成功页才计入总字节数）。
     * 关闭时委托底层流；底层关闭异常由调用方映射为 OUTPUT_WRITE_FAILED。
     */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        /** 已写出字节数（成功写出的真实字节）。 */
        var count: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            count += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
