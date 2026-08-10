package com.example.docushift_mobile

import android.app.Activity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * DocuShift PDF → PNG — Flutter ↔ Android 平台通道（第 4 期）。
 *
 * 三方法：`pickPdf` / `pickOutputDirectory` / `convertPdfToPng`。
 * 委托 [PdfToPngCoordinator] 处理实际 I/O 操作。
 *
 * 结构性门禁（缺失参数 / 空字符串 → INVALID_ARGS）在本层快速失败，
 * 深层校验（页数 / 大小 / 可打开性 / BUSY）交给 Coordinator/Validator。
 */
class PdfToPngPlugin internal constructor(
    private val activity: Activity
) : MethodChannel.MethodCallHandler {

    companion object {
        private const val CHANNEL = "com.example.docushift_mobile/pdf_to_png"
        private const val METHOD_PICK_PDF = "pickPdf"
        private const val METHOD_PICK_DIR = "pickOutputDirectory"
        private const val METHOD_CONVERT = "convertPdfToPng"
        private const val PARAM_PDF_URI = "pdfUri"
        private const val PARAM_DIR_URI = "directoryUri"
        private const val PARAM_START_PAGE = "startPage"
        private const val PARAM_END_PAGE = "endPage"
        private const val PARAM_RESOLUTION = "resolution"

        /**
         * 可选页码参数解析结果（密封类，区分三种情形）：
         * - [Missing]：参数缺失 → 调用方传 null 给协调器，走全页默认；
         * - [Present]：合法整数；
         * - [Invalid]：非整数（字符串/小数/布尔等）→ 已直接以 INVALID_ARGS 失败。
         */
        private sealed class PageArg {
            object Missing : PageArg()
            data class Present(val value: Int) : PageArg()
            object Invalid : PageArg()
        }

        /**
         * 解析可选页码参数：null → [Missing]（交给协调器按全页默认）；
         * Int → [Present]；其它类型（字符串/小数/布尔）→ 以 INVALID_ARGS 失败并返回 [Invalid]。
         */
        private fun parseOptionalPage(raw: Any?, name: String, result: MethodChannel.Result): PageArg {
            return when (raw) {
                null -> PageArg.Missing
                is Int -> PageArg.Present(raw)
                else -> {
                    result.error("INVALID_ARGS", "$name 必须为整数", null)
                    PageArg.Invalid
                }
            }
        }

        /**
         * 解析清晰度参数：Int 且 ∈ {96,144,216} → 返回该值；
         * Int 但不在白名单 → 以 INVALID_RASTER_RESOLUTION 失败并返回 null；
         * 非 Int（字符串/小数/布尔）→ 以 INVALID_ARGS 失败并返回 null。
         * 参数缺失/null 的情况由调用方在传入前过滤。
         */
        private fun parseResolution(raw: Any, result: MethodChannel.Result): Int? {
            return when (raw) {
                is Int -> {
                    if (RasterResolution.isValid(raw)) {
                        raw
                    } else {
                        result.error("INVALID_RASTER_RESOLUTION", "不支持的清晰度 $raw", null)
                        null
                    }
                }
                else -> {
                    result.error("INVALID_ARGS", "resolution 必须为整数", null)
                    null
                }
            }
        }

        fun registerWith(flutterEngine: FlutterEngine, activity: Activity) {
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                CHANNEL
            ).setMethodCallHandler(PdfToPngPlugin(activity))
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val coordinator = (activity as? MainActivity)?.pdfToPngCoordinator
        if (coordinator == null) {
            result.error("NO_COORDINATOR", "Activity 未初始化", null)
            return
        }

        when (call.method) {
            METHOD_PICK_PDF -> coordinator.pickPdf(result)
            METHOD_PICK_DIR -> coordinator.pickOutputDirectory(result)
            METHOD_CONVERT -> {
                val pdfUri = call.argument<String>(PARAM_PDF_URI)
                if (pdfUri.isNullOrEmpty()) {
                    result.error("INVALID_ARGS", "缺少 pdfUri 参数或为空", null)
                    return
                }
                val dirUri = call.argument<String>(PARAM_DIR_URI)
                if (dirUri.isNullOrEmpty()) {
                    result.error("INVALID_ARGS", "缺少 directoryUri 参数或为空", null)
                    return
                }
                // 导出范围（可选）：缺失则全页默认；非整数（如字符串/小数）视为非法参数。
                val startArg = parseOptionalPage(call.argument<Any>(PARAM_START_PAGE), "startPage", result)
                if (startArg is PageArg.Invalid) return
                val endArg = parseOptionalPage(call.argument<Any>(PARAM_END_PAGE), "endPage", result)
                if (endArg is PageArg.Invalid) return
                val startPage = (startArg as? PageArg.Present)?.value
                val endPage = (endArg as? PageArg.Present)?.value
                // 解析清晰度（第 8 期）：参数缺失/null → 协调器按默认 144 处理；
                // 参数存在但非法 → parseResolution 已调用 result.error，直接返回。
                val resolution: Int?
                val rawResolution = call.argument<Any>(PARAM_RESOLUTION)
                if (rawResolution == null) {
                    resolution = null
                } else {
                    resolution = parseResolution(rawResolution, result) ?: return
                }
                coordinator.convertPdfToPng(pdfUri, dirUri, result, startPage, endPage, resolution)
            }
            else -> result.notImplemented()
        }
    }
}
