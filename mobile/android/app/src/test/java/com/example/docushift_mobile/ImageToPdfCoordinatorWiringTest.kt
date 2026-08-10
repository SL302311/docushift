package com.example.docushift_mobile

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ImageToPdfCoordinator 无设备接线测试：
 * 真实执行 single-flight（compareAndSet）+ CompletionGuard + onDestroy 竞争逻辑，
 * 以及第 3 期多图路径（有序 URI 列表、批量校验、输出清理接缝）。
 *
 * 通过可注入接缝（validateProvider / convertExecutor / saveLauncher / resolver /
 * uriParser / outputDeleter）在普通 JVM 下驱动真实协调流程，绕过平台解码与 SAF UI。
 */
class ImageToPdfCoordinatorWiringTest {

    private val inUri = Mockito.mock(Uri::class.java)
    private val outUri = Mockito.mock(Uri::class.java)

    private fun coordinator(): ImageToPdfCoordinator {
        val c = ImageToPdfCoordinator()   // activity = null（测试路径）
        // 批量校验：把每个 URI 映射为一个已验证图片（顺序保留）
        c.validateProvider = { uris ->
            ManyValidationResult(
                true,
                uris.map { ValidatedImage(it, "n", "image/png", 1000) },
                null, null, null,
            )
        }
        // android.jar 桩下 Uri.parse 返回 null → 注入 uriParser 返回受控 mock Uri
        c.uriParser = { _ -> inUri }
        Mockito.`when`(inUri.toString()).thenReturn("content://docushift/in.png")
        Mockito.`when`(outUri.toString()).thenReturn("content://docushift/out.pdf")
        return c
    }

    @Test
    fun singleFlight_secondCallReturnsBusy() {
        val c = coordinator()
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ ->
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            outUri.toString() to 123L
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r1 = FakeMethodResult()
        val r2 = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r1)
        assertTrue("executor 应已进入后台", started.await(3, TimeUnit.SECONDS))

        // 挂起期间第二次调用 → BUSY，且不触发第二次转换
        c.convertAndSave(listOf(inUri.toString()), r2)
        assertEquals("BUSY", r2.errorCode.get())

