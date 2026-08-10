package com.example.docushift_mobile

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/**
 * DocuShift PDF → PNG — 输入验证器。
 *
 * 在 Android 端真实执行门禁：MIME、文件名、文件大小、PDF 页数与可打开性。
 * 设计要点（便于无设备接线测试）：
 * - [resolveFileSize] 是内部可见的三段大小回退（OpenableColumns.SIZE →
 *   AssetFileDescriptor.length → ParcelFileDescriptor.statSize），可在 JVM 下用
 *   受控 ContentResolver 驱动真实代码路径。
 * - [rendererProbe] 为可注入的页数探测接缝：默认走真实 [PdfRenderer] 打开并读取
 *   pageCount（同时验证 PDF 可打开、非密码保护）；测试可注入受控实现（返回页数或抛错）。
 */
class PdfInputValidator(private val contentResolver: ContentResolver) {

    /**
     * 页数探测接缝。默认走真实 [PdfRenderer]；无设备测试可注入受控实现，
     * 返回页数（Int）或抛出（损坏/受密码保护/无法打开 → 映射 PDF_OPEN_FAILED）。
     */
    internal var rendererProbe: ((Uri) -> Int)? = null

    data class ValidationResult(
        val valid: Boolean,
        val displayName: String,
        val mimeType: String,
        val fileSizeBytes: Long,
        val pageCount: Int,
        val errorCode: String?,
        val errorMessage: String?,
    )

    companion object {
        private const val MAX_FILE_SIZE = 100L * 1024 * 1024   // 100 MiB
        private const val MAX_PAGE_COUNT = 20

        /** 从显示名去掉扩展名，用于输出子文件夹命名。 */
        internal fun baseName(displayName: String): String {
            val dot = displayName.lastIndexOf('.')
            return if (dot > 0) displayName.substring(0, dot) else displayName
        }
    }

    /**
     * 验证单个 PDF 输入，返回验证结果。
     * 门禁顺序：显示名 → MIME → 大小 → 页数（可打开性）。
     */
    fun validate(pdfUri: Uri): ValidationResult {
        val displayName = queryDisplayName(pdfUri)
        if (displayName.isNullOrEmpty()) {
            return ValidationResult(false, "", "", 0, 0, "UNSUPPORTED_FORMAT", "无法读取文件信息")
        }

        val mime = contentResolver.getType(pdfUri) ?: ""
        if (mime != "application/pdf") {
            return ValidationResult(false, displayName, mime, 0, 0,
                "UNSUPPORTED_FORMAT", "只支持 PDF，当前: $mime")
        }

        val fileSize = resolveFileSize(pdfUri)
        if (fileSize <= 0) {
            return ValidationResult(false, displayName, mime, fileSize, 0,
                "FILE_SIZE_UNKNOWN", "无法确定文件大小，可能已损坏或不受支持")
        }
        if (fileSize > MAX_FILE_SIZE) {
            return ValidationResult(false, displayName, mime, fileSize, 0,
                "FILE_TOO_LARGE", "文件超过 100 MiB 限制（实际: ${fileSize / 1024 / 1024} MiB）")
        }

        val pageCount = try {
            (rendererProbe ?: defaultProbe)(pdfUri)
        } catch (e: Exception) {
            return ValidationResult(false, displayName, mime, fileSize, 0,
                "PDF_OPEN_FAILED", "PDF 无法打开（可能损坏或受密码保护）")
        }
        if (pageCount < 1) {
            return ValidationResult(false, displayName, mime, fileSize, 0,
                "PDF_OPEN_FAILED", "PDF 不含任何页面")
        }
        if (pageCount > MAX_PAGE_COUNT) {
            return ValidationResult(false, displayName, mime, fileSize, pageCount,
                "TOO_MANY_PAGES", "PDF 页数超过 20（当前 $pageCount 页）")
        }

        return ValidationResult(true, displayName, mime, fileSize, pageCount, null, null)
    }

    private val defaultProbe: (Uri) -> Int = { uri ->
        val fd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开 PDF 文件描述符")
        val renderer = PdfRenderer(fd)
        try {
            renderer.pageCount
        } finally {
            renderer.close()
            try {
                fd.close()
            } catch (_: Exception) {
                // PdfRenderer.close 可能已关闭底层 fd；忽略重复关闭异常
            }
        }
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
     */
    internal fun resolveFileSize(uri: Uri): Long {
        var size = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0) {
                    val s = cursor.getLong(sizeIdx)
                    if (s > 0) size = s
                }
            }
        }

        if (size <= 0) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len > 0) size = len
                }
            } catch (_: Exception) {}
        }

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
}

/**
 * 单个已验证 PDF 的不可变元数据。
 * 在请求转换前完成验证，形成后进入请求，避免后续重新猜测名称或页数。
 */
data class ValidatedPdf(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
)
