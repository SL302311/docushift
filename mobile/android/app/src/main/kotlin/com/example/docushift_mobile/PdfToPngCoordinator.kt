package com.example.docushift_mobile

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference

/**
 * DocuShift PDF → PNG — Android 端操作协调器（第 4 期）。
 *
 * 三方法契约（镜像 [ImageToPdfCoordinator] 模式）：
 * - [pickPdf]：`OpenDocument` 限定 `application/pdf`；选中后立即用 [PdfInputValidator]
 *   完成全部输入验证（MIME/显示名/大小/页数/可打开性），返回原生元数据
 *   `{uri, name, pageCount, size}`；用户取消返回 null。
 * - [pickOutputDirectory]：`OpenDocumentTree` 获取可写输出树 URI，尽力保留本次授权
 *   （takePersistableUriPermission）；用户取消返回 null，不创建任何文件。
 * - [convertPdfToPng]：进入后台转换前再次验证（防止选择后文件变化），后台线程执行
 *   [PdfToPngConverter.convert]；成功返回 `{directoryUri, pageCount, size}`。
 *
 * 并发与生命周期：
 * - 三个操作各自 [AtomicReference] single-flight；重复请求 → BUSY。
 * - 转换请求保留至后台工作真正完成，[CompletionGuard] 保证 成功/失败/取消/销毁
 *   之间竞争时 [MethodChannel.Result] 恰好完成一次。
 * - 失败时按相反顺序尽力删除本次新建的页文件与输出子文件夹（createdSink），
 *   清理异常不覆盖原始错误。
 *
 * 为无设备接线测试预留可注入接缝（resolver / convertExecutor / pdfPicker /
 * treePicker / validateProvider / uriParser / outputDeleter / permissionTaker）；
 * 默认走真实实现。
 */
open class PdfToPngCoordinator(private val activity: FlutterFragmentActivity? = null) {

    lateinit var pickPdfLauncher: ActivityResultLauncher<Array<String>>
        internal set
    lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>
        internal set

    // ================================================================
    // 可注入接缝（测试用；默认走真实实现）
    // ================================================================

    /** 内容解析器。默认取 activity.contentResolver，测试时直接注入。 */
    internal var resolver: ContentResolver?
        get() = _testResolver ?: activity?.contentResolver
        set(value) { _testResolver = value }
    private var _testResolver: ContentResolver? = null

    /** 转换执行器，默认调用真实 [PdfToPngConverter.convert]；createdSink 由本协调器持有。 */
    internal fun interface ConvertExecutor {
        fun convert(params: PdfToPngConverter.ConvertParams, createdSink: MutableList<Uri>): Pair<String, Long>
    }
    internal var convertExecutor: ConvertExecutor = ConvertExecutor { params, sink ->
        PdfToPngConverter.convert(
            resolver ?: error("ContentResolver 未提供"),
            params,
            sink,
        )
    }

    /** PDF 选择接缝；返回 null 表示走真实 launcher（异步）。测试注入以驱动同步流程。 */
    internal fun interface PdfPicker {
        fun launch(): Uri?
    }
    internal var pdfPicker: PdfPicker? = null

    /** 输出目录选择接缝；返回 null 表示走真实 launcher（异步）。测试注入以驱动同步流程。 */
    internal fun interface TreePicker {
        fun launch(): Uri?
    }
    internal var treePicker: TreePicker? = null

    /** 可选注入的 PDF 校验器；默认用 PdfInputValidator(resolver).validate。 */
    internal var validateProvider: ((Uri) -> PdfInputValidator.ValidationResult)? = null

    /**
     * Uri 解析接缝。默认走真实 [Uri.parse]；
     * 纯 JVM 测试下 android.jar 为桩（Uri.parse 返回 null），需注入受控 Uri。
     */
    internal var uriParser: (String) -> Uri = { Uri.parse(it) }

    /**
     * 输出清理接缝；转换/写入失败时按相反顺序尽力调用。
     * 默认走生产实现：SAF 文档用 [DocumentsContract.deleteDocument] 删除。
     * 删除失败仅返回 false，不抛出、不覆盖原始转换错误。
     */
    internal var outputDeleter: ((Uri) -> Boolean)? = { uri ->
        runCatching {
            DocumentsContract.deleteDocument(resolver ?: error("no resolver"), uri)
        }.getOrDefault(false)
    }