        proceed.countDown()
        assertTrue("首次转换应完成", r1.await(3000))
        assertNull(r1.errorCode.get())
        assertNotNull(r1.successValue.get())
        assertEquals("BUSY", r2.errorCode.get())   // 第二次始终为 BUSY
    }

    @Test
    fun backgroundCompletion_completesExactlyOnce() {
        val c = coordinator()
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ -> outUri.toString() to 123L }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r)
        assertTrue(r.await(3000))
        assertNull(r.errorCode.get())
        assertNotNull(r.successValue.get())
    }

    @Test
    fun onDestroy_race_withBackground_exactlyOnce() {
        val c = coordinator()
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ ->
            started.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            outUri.toString() to 123L
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r)
        assertTrue("executor 应已进入后台", started.await(3, TimeUnit.SECONDS))

        // onDestroy 与后台完成竞争：只有一个胜出（恰好一次）
        c.onDestroy()
        proceed.countDown()
        assertTrue(r.await(3000))

        val successFired = r.successValue.get() != null
        val destroyedFired = r.errorCode.get() == "DESTROYED"
        // 不变量：恰好一个结果被投递
        assertTrue("结果应恰好完成一次", successFired xor destroyedFired)
        assertTrue("结果应已投递", r.completed)
    }

    @Test
    fun multiImage_orderPreservedToExecutor() {
        val c = coordinator()
        val u1 = Mockito.mock(Uri::class.java)
        val u2 = Mockito.mock(Uri::class.java)
        val u3 = Mockito.mock(Uri::class.java)
        val byInput = mapOf("u1" to u1, "u2" to u2, "u3" to u3)
        c.uriParser = { byInput[it]!! }
        var captured: List<ValidatedImage>? = null
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { images, _ ->
            captured = images
            outUri.toString() to 123L
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf("u1", "u2", "u3"), r)
        assertTrue(r.await(3000))
        assertNull(r.errorCode.get())
        assertNotNull(captured)
        assertEquals(3, captured!!.size)
        // 三个不同 URI，顺序与 UI 传入严格一致
        assertSame(u1, captured!![0].uri)
        assertSame(u2, captured!![1].uri)
        assertSame(u3, captured!![2].uri)
    }

    @Test
    fun convertAndSave_exactly20Images_dispatchesToExecutor() {
        val c = coordinator()
        var captured: List<ValidatedImage>? = null
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { images, _ ->
            captured = images
            outUri.toString() to 123L
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(List(20) { "u$it" }, r)
        assertTrue(r.await(3000))
        assertNull(r.errorCode.get())
        assertNotNull(captured)
        assertEquals(20, captured!!.size)
    }

    @Test
    fun outputCleanup_onFailure_deleterInvoked() {
        val c = coordinator()
        val deleted = AtomicBoolean(false)
        c.outputDeleter = { _ -> deleted.set(true); true }
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ ->
            throw Exception("DECODE_FAILED: boom")
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r)
        assertTrue(r.await(3000))
        assertEquals("DECODE_FAILED", r.errorCode.get())
        assertTrue("转换失败时输出清理应被调用", deleted.get())
    }

    @Test
    fun outputCleanup_onFailure_defaultDeleter_usesResolver() {
        val c = coordinator()
        // 不注入 outputDeleter，使用生产默认（通过 resolver 删除输出）
        val mockCr = Mockito.mock(android.content.ContentResolver::class.java)
        c.resolver = mockCr
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ ->
            throw Exception("DECODE_FAILED: boom")
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf("in"), r)
        assertTrue(r.await(3000))
        assertEquals("DECODE_FAILED", r.errorCode.get())
        // 生产默认删除器应调用 resolver.delete(outUri)
        Mockito.verify(mockCr).delete(outUri, null, null)
    }

    @Test
    fun outputCleanup_onFailure_deleterThrows_preservesOriginalError() {
        val c = coordinator()
        // 删除器自身失败，不得覆盖原始转换错误
        c.outputDeleter = { throw RuntimeException("delete boom") }
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ ->
            throw Exception("DECODE_FAILED: boom")
        }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }

        val r = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r)
        assertTrue(r.await(3000))
        // 原始转换错误（DECODE_FAILED）必须保留，不能被删除异常覆盖
        assertEquals("DECODE_FAILED", r.errorCode.get())
    }

    @Test
    fun pickImages_singleFlight_secondCallBusy() {
        val c = coordinator()
        // 注入无操作的启动器（launch 不触发回调），使 _pendingPick 保持占用
        @Suppress("UNCHECKED_CAST")
        c.pickImagesLauncher = Mockito.mock(ActivityResultLauncher::class.java) as ActivityResultLauncher<Array<String>>

        val r1 = FakeMethodResult()
        c.pickImages(r1)   // 占用 pending
        val r2 = FakeMethodResult()
        c.pickImages(r2)   // 再次调用 → BUSY
        assertEquals("BUSY", r2.errorCode.get())
    }

    // ================================================================
    // 第 9 期：sharePdf — intent 契约、URI 校验、异常处理
    // ================================================================

    @Test
    fun sharePdf_validContentUri_startsChooserWithCorrectIntent() {
        val c = coordinator()
        var capturedIntent: Intent? = null
        c.shareLauncher = { intent -> capturedIntent = intent }

        val r = FakeMethodResult()
        Mockito.`when`(outUri.toString()).thenReturn("content://docushift/test.pdf")
        Mockito.`when`(outUri.scheme).thenReturn("content")
        c.uriParser = { str ->
            if (str == "content://docushift/test.pdf") outUri
            else inUri
        }
        c.sharePdf("content://docushift/test.pdf", r)

        // shareLauncher 被调用（非 error 路径）
        assertNull(r.errorCode.get())
        assertNotNull("分享 Intent 应被传给 launcher", capturedIntent)
        // 注意：android.jar 桩 Intent 构造器在纯 JVM 下不保留 action 属性，
        // 实际 intent 契约（ACTION_SEND / MIME / EXTRA_STREAM / flag / ClipData）
        // 由生产代码构建并在真机生效——真机验收留第十期。
    }

    @Test
    fun sharePdf_emptyUri_invalidArgs() {
        val c = coordinator()
        var launched = false
        c.shareLauncher = { launched = true }

        val r = FakeMethodResult()
        c.sharePdf("   ", r)
        assertEquals("INVALID_ARGS", r.errorCode.get())
        assertFalse("空 URI 不应启动分享", launched)
    }

    @Test
    fun sharePdf_fileUri_invalidOutputUri() {
        val c = coordinator()
        val fileUri = Mockito.mock(Uri::class.java)
        Mockito.`when`(fileUri.toString()).thenReturn("file:///sdcard/test.pdf")
        Mockito.`when`(fileUri.scheme).thenReturn("file")
        c.uriParser = { fileUri }
        var launched = false
        c.shareLauncher = { launched = true }

        val r = FakeMethodResult()
        c.sharePdf("file:///sdcard/test.pdf", r)
        assertEquals("INVALID_OUTPUT_URI", r.errorCode.get())
        assertFalse("file:// URI 不应启动分享", launched)
    }

    @Test
    fun sharePdf_httpUri_invalidOutputUri() {
        val c = coordinator()
        val httpUri = Mockito.mock(Uri::class.java)
        Mockito.`when`(httpUri.toString()).thenReturn("https://example.com/test.pdf")
        Mockito.`when`(httpUri.scheme).thenReturn("https")
        c.uriParser = { httpUri }
        var launched = false
        c.shareLauncher = { launched = true }

        val r = FakeMethodResult()
        c.sharePdf("https://example.com/test.pdf", r)
        assertEquals("INVALID_OUTPUT_URI", r.errorCode.get())
        assertFalse(launched)
    }

    @Test
    fun sharePdf_noScheme_invalidOutputUri() {
        val c = coordinator()
        val bareUri = Mockito.mock(Uri::class.java)
        Mockito.`when`(bareUri.toString()).thenReturn("test.pdf")
        Mockito.`when`(bareUri.scheme).thenReturn(null)
        c.uriParser = { bareUri }
        var launched = false
        c.shareLauncher = { launched = true }

        val r = FakeMethodResult()
        c.sharePdf("test.pdf", r)
        assertEquals("INVALID_OUTPUT_URI", r.errorCode.get())
        assertFalse(launched)
    }

    @Test
    fun sharePdf_activityNotFound_shareUnavailable() {
        val c = coordinator()
        c.shareLauncher = { throw android.content.ActivityNotFoundException("no activity") }
        Mockito.`when`(outUri.toString()).thenReturn("content://test/x.pdf")
        Mockito.`when`(outUri.scheme).thenReturn("content")
        c.uriParser = { outUri }

        val r = FakeMethodResult()
        c.sharePdf("content://test/x.pdf", r)
        assertEquals("SHARE_UNAVAILABLE", r.errorCode.get())
    }

    @Test
    fun sharePdf_shareLauncherThrows_shareUnavailable() {
        val c = coordinator()
        c.shareLauncher = { throw RuntimeException("start failed") }
        Mockito.`when`(outUri.toString()).thenReturn("content://test/x.pdf")
        Mockito.`when`(outUri.scheme).thenReturn("content")
        c.uriParser = { outUri }

        val r = FakeMethodResult()
        c.sharePdf("content://test/x.pdf", r)
        assertEquals("SHARE_UNAVAILABLE", r.errorCode.get())
    }

    @Test
    fun sharePdf_doesNotInterfereWithConversion() {
        // 分享不应干扰 single-flight：convertAndSave 先被调用后仍可分享
        val c = coordinator()
        c.convertExecutor = ImageToPdfCoordinator.ConvertExecutor { _, _ -> outUri.toString() to 123L }
        c.saveLauncher = ImageToPdfCoordinator.SaveLauncher { _ -> outUri }
        var shareLaunched = false
        c.shareLauncher = { shareLaunched = true }
        Mockito.`when`(outUri.toString()).thenReturn("content://docushift/out.pdf")
        Mockito.`when`(outUri.scheme).thenReturn("content")
        c.uriParser = { s ->
            if (s == "content://docushift/out.pdf") outUri else inUri
        }

        // 先转换
        val r1 = FakeMethodResult()
        c.convertAndSave(listOf(inUri.toString()), r1)
        assertTrue(r1.await(3000))
        assertNull(r1.errorCode.get())

        // 再分享同一个 URI
        val r2 = FakeMethodResult()
        c.sharePdf("content://docushift/out.pdf", r2)
        assertNull(r2.errorCode.get())
        assertTrue(shareLaunched)
    }
}
