package com.example.docushift_mobile

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicReference

/**
 * DocuShift 图片转 PDF — Android 端操作协调器。
 *
 * 第 3 期：支持一次选择 1—20 张图片，按序合并为多页 PDF。
 * - 选择器改用 [ActivityResultContracts.OpenMultipleDocuments]。
 * - 请求改为不可变 [ValidatedImage] 列表；选择/保存/后台转换继续遵守 single-flight、
 *   销毁竞争、恰好完成一次。
 * - 打开保存面板前依次验证全部输入（MIME/显示名/大小/像素/数量/200MiB 总量），
 *   形成不可变已验证列表进入请求。
 * - 转换或写入失败后尽力清理已创建的输出 URI；清理失败不覆盖原始错误。
 *
 * 为无设备接线测试预留可注入接缝（resolver / convertExecutor / saveLauncher /
 * validateProvider / uriParser / outputDeleter）；默认走真实实现。
 */
open class ImageToPdfCoordinator(private val activity: FlutterFragmentActivity? = null) {

    lateinit var pickImagesLauncher: ActivityResultLauncher<Array<String>>
        internal set
    lateinit var createDocLauncher: ActivityResultLauncher<String>
        internal set

    // ================================================================
    // 可注入接缝（测试用；默认走真实实现）
    // ================================================================

    /** 内容解析器。默认取 activity.contentResolver，测试时直接注入。 */
    internal var resolver: ContentResolver?
        get() = _testResolver ?: activity?.contentResolver
        set(value) { _testResolver = value }
    private var _testResolver: ContentResolver? = null

    /** 转换执行器，默认调用真实 [ImageToPdfConverter.convertMany]。 */
    internal fun interface ConvertExecutor {
        fun convert(images: List<ValidatedImage>, outputUri: Uri): Pair<String, Long>
    }
    internal var convertExecutor: ConvertExecutor = ConvertExecutor { images, output ->
        ImageToPdfConverter.convertMany(activity!!, images, output)
    }

    /** 保存位置启动器；返回 null 表示用户取消。测试时直接注入以驱动同步流程。 */
    internal fun interface SaveLauncher {
        fun launch(suggestedName: String): Uri?
    }
    internal var saveLauncher: SaveLauncher? = null

    /** 可选注入的批量校验器；默认用 ImageInputValidator(resolver).validateMany。 */
    internal var validateProvider: ((List<Uri>) -> ManyValidationResult)? = null

    /**
     * Uri 解析接缝。默认走真实 [Uri.parse]；
     * 纯 JVM 测试下 android.jar 为桩（Uri.parse 返回 null），需注入受控 Uri。
     */
    internal var uriParser: (String) -> Uri = { Uri.parse(it) }

    /**
     * 输出清理接缝；转换/写入失败时尽力调用。
     * 默认走生产实现：通过真实 [resolver] 删除已创建的输出（SAF CreateDocument 返回的 URI）。
     * 删除失败仅返回 false，不抛出、不覆盖原始转换错误。
     */
    internal var outputDeleter: ((Uri) -> Boolean)? = { uri ->
        runCatching { resolver?.delete(uri, null, null) ?: 0 }.getOrDefault(0) > 0
    }

    /**
     * 分享启动器（第 9 期）：构建显式 Intent 后经 Activity 启动系统 chooser。
     * 测试可注入以验证 intent action / MIME / EXTRA_STREAM / ClipData / flag 而无需
     * 实际弹出系统面板。生产实现走 [realShareLauncher]。
     */
    internal var shareLauncher: ((Intent) -> Unit)? = null

    /** 生产分享启动器：通过 Activity 启动 chooser。 */
    private fun realShareLauncher(intent: Intent) {
        val title = android.content.Intent.createChooser(intent, "分享 PDF")
        activity?.startActivity(title)
    }

    // ================================================================
    // 启动器注册（仅设备路径使用）
    // ================================================================

    fun registerLaunchers() {
        requireNotNull(activity) { "registerLaunchers 需要 activity" }
        pickImagesLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri>? ->
            pickSettle(uris)
        }