    /** 授权保留接缝；默认对树 URI 尽力 takePersistableUriPermission（读+写）。 */
    internal var permissionTaker: ((Uri) -> Unit)? = { uri ->
        runCatching {
            resolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    // ================================================================
    // 启动器注册（仅设备路径使用）
    // ================================================================

    fun registerLaunchers() {
        requireNotNull(activity) { "registerLaunchers 需要 activity" }
        pickPdfLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            pdfPickSettle(uri)
        }

        openTreeLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { treeUri: Uri? ->
            treeSettle(treeUri)
        }
    }

    // ================================================================
    // pickPdf
    // ================================================================

    private val _pendingPick = AtomicReference<MethodChannel.Result?>()

    open fun pickPdf(result: MethodChannel.Result) {
        if (!_pendingPick.compareAndSet(null, result)) {
            result.error("BUSY", "上一个 PDF 选择尚未完成", null)
            return
        }
        // 测试路径：同步注入选择结果
        val picker = pdfPicker
        if (picker != null) {
            pdfPickSettle(picker.launch())
            return
        }
        pickPdfLauncher.launch(arrayOf("application/pdf"))
    }

    internal fun pdfPickSettle(uri: Uri?) {
        val result = _pendingPick.getAndSet(null) ?: return
        if (uri == null) {
            // 用户取消选择 → 返回 null（Flutter 保留当前状态）
            result.success(null)
            return
        }
        // 全部输入验证在请求输出目录之前完成；仅默认校验路径需要 resolver
        val v = validateProvider?.invoke(uri) ?: run {
            val res = resolver ?: run {
                result.error("NO_RESOLVER", "ContentResolver 未提供", null)
                return
            }
            PdfInputValidator(res).validate(uri)
        }
        if (!v.valid) {
            result.error(v.errorCode!!, v.errorMessage, null)
            return
        }
        result.success(
            mapOf(
                "uri" to uri.toString(),
                "name" to v.displayName,
                "pageCount" to v.pageCount,
                "size" to v.fileSizeBytes,
            )
        )
    }

    // ================================================================
    // pickOutputDirectory
    // ================================================================

    private val _pendingTree = AtomicReference<MethodChannel.Result?>()

    open fun pickOutputDirectory(result: MethodChannel.Result) {
        if (!_pendingTree.compareAndSet(null, result)) {
            result.error("BUSY", "上一个目录选择尚未完成", null)
            return
        }
        val picker = treePicker
        if (picker != null) {
            treeSettle(picker.launch())
            return
        }
        openTreeLauncher.launch(null)
    }

    internal fun treeSettle(treeUri: Uri?) {
        val result = _pendingTree.getAndSet(null) ?: return
        if (treeUri == null) {
            // 用户取消目录选择 → 返回 null，不创建任何文件
            result.success(null)
            return
        }
        // 尽力保留本次授权；失败不阻断（本次会话内授权仍有效）
        try {
            permissionTaker?.invoke(treeUri)
        } catch (_: Exception) {
        }
        result.success(treeUri.toString())
    }

    // ================================================================
    // convertPdfToPng
    // ================================================================

    private class ConvertRequest(
        val result: MethodChannel.Result,
        val params: PdfToPngConverter.ConvertParams,
        val createdSink: MutableList<Uri> = mutableListOf(),
        val guard: CompletionGuard = CompletionGuard(),
    )

    private val _pendingConvert = AtomicReference<ConvertRequest?>()

    open fun convertPdfToPng(
        pdfUri: String,
        directoryUri: String,
        result: MethodChannel.Result,
        startPage: Int? = null,
        endPage: Int? = null,
        resolution: Int? = null,
    ) {
        val pdf = uriParser(pdfUri)
        val tree = uriParser(directoryUri)

        // 转换前再次验证（防止选择后文件被替换/删除；同时拿到不可变元数据）
        val v = validateProvider?.invoke(pdf)
            ?: PdfInputValidator(resolver ?: error("ContentResolver 未提供")).validate(pdf)
        if (!v.valid) {
            result.error(v.errorCode!!, v.errorMessage, null)
            return
        }

        // 规范化并校验导出范围（1-based 闭区间）；缺失则默认全页 [1, total]。
        // 不信任上游参数——在创建任何输出之前完成校验，非法范围不创建输出。
        val total = v.pageCount
        val s = startPage ?: 1
        val e = endPage ?: total
        if (s < 1 || e > total || s > e) {
            result.error(
                "INVALID_PAGE_RANGE",
                "页码范围不合法（起始 $s，结束 $e，PDF 共 $total 页，${v.displayName}）",
                null,
            )
            return
        }

        // 清晰度校验（第 8 期）：必须在创建任何输出之前完成。
        // 缺失/null → 默认 144；非 96/144/216 → INVALID_RASTER_RESOLUTION。
        val dpi = resolution ?: RasterResolution.DEFAULT.dpi
        val rasterRes = RasterResolution.fromDpi(dpi)
        if (rasterRes == null) {
            result.error(
                "INVALID_RASTER_RESOLUTION",
                "不支持的清晰度 $dpi",
                null,
            )
            return
        }

        val request = ConvertRequest(
            result,
            PdfToPngConverter.ConvertParams(
                pdfUri = pdf,
                outputTreeUri = tree,
                displayName = v.displayName,
                pageCount = total,
                startPage = s,
                endPage = e,
                resolution = rasterRes,
            ),
        )
        if (!_pendingConvert.compareAndSet(null, request)) {
            result.error("BUSY", "上一个转换操作尚未完成", null)
            return
        }

        // 后台转换 — 请求保留到后台工作真正完成（见 CompletionGuard）
        Thread {
            try {
                val (folderUri, totalBytes) = convertExecutor.convert(request.params, request.createdSink)
                settle(request) {
                    it.success(
                        mapOf(
                            "directoryUri" to folderUri,
                            // 成功结果中的 pageCount 为本次实际导出的页数
                            "pageCount" to request.params.endPage - request.params.startPage + 1,
                            "size" to totalBytes,
                        )
                    )
                }
            } catch (e: Exception) {
                val code = extractErrorCode(e.message ?: "UNKNOWN")
                // 按相反顺序尽力清理本次新建的页文件与输出子文件夹；
                // 清理失败不覆盖原始转换错误。
                cleanupCreated(request.createdSink)
                settle(request) { it.error(code, e.message, null) }
            }
        }.start()
    }

    /** 按相反顺序尽力删除本次新建的 URI；任何清理异常吞掉，不影响原始错误。 */
    private fun cleanupCreated(created: List<Uri>) {
        for (uri in created.asReversed()) {
            try {
                outputDeleter?.invoke(uri)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 保证 [MethodChannel.Result] 恰好完成一次。
     * [CompletionGuard] 在 成功 / 失败 / 销毁 之间竞争时只生效一次；
     * 清理挂起请求时仅当仍是同一请求才清除。
     */
    private fun settle(request: ConvertRequest, action: (MethodChannel.Result) -> Unit) {
        val won = request.guard.complete { action(request.result) }
        if (won) {
            _pendingConvert.compareAndSet(request, null)
        }
    }

    // ================================================================
    // 生命周期
    // ================================================================

    fun onDestroy() {
        _pendingPick.getAndSet(null)?.error("DESTROYED", "Activity 已销毁", null)
        _pendingTree.getAndSet(null)?.error("DESTROYED", "Activity 已销毁", null)
        val request = _pendingConvert.getAndSet(null)
        if (request != null) {
            settle(request) { it.error("DESTROYED", "Activity 已销毁", null) }
        }
    }

    companion object {
        /** 从异常消息中提取稳定错误码（去掉中文消息后缀）。 */
        fun extractErrorCode(message: String): String {
            return when {
                message.startsWith("PDF_OPEN_FAILED") -> "PDF_OPEN_FAILED"
                message.startsWith("TOO_MANY_PAGES") -> "TOO_MANY_PAGES"
                message.startsWith("OUTPUT_DIR_UNAVAILABLE") -> "OUTPUT_DIR_UNAVAILABLE"
                message.startsWith("PAGE_RENDER_FAILED") -> "PAGE_RENDER_FAILED"
                message.startsWith("OUTPUT_WRITE_FAILED") -> "OUTPUT_WRITE_FAILED"
                message.startsWith("UNSUPPORTED_FORMAT") -> "UNSUPPORTED_FORMAT"
                message.startsWith("FILE_TOO_LARGE") -> "FILE_TOO_LARGE"
                message.startsWith("FILE_SIZE_UNKNOWN") -> "FILE_SIZE_UNKNOWN"
                message.startsWith("INVALID_ARGS") -> "INVALID_ARGS"
                message.startsWith("INVALID_PAGE_RANGE") -> "INVALID_PAGE_RANGE"
                message.startsWith("INVALID_RASTER_RESOLUTION") -> "INVALID_RASTER_RESOLUTION"
                else -> "CONVERSION_FAILED"
            }
        }
    }
}
