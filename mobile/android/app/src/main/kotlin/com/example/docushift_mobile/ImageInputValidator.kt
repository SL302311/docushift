package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns

/**
 * DocuShift 图片转 PDF — 输入验证器。
 *
 * 在 Android 端真实执行门禁：MIME、文件名、文件大小、解码后像素数。
 *
 * 设计要点（便于无设备接线测试）：
 * - [resolveFileSize] 是内部可见的三段大小回退（OpenableColumns.SIZE →
 *   AssetFileDescriptor.length → ParcelFileDescriptor.statSize），可在 JVM 下用
 *   受控 ContentResolver 驱动真实代码路径。
 * - [classifyPixelCount] 为纯函数，覆盖 -1×-1、零尺寸、4000 万像素边界与超大尺寸。
 * - [estimatePixelError] 在边界解码异常或尺寸非法时返回 DECODE_FAILED，
 *   不再以 0 像素放行损坏图片。
 */
class ImageInputValidator(private val contentResolver: ContentResolver) {

    /**
     * 边界解码接缝。默认走真实 [android.graphics.BitmapFactory] 解码；
     * 无设备测试可注入受控宽高，绕过 android.jar 桩（`BitmapFactory` 抛 `Stub!`）。
     */
    internal var boundsProvider: ((Uri) -> Pair<Int, Int>?)? = null

    data class ValidationResult(
        val valid: Boolean,
        val displayName: String,
        val mimeType: String,
        val fileSizeBytes: Long,
        val errorCode: String?,
        val errorMessage: String?,
    )

    companion object {
        private const val MAX_FILE_SIZE = 30L * 1024 * 1024   // 30 MiB
        private val ALLOWED_MIME = setOf("image/png", "image/jpeg", "image/bmp", "image/x-ms-bmp")

        // 4000 万像素 = 40000000
        private const val MAX_PIXEL_COUNT = 40_000_000L

        // 安全采样边界
        private const val MAX_DECODE_DIMENSION = 4096

        // 第 3 期：多图门禁
        private const val MAX_IMAGE_COUNT = 20
        private const val MAX_TOTAL_SIZE = 200L * 1024 * 1024   // 200 MiB

        /** 根据原始尺寸计算安全采样率。 */
        fun calculateSampleSize(origW: Int, origH: Int): Int {
            val maxDim = if (origW > origH) origW else origH
            var sample = 1
            // 用 Long 比较避免 maxDim / sample 整数截断
            while (maxDim.toLong() > MAX_DECODE_DIMENSION.toLong() * sample.toLong()) {
                sample *= 2
                // 溢出保护
                if (sample >= 65536) break
            }
            return sample
        }

        /**
         * 纯函数：根据解码后的宽高判定像素门禁。
         * @return 错误码（DECODE_FAILED / IMAGE_TOO_LARGE）或 null 表示通过。
         */
        internal fun classifyPixelCount(w: Int, h: Int): String? {
            if (w <= 0 || h <= 0) return "DECODE_FAILED"
            val pixels = w.toLong() * h.toLong()
            if (pixels > MAX_PIXEL_COUNT) return "IMAGE_TOO_LARGE"
            return null
        }
    }

    /**
     * 验证输入图片，返回验证结果。
     * 如果 valid == false，在 errorMessage 中提供错误描述。
     */
    fun validate(inputUri: Uri): ValidationResult {
        // 1. 文件名
        val displayName = queryDisplayName(inputUri)
        if (displayName.isNullOrEmpty()) {
            return ValidationResult(false, "", "", 0, "UNSUPPORTED_FORMAT", "无法读取文件信息")
        }

        // 2. MIME
        val mime = contentResolver.getType(inputUri) ?: ""
        if (mime !in ALLOWED_MIME) {
            return ValidationResult(false, displayName, mime, 0,
                "UNSUPPORTED_FORMAT", "只支持 PNG、JPEG 和 BMP，当前: $mime")
        }

        // 3. 文件大小（三段回退；未知则不放行）
        val fileSize = resolveFileSize(inputUri)
        if (fileSize <= 0) {
            return ValidationResult(false, displayName, mime, fileSize,
                "FILE_SIZE_UNKNOWN", "无法确定文件大小，可能已损坏或不受支持")
        }
        if (fileSize > MAX_FILE_SIZE) {
            return ValidationResult(false, displayName, mime, fileSize,
                "FILE_TOO_LARGE", "文件超过 30 MiB 限制（实际: ${fileSize / 1024 / 1024} MiB）")
        }

        // 4. 像素门禁（损坏图片 → DECODE_FAILED，不再以 0 像素放行）
        val pixelError = estimatePixelError(inputUri)
        if (pixelError != null) {
            return ValidationResult(false, displayName, mime, fileSize,
                pixelError, "图片解码失败或尺寸非法（${pixelError}）")
        }

        return ValidationResult(true, displayName, mime, fileSize, null, null)
    }

