package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.OutputStream

/**
 * DocuShift 图片 → PDF 转换器 — 使用 Android 系统 PdfDocument API。
 *
 * 稳定错误码（异常消息前缀）：
 * - `DECODE_FAILED:` — 图片解码失败
 * - `WRITE_FAILED:` — PDF 写入失败
 * - `UNSUPPORTED_FORMAT:` — 不支持的图片格式
 *
 * 第 3 期新增多图入口 [convertMany]：按列表顺序逐页解码、绘制、回收 Bitmap，
 * 每页失败立即关闭文档并向上抛出（含失败序号与显示名），输出 URI 清理由
 * [ImageToPdfCoordinator] 负责。
 *
 * 为无设备测试预留接缝（[bitmapDecoder] / [documentFactory]），默认走真实
 * 解码与 PdfDocument；测试可注入受控实现以证明顺序、页数与资源释放。
 */
object ImageToPdfConverter {

    /**
     * 转换单张图片为单页 PDF（第 2 期兼容子集）。
     * 委托 [convertMany] 实现，确保单图与多图共用同一生产路径。
     */
    fun convert(
        context: android.content.Context,
        inputUri: Uri,
        outputUri: Uri,
        outputStreamOpener: ((Uri) -> OutputStream?)? = null,
    ): Pair<String, Long> {
        return convertMany(
            context,
            listOf(ValidatedImage(inputUri, "", "", 0)),
            outputUri,
            outputStreamOpener,
        )
    }

    /**
     * 按有序列表把多张图片转换为多页 PDF。
     *
     * - 仅解码当前图片；应用 EXIF；创建对应方向页并绘制；进入下一张前回收 Bitmap。
     * - 任一页解码/绘制失败 → 关闭文档并抛出（消息含序号与显示名 + 稳定错误码）。
     * - 全部页完成后只调用一次 [PdfDocumentPort.write]；输出流由写入器恰好关闭一次。
     *
     * 第 3 期起 [bitmapDecoder] / [documentFactory] 作为可选接缝注入（默认走真实实现），
     * 取代原先 object 上的可变全局属性——避免单例全局可变状态在跨用例/跨调用间泄漏。
     *
     * @return `(outputUri.toString(), 实际字节数)`。
     */
    fun convertMany(
        context: android.content.Context,
        images: List<ValidatedImage>,
        outputUri: Uri,
        outputStreamOpener: ((Uri) -> OutputStream?)? = null,
        bitmapDecoder: (ContentResolver, ValidatedImage) -> Bitmap =
            { cr, image -> decodeOrientedBitmap(cr, image.uri) },
        documentFactory: () -> PdfDocumentPort = { RealPdfDocument() },
    ): Pair<String, Long> {
        val cr = context.contentResolver
        val opener = outputStreamOpener ?: { u -> cr.openOutputStream(u) }
        val out = opener(outputUri) ?: throw Exception("WRITE_FAILED: 无法打开输出流")

        // 统一输出流所有权：仅此处的 finally 负责恰好关闭一次。
        // writeWithCounting 只写 + 刷新 + 计数，不再关闭底层流（见下方实现）。
        var primary: Throwable? = null
        try {
            val doc = documentFactory()
            try {
                for ((index, image) in images.withIndex()) {
                    val bitmap = try {
                        bitmapDecoder(cr, image)
                    } catch (e: Exception) {
                        // 解码失败统一包装为稳定错误码，附失败序号、显示名与底层原因
                        throw Exception(
                            "DECODE_FAILED: 第 ${index + 1} 张（${image.displayName}）解码失败：${e.message}",
                        )
                    }
                    try {
                        doc.addPage(bitmap)
                    } catch (e: Exception) {
                        // 绘制失败：回收当前图、停止后续页、包装为可定位稳定错误码
                        bitmap.recycle()
                        throw Exception(
                            "ADD_PAGE_FAILED: 第 ${index + 1} 张（${image.displayName}）绘制失败：${e.message}",
                        )
                    }
                    // 成功加入本页后回收当前 Bitmap，不同时持有多张
                    bitmap.recycle()
                }
                val size = doc.write(out)
                return Pair(outputUri.toString(), size)
            } finally {
                doc.close()
            }
        } catch (e: Exception) {
            primary = e
            throw e
        } finally {
            try {
                out.close()
            } catch (ce: Exception) {
                // 关闭失败：仅当主流程成功返回时才升级为 WRITE_FAILED；
                // 若已有转换/写入异常在传播，保留原错误（关闭异常不覆盖原错误）。
                if (primary == null) throw Exception("WRITE_FAILED: ${ce.message}")
            }
        }
    }

    // ================================================================
    // PDF 文档端口（便于无设备测试注入）
    // ================================================================

    /** PDF 文档抽象：添加一页、写入并计数、关闭。 */
    interface PdfDocumentPort {
        /** 把 Bitmap 绘制到新的一页（白底、等比缩放、按方向）。 */
        fun addPage(bitmap: Bitmap)

        /** 写入输出流并返回实际字节数（恰好关闭一次）。 */
        fun write(out: OutputStream): Long

        /** 释放文档资源（幂等）。 */
        fun close()
    }

    /** 生产实现：包装 Android 系统 [PdfDocument]。 */
    private class RealPdfDocument : PdfDocumentPort {
        private val doc = PdfDocument()
        private var closed = false

