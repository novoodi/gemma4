package com.navoodi.morimi.data.local

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    @TypeConverter
    fun fromList(list: List<String>): String = list.joinToString("||")

    @TypeConverter
    fun toList(str: String): List<String> =
        if (str.isBlank()) emptyList() else str.split("||")

    // ── 임베딩 벡터: FloatArray ↔ ByteArray (little-endian float32 직렬화) ──
    @TypeConverter
    fun fromFloatArray(arr: FloatArray?): ByteArray? {
        if (arr == null) return null
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }
}