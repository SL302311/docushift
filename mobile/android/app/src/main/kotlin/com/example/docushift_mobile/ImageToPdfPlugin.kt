package com.example.docushift_mobile

import android.app.Activity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * DocuShift 图片转 PDF — Flutter ↔ Android 平台通道。
 *
 * 第 3 期：pickImages 返回有序 (uri, name) 列表；convertAndSave 接收有序
 * imageUris 列表。委托 [ImageToPdfCoordinator] 处理实际 I/O 操作。
 *
 * 结构性门禁（缺失参数 / 空列表 / 元素非非空字符串 → INVALID_ARGS；
 * 超过 20 张 → TOO_MANY_FILES）在本层快速失败，深层校验交给 Coordinator/Validator。
 */
class ImageToPdfPlugin internal constructor(
    private val activity: Activity
) : MethodChannel.MethodCallHandler {

    companion object {
        private const val CHANNEL = "com.example.docushift_mobile/image_to_pdf"
        private const val METHOD_PICK = "pickImages"
        private const val METHOD_CONVERT = "convertAndSave"
        private const val METHOD_SHARE = "sharePdf"
        private const val PARAM_IMAGE_URIS = "imageUris"
        private const val PARAM_OUTPUT_URI = "outputUri"
        private const val MAX_IMAGE_COUNT = 20

        fun registerWith(flutterEngine: FlutterEngine, activity: Activity) {
            MethodChannel(
                flutterEngine.dartExecutor.binaryMessenger,
                CHANNEL
            ).setMethodCallHandler(ImageToPdfPlugin(activity))
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val coordinator = (activity as? MainActivity)?.coordinator
        if (coordinator == null) {
            result.error("NO_COORDINATOR", "Activity 未初始化", null)
            return
        }

        when (call.method) {
            METHOD_PICK -> coordinator.pickImages(result)
            METHOD_CONVERT -> {
                val raw = call.argument<List<*>>(PARAM_IMAGE_URIS)
                if (raw == null || raw.isEmpty()) {
                    result.error("INVALID_ARGS", "缺少 imageUris 参数或为空", null)
                    return
                }
                if (raw.size > MAX_IMAGE_COUNT) {
                    result.error("TOO_MANY_FILES", "最多选择 $MAX_IMAGE_COUNT 张图片", null)
                    return
                }
                val uris = mutableListOf<String>()
                for (element in raw) {
                    if (element !is String || element.isEmpty()) {
                        result.error("INVALID_ARGS", "imageUris 元素必须是非空字符串", null)
                        return
                    }
                    uris.add(element)
                }
                coordinator.convertAndSave(uris, result)
            }
            METHOD_SHARE -> {
                // 先取原始值再显式类型判断：call.argument<String> 对非 String 抛
                // ClassCastException 而非返回 null，无法稳定返回 INVALID_ARGS。
                val raw = call.argument<Any>(PARAM_OUTPUT_URI)
                if (raw == null || raw !is String || raw.isBlank()) {
                    result.error("INVALID_ARGS", "缺少 outputUri 参数或为空", null)
                    return
                }
                coordinator.sharePdf(raw, result)
            }
            else -> result.notImplemented()
        }
    }
}
