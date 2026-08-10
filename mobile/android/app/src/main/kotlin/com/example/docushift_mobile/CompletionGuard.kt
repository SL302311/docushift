package com.example.docushift_mobile

import java.util.concurrent.atomic.AtomicBoolean

/**
 * DocuShift — 恰好完成一次（exactly-once）完成保护。
 *
 * 无 Android 依赖的纯逻辑，可在普通 JVM 中单元测试。
 * 使用 [Runnable]（Java SAM 接口）便于 Kotlin 与 Java 双方直接调用。
 * 用于保证 [io.flutter.plugin.common.MethodChannel.Result] 在
 * 成功 / 失败 / 取消 / Activity 销毁 / 重复调用 等竞争下只完成一次。
 */
class CompletionGuard {

    private val _completed = AtomicBoolean(false)

    /**
     * 仅第一次调用会真正执行 [action]，后续调用安全忽略。
     * 返回 true 表示本次为首次完成，false 表示已被更早的完成占用。
     */
    fun complete(action: Runnable): Boolean {
        if (_completed.compareAndSet(false, true)) {
            action.run()
            return true
        }
        return false
    }

    /** 当前是否已经完成。 */
    fun isCompleted(): Boolean = _completed.get()
}