        override fun addPage(bitmap: Bitmap) {
            val layout = computeLayout(bitmap.width, bitmap.height)
            val pageInfo = PdfDocument.PageInfo.Builder(
                layout.pageWidth.toInt(), layout.pageHeight.toInt(), 1,
            ).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawBitmap(
                bitmap, null,
                android.graphics.RectF(
                    layout.drawX, layout.drawY,
                    layout.drawX + layout.drawW, layout.drawY + layout.drawH,
                ),
                null,
            )
            doc.finishPage(page)
        }

        override fun write(out: OutputStream): Long {
            return writeWithCounting(out) { os -> doc.writeTo(os) }
        }

        override fun close() {
            if (closed) return
            closed = true
            doc.close()
        }
    }

    // ================================================================
    // 页面布局
    // ================================================================

    private data class LayoutResult(
        val pageWidth: Float, val pageHeight: Float,
        val drawX: Float, val drawY: Float,
        val drawW: Float, val drawH: Float,
    )

    private fun computeLayout(imgW: Int, imgH: Int): LayoutResult {
        val isLandscape = imgW > imgH
        val pageW = if (isLandscape) 842f else 595f
        val pageH = if (isLandscape) 595f else 842f
        val margin = 34f
        val contentW = pageW - 2 * margin
        val contentH = pageH - 2 * margin
        val scale = minOf(contentW / imgW, contentH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val drawX = margin + (contentW - drawW) / 2
        val drawY = margin + (contentH - drawH) / 2
        return LayoutResult(pageW, pageH, drawX, drawY, drawW, drawH)
    }

    // ================================================================
    // 解码（损坏图片返回 null）
    // ================================================================

    private fun decodeSampledBitmap(cr: ContentResolver, uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        // 宽高 <= 0 即损坏，不等到写 PDF 才失败
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val sampleSize = ImageInputValidator.calculateSampleSize(opts.outWidth, opts.outHeight)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }

    /**
     * 解码并应用 EXIF 方向，返回可能新建的方向修正 Bitmap。
     *
     * 为纯 JVM 测试预留 [decode] / [readOrientation] / [createOriented] 接缝：
     * 默认走真实 [decodeSampledBitmap]、[readExifOrientation] 与 [Bitmap.createBitmap]；
     * 测试可注入受控实现，验证“旋转产生新 Bitmap 时原图被回收”的资源所有权。
     */
    internal fun decodeOrientedBitmap(
        cr: ContentResolver,
        uri: Uri,
        decode: (ContentResolver, Uri) -> Bitmap? = { c, u -> decodeSampledBitmap(c, u) },
        readOrientation: (ContentResolver, Uri) -> Int = { c, u -> readExifOrientation(c, u) },
        createOriented: (Bitmap, android.graphics.Matrix) -> Bitmap =
            { b, m -> Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true) },
    ): Bitmap {
        val bitmap = decode(cr, uri)
            ?: throw Exception("DECODE_FAILED: 图片解码失败")
        return applyExifOrientation(bitmap, readOrientation(cr, uri), createOriented)
    }

    // ================================================================
    // EXIF 方向
    // ================================================================

    /**
     * 按 EXIF [orientation] 修正方向。
     * - 正常方向或未知方向：原样返回（不新建）。
     * - 旋转/镜像：经 [createOriented] 生成新 Bitmap 后立即回收原图，避免泄漏。
     */
    private fun applyExifOrientation(
        bitmap: Bitmap,
        orientation: Int,
        createOriented: (Bitmap, android.graphics.Matrix) -> Bitmap,
    ): Bitmap {
        if (orientation == android.media.ExifInterface.ORIENTATION_NORMAL) return bitmap
        val matrix = buildOrientationMatrix(orientation) ?: return bitmap
        // 生成新 Bitmap 后原图不再需要，立即回收，避免多图转换持续泄漏图像内存
        val oriented = createOriented(bitmap, matrix)
        bitmap.recycle()
        return oriented
    }

    /** 将 EXIF 方向映射为旋转/镜像矩阵；未知方向返回 null（保持原图）。 */
    private fun buildOrientationMatrix(orientation: Int): android.graphics.Matrix? {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.preScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(90f); matrix.preScale(1f, -1f)
            }
            else -> return null
        }
        return matrix
    }

    private fun readExifOrientation(cr: ContentResolver, uri: Uri): Int {
        return try {
            cr.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }

    // ================================================================
    // 计数字节流 + 关闭语义
    // ================================================================

    /** 包装 [OutputStream]，计数所有写入字节。覆盖三种 write 方法。 */
    class CountingOutputStream(delegate: OutputStream) : java.io.FilterOutputStream(delegate) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len.toLong()
        }
    }

    /**
     * 将 PDF 内容写入 [output] 并计数写入字节；**不关闭** [output]。
     *
     * - 用 [CountingOutputStream] 包裹，三种 write 均真实计数。
     * - write / flush 任一失败都稳定抛出 `WRITE_FAILED`（不吞异常）。
     * - 输出流的关闭由调用方（[convertMany] 的 finally）统一负责、**恰好一次**；
     *   本函数只写 + 刷新 + 计数，避免与调用方重复关闭。
     *
     * 纯函数（仅依赖 java.io），可在普通 JVM 中单元测试。
     */
    internal fun writeWithCounting(
        output: OutputStream,
        writeAction: (OutputStream) -> Unit,
    ): Long {
        val countingStream = CountingOutputStream(output)
        try {
            writeAction(countingStream)
            countingStream.flush()
        } catch (e: Exception) {
            throw Exception("WRITE_FAILED: ${e.message}")
        }
        return countingStream.count
    }
}
