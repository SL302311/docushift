package com.example.docushift_mobile

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 受控 ContentResolver 构造器。
 *
 * 本版 android.jar 中 ContentResolver 的方法为 final，无法子类重写；
 * 故用 Mockito（inline mock maker，Mockito 5 默认）直接模拟 ContentResolver，
 * 受控返回三段大小回退所需的 Cursor / AFD / PFD。
 *
 * 不实例化任何 Android 运行时类（android.jar 为桩），避免 "Stub!" 异常。
 */
class FakeContentResolver(
    var displayName: String = "photo.png",
    var mimeType: String = "image/png",
    var cursorSize: Long? = 12345L,
    var includeSizeColumn: Boolean = true,
    var assetFileDescriptor: AssetFileDescriptor? = null,
    var parcelFileDescriptor: ParcelFileDescriptor? = null,
    var inputStream: InputStream = ByteArrayInputStream(ByteArray(0)),
) {
    /** 生成一个受控的 ContentResolver mock。 */
    fun build(): ContentResolver {
        val cursor = Mockito.mock(Cursor::class.java)
        Mockito.`when`(cursor.moveToFirst()).thenReturn(true)
        Mockito.`when`(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        Mockito.`when`(cursor.getString(0)).thenReturn(displayName)
        if (includeSizeColumn) {
            Mockito.`when`(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
            Mockito.`when`(cursor.getLong(1)).thenReturn(cursorSize ?: 0L)
        } else {
            Mockito.`when`(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(-1)
        }

        val cr = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(
            cr.query(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())
        ).thenReturn(cursor)
        Mockito.`when`(cr.getType(Mockito.any())).thenReturn(mimeType)
        Mockito.`when`(cr.openAssetFileDescriptor(Mockito.any(), Mockito.any()))
            .thenReturn(assetFileDescriptor)
        Mockito.`when`(cr.openFileDescriptor(Mockito.any(), Mockito.any()))
            .thenReturn(parcelFileDescriptor)
        Mockito.`when`(cr.openInputStream(Mockito.any())).thenReturn(inputStream)
        return cr
    }
}