    /**
     * 校验多图输入（在打开保存面板前依次验证全部输入）。
     *
     * 覆盖：空列表（INVALID_ARGS）、超过 [MAX_IMAGE_COUNT]（TOO_MANY_FILES）、
     * 单文件 MIME/大小/像素门禁（沿用 [validate]）、以及全部图片累计总大小超过
     * [MAX_TOTAL_SIZE]（TOTAL_SIZE_TOO_LARGE）。
     *
     * 验证结果形成不可变 [ValidatedImage] 列表，进入请求后不再重新猜测名称或顺序。
     *
     * @return [ManyValidationResult]，valid 为 false 时 errorCode/errorMessage/failedIndex 描述首个失败。
     */
    fun validateMany(uris: List<Uri>): ManyValidationResult {
        if (uris.isEmpty()) {
            return ManyValidationResult(false, emptyList(), "INVALID_ARGS", "未选择图片", null)
        }
        if (uris.size > MAX_IMAGE_COUNT) {
            return ManyValidationResult(
                false, emptyList(), "TOO_MANY_FILES",
                "最多选择 $MAX_IMAGE_COUNT 张图片（当前 ${uris.size} 张）", null,
            )
        }

        val images = mutableListOf<ValidatedImage>()
        var totalSize: Long = 0
        for ((index, uri) in uris.withIndex()) {
            val r = validate(uri)
            if (!r.valid) {
                return ManyValidationResult(
                    false, emptyList(), r.errorCode,
                    "第 ${index + 1} 张（${r.displayName}）：${r.errorMessage}", index,
                )
            }
            images.add(ValidatedImage(uri, r.displayName, r.mimeType, r.fileSizeBytes))
            totalSize += r.fileSizeBytes
            if (totalSize > MAX_TOTAL_SIZE) {
                return ManyValidationResult(
                    false, emptyList(), "TOTAL_SIZE_TOO_LARGE",
                    "已选图片总大小超过 200 MiB（第 ${index + 1} 张后超限）", index,
                )
            }
        }
        return ManyValidationResult(true, images.toList(), null, null, null)
    }

    internal fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
            }
        }
        return name
    }

    /**
     * 三段回退解析文件大小，返回字节数；三者均缺失/为负时返回 0（表示未知）。
     *
     * 1. OpenableColumns.SIZE
     * 2. AssetFileDescriptor.length
     * 3. ParcelFileDescriptor.statSize
     */
    internal fun resolveFileSize(uri: Uri): Long {
        var size = 0L

        // 回退 1：OpenableColumns.SIZE
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0) {
                    val s = cursor.getLong(sizeIdx)
                    if (s > 0) size = s
                }
            }
        }

        // 回退 2：AssetFileDescriptor.length
        if (size <= 0) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len > 0) size = len
                }
            } catch (_: Exception) {}
        }

        // 回退 3：ParcelFileDescriptor.statSize
        if (size <= 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val len = pfd.statSize
                    if (len > 0) size = len
                }
            } catch (_: Exception) {}
        }

        return size
    }

    /**
     * 解码边界尺寸（不加载像素）。
     * @return 解码成功且尺寸合法返回 `(宽, 高)`；否则 `null`（损坏或异常）。
     */
    internal fun decodeBounds(uri: Uri): Pair<Int, Int>? {
        val provider = boundsProvider
        if (provider != null) return provider(uri)
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) null
            else Pair(opts.outWidth, opts.outHeight)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 边界解码判定像素合法性。
     *
     * 解码出的宽高必须经由 [classifyPixelCount] 判定：
     * 非正尺寸 → `DECODE_FAILED`；超过 4000 万像素 → `IMAGE_TOO_LARGE`。
     * 这是 4000 万像素上限接入生产路径的关键——此前只判断宽高是否为正，
     * 导致任意超限图片被放行。
     *
     * @return null 表示可解码且尺寸合法；否则返回错误码。
     */
    internal fun estimatePixelError(uri: Uri): String? {
        val bounds = decodeBounds(uri) ?: return "DECODE_FAILED"
        return classifyPixelCount(bounds.first, bounds.second)
    }
}

/**
 * 单个已验证图片的不可变元数据。
 *
 * 在打开保存面板前完成验证，形成后进入请求，避免后续重新猜测名称或顺序。
 */
data class ValidatedImage(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
)

/**
 * 多图验证结果。
 *
 * @param valid        是否全部通过。
 * @param images       通过验证的有序图片列表（valid 为 true 时有效）。
 * @param errorCode    稳定错误码（valid 为 false 时有效）。
 * @param errorMessage 人类可读错误描述（含失败图片序号与显示名）。
 * @param failedIndex  首个失败图片在输入列表中的下标（从 0 开始）。
 */
data class ManyValidationResult(
    val valid: Boolean,
    val images: List<ValidatedImage>,
    val errorCode: String?,
    val errorMessage: String?,
    val failedIndex: Int?,
)
