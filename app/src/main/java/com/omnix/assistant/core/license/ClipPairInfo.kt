package com.omnix.assistant.core.license

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Модель/цвет пары из серверного каталога: в коде приложения моделей нет. */
@Serializable
data class ClipModelInfo(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("color_name") val colorName: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("image_url") val imageUrl: String? = null
) {
    /** «OMNIX Clip · Чёрный» — как модель названа на сервере. */
    val label: String get() = "$displayName · $colorName"
}

/** Своя пара наушников (validate-ответ). */
@Serializable
data class ClipPairInfo(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("bt_address") val btAddress: String,
    @SerialName("model") val model: ClipModelInfo? = null,
    @SerialName("bound_at") val boundAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null
)
