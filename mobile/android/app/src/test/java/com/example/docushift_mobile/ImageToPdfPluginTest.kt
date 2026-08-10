package com.example.docushift_mobile

import io.flutter.plugin.common.MethodCall
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

/**
 * ImageToPdfPlugin 平台通道测试：结构性门禁与分发。
 *
 * - pickImages → coordinator.pickImages(result)
 * - convertAndSave：参数缺失 / 空列表 / 元素非非空字符串 → INVALID_ARGS；
 *   超过 20 张 → TOO_MANY_FILES；合法列表 → coordinator.convertAndSave(有序列表, result)。
 */
class ImageToPdfPluginTest {

    private val coordinator = Mockito.mock(ImageToPdfCoordinator::class.java)
    private val activity = Mockito.mock(MainActivity::class.java).also {
        Mockito.`when`(it.coordinator).thenReturn(coordinator)
    }
    private val plugin = ImageToPdfPlugin(activity)

    @Test
    fun pickImages_dispatchesToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("pickImages", null), result)
        Mockito.verify(coordinator).pickImages(result)
    }

    @Test
    fun convertAndSave_missingArg_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("convertAndSave", null), result)
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convertAndSave_emptyList_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("convertAndSave", mapOf("imageUris" to emptyList<String>())), result)
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convertAndSave_elementNotString_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("convertAndSave", mapOf("imageUris" to listOf("a", 123, "c"))),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun convertAndSave_over20_tooManyFiles() {
        val result = FakeMethodResult()
        val uris = List(21) { "u$it" }
        plugin.onMethodCall(MethodCall("convertAndSave", mapOf("imageUris" to uris)), result)
        assertEquals("TOO_MANY_FILES", result.errorCode.get())
    }

    @Test
    fun convertAndSave_validList_dispatchesOrderedUris() {
        val result = FakeMethodResult()
        val input = listOf("a", "b", "c")
        plugin.onMethodCall(MethodCall("convertAndSave", mapOf("imageUris" to input)), result)
        // mockito-kotlin 的 argumentCaptor/capture 返回非空类型，避免 capture() 返回 null
        // 触发 Kotlin 非空断言 NPE（进而污染 Mockito 匹配器栈，连累其它用例）。
        val captor = argumentCaptor<List<String>>()
        verify(coordinator).convertAndSave(captor.capture(), eq(result))
        assertEquals(input, captor.firstValue)
    }

    @Test
    fun unknownMethod_notImplemented() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("unknown", null), result)
        assertEquals(true, result.completed)
    }

    // ================================================================
    // 第 9 期：sharePdf 参数校验
    // ================================================================

    @Test
    fun sharePdf_missingArg_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("sharePdf", null), result)
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun sharePdf_emptyString_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(MethodCall("sharePdf", mapOf("outputUri" to "")), result)
        assertEquals("INVALID_ARGS", result.errorCode.get())
    }

    @Test
    fun sharePdf_validUri_dispatchesToCoordinator() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("sharePdf", mapOf("outputUri" to "content://docushift/out.pdf")),
            result,
        )
        Mockito.verify(coordinator).sharePdf("content://docushift/out.pdf", result)
    }

    // ================================================================
    // 第 9 期 R1：非字符串 outputUri 显式类型判断
    // ================================================================

    @Test
    fun sharePdf_intOutputUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("sharePdf", mapOf("outputUri" to 123)),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun sharePdf_booleanOutputUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("sharePdf", mapOf("outputUri" to true)),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }

    @Test
    fun sharePdf_doubleOutputUri_invalidArgs() {
        val result = FakeMethodResult()
        plugin.onMethodCall(
            MethodCall("sharePdf", mapOf("outputUri" to 3.14)),
            result,
        )
        assertEquals("INVALID_ARGS", result.errorCode.get())
        Mockito.verifyNoInteractions(coordinator)
    }
}
