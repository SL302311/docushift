package com.example.docushift_mobile

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 受控 MethodChannel.Result —— 记录 success / error 并支持等待，
 * 用于断言 Coordinator single-flight 与 onDestroy 竞争下结果恰好完成一次。
 */
class FakeMethodResult : MethodChannel.Result {

    val successValue: AtomicReference<Any?> = AtomicReference(null)
    val errorCode: AtomicReference<String?> = AtomicReference(null)
    val errorMessage: AtomicReference<String?> = AtomicReference(null)
    @Volatile var completed: Boolean = false
        private set

    private val latch = CountDownLatch(1)

    override fun success(result: Any?) {
        successValue.set(result)
        completed = true
        latch.countDown()
    }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        this.errorCode.set(errorCode)
        this.errorMessage.set(errorMessage)
        completed = true
        latch.countDown()
    }

    override fun notImplemented() {
        completed = true
        latch.countDown()
    }

    /** 等待结果（最多 [timeoutMs] 毫秒）。 */
    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