        createDocLauncher = activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { outputUri: Uri? ->
            convertSettle(outputUri)
        }
    }

    // ================================================================
    // pickImages
    // ================================================================

    private val _pendingPick = AtomicReference<MethodChannel.Result?>()

    open fun pickImages(result: MethodChannel.Result) {
        if (!_pendingPick.compareAndSet(null, result)) {
            result.error("BUSY", "上一个图片选择尚未完成", null)
            return
        }
        pickImagesLauncher.launch(arrayOf("image/*"))
    }

    private fun pickSettle(uris: List<Uri>?) {
        val result = _pendingPick.getAndSet(null) ?: return
        if (uris == null) {
            // 用户取消选择 → 返回 null（Flutter 保持原列表）
            result.success(null)
            return
        }
        val resolver = resolver ?: run {
            result.error("NO_RESOLVER", "ContentResolver 未提供", null)
            return
        }
        val validator = ImageInputValidator(resolver)
        val list = uris.map { uri ->
            mapOf(
                "uri" to uri.toString(),
                "name" to (validator.queryDisplayName(uri) ?: ""),
            )
        }
        result.success(list)
    }

    // ================================================================
    // convertAndSave
    // ================================================================

    private class ConvertRequest(
        val result: MethodChannel.Result,
        val images: List<ValidatedImage>,
        val guard: CompletionGuard = CompletionGuard(),
    )

    private val _pendingConvert = AtomicReference<ConvertRequest?>()

    open fun convertAndSave(imageUris: List<String>, result: MethodChannel.Result) {
        val uris = imageUris.map { uriParser(it) }

        // 打开保存面板前依次验证全部输入（形成不可变已验证列表）
        val many = validateProvider?.invoke(uris)
            ?: ImageInputValidator(resolver ?: error("ContentResolver 未提供")).validateMany(uris)
        if (!many.valid) {
            // 稳定错误码 + 人类消息分离；消息含失败序号与显示名
            result.error(many.errorCode!!, many.errorMessage, null)
            return
        }

        val request = ConvertRequest(result, many.images)
        if (!_pendingConvert.compareAndSet(null, request)) {
            result.error("BUSY", "上一个转换操作尚未完成", null)
            return
        }

        val pdfName = suggestedName(many.images)

        // 测试路径：直接注入保存位置并同步进入转换结算
        val out = saveLauncher?.launch(pdfName)
        if (out == null) {
            // 设备路径：真实 CreateDocument launcher 异步返回后回调 convertSettle
            createDocLauncher.launch(pdfName)
            return
        }
        convertSettle(out)
    }

    /** 根据已验证图片列表生成建议保存名。 */
    private fun suggestedName(images: List<ValidatedImage>): String {
        val firstName = images.firstOrNull()?.displayName ?: "docushift"
        val base = firstName.replace(Regex("\\.(?i)(png|jpe?g|bmp)$"), "")
        return if (images.size <= 1) {
            "$base.pdf"
        } else {
            "${base}_合并_${images.size}页.pdf"
        }
    }

    private fun convertSettle(outputUri: Uri?) {
        // 请求保留到后台工作真正完成（见 CompletionGuard），
        // 不在此处清空，否则第二次转换会被放行且 onDestroy 无法接管。
        val request = _pendingConvert.get() ?: return

        if (outputUri == null) {
            // 用户取消保存 → 恢复 selected 状态（成功返回 null）
            settle(request) { it.success(null) }
            return
        }

        val images = request.images
        val methodResult = request.result

        // 后台转换 — methodResult 是局部不可变引用，线程闭包捕获它
        Thread {
            try {
                val (path, size) = convertExecutor.convert(images, outputUri)
                settle(request) { it.success(mapOf("path" to path, "size" to size)) }
            } catch (e: Exception) {
                val code = extractErrorCode(e.message ?: "UNKNOWN")
                // 尽力清理已创建的输出，清理失败不覆盖原始转换错误
                try { outputDeleter?.invoke(outputUri) } catch (_: Exception) { }
                settle(request) { it.error(code, e.message, null) }
            }
        }.start()
    }

    /**
     * 保证 [MethodChannel.Result] 恰好完成一次。
     * 通过 [CompletionGuard] 在 成功 / 失败 / 取消 / 销毁 之间竞争时只生效一次；
     * 清理挂起请求时仅当仍是同一请求才清除。
     */
    private fun settle(request: ConvertRequest, action: (MethodChannel.Result) -> Unit) {
        val won = request.guard.complete { action(request.result) }
        if (won) {
            _pendingConvert.compareAndSet(request, null)
        }
    }

    // ================================================================
    // sharePdf（第 9 期）
    // ================================================================

    /**
     * 将已生成的 PDF 通过系统分享面板发送。
     *
     * - 仅接受 `content://` scheme 的 URI；file://、http(s):// 或裸路径返回 INVALID_OUTPUT_URI；
     * - 空/空白 URI 返回 INVALID_ARGS；
     * - 构建 `ACTION_SEND` + `application/pdf` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`
     *   + `ClipData`，经 [shareLauncher]（默认 [realShareLauncher]）启动系统 chooser；
     * - `ActivityNotFoundException` 或普通启动异常映射为 SHARE_UNAVAILABLE；
     * - 不复制文件、不持久化授权、不干扰转换或输出清理。
     */
    open fun sharePdf(outputUri: String, result: MethodChannel.Result) {
        if (outputUri.isBlank()) {
            result.error("INVALID_ARGS", "缺少 outputUri 参数或为空", null)
            return
        }

        val uri = uriParser(outputUri)
        val scheme = uri.scheme ?: ""
        if (scheme != "content") {
            result.error(
                "INVALID_OUTPUT_URI",
                "不支持的 URI scheme（$scheme）：仅支持 content://",
                null,
            )
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // ClipData 是分享面板显示元数据的增强；其构造异常不影响 Intent 其他属性。
        try {
            intent.clipData = android.content.ClipData.newRawUri(null, uri)
        } catch (_: Exception) {
            // 在桩/异常 URI 环境中忽略，保持 Intent 可启动
        }

        try {
            val launcher = shareLauncher ?: ::realShareLauncher
            launcher(intent)
            // 启动成功仅表示已交给系统 chooser，不等于用户已发送
            result.success(null)
        } catch (e: android.content.ActivityNotFoundException) {
            result.error("SHARE_UNAVAILABLE", "没有可处理 PDF 分享的应用", null)
        } catch (e: Exception) {
            result.error("SHARE_UNAVAILABLE", "启动分享面板失败：${e.message}", null)
        }
    }

    // ================================================================
    // 生命周期
    // ================================================================

    fun onDestroy() {
        _pendingPick.getAndSet(null)?.error("DESTROYED", "Activity 已销毁", null)
        val request = _pendingConvert.getAndSet(null)
        if (request != null) {
            settle(request) { it.error("DESTROYED", "Activity 已销毁", null) }
        }
    }

    companion object {
        /** 从异常消息中提取稳定错误码（去掉中文消息后缀）。 */
        fun extractErrorCode(message: String): String {
            return when {
                message.startsWith("DECODE_FAILED") -> "DECODE_FAILED"
                message.startsWith("WRITE_FAILED") -> "WRITE_FAILED"
                message.startsWith("ADD_PAGE_FAILED") -> "ADD_PAGE_FAILED"
                message.startsWith("UNSUPPORTED_FORMAT") -> "UNSUPPORTED_FORMAT"
                message.startsWith("IMAGE_TOO_LARGE") -> "IMAGE_TOO_LARGE"
                message.startsWith("FILE_TOO_LARGE") -> "FILE_TOO_LARGE"
                message.startsWith("FILE_SIZE_UNKNOWN") -> "FILE_SIZE_UNKNOWN"
                message.startsWith("TOO_MANY_FILES") -> "TOO_MANY_FILES"
                message.startsWith("TOTAL_SIZE_TOO_LARGE") -> "TOTAL_SIZE_TOO_LARGE"
                message.startsWith("INVALID_ARGS") -> "INVALID_ARGS"
                message.startsWith("INVALID_OUTPUT_URI") -> "INVALID_OUTPUT_URI"
                message.startsWith("SHARE_UNAVAILABLE") -> "SHARE_UNAVAILABLE"
                else -> "CONVERSION_FAILED"
            }
        }
    }
}
