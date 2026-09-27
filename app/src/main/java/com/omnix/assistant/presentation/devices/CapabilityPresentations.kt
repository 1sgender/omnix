package com.omnix.assistant.presentation.devices

import com.omnix.assistant.agent.capability.CapabilityStatus

/**
 * Чистые токены презентации для capability-снимка «На этом телефоне»
 * (блок 6 плана пересборки фронта, 2026-09-26). Паттерн тот же, что
 * AutomationDescriptions/MemoryTypeLabels: домен-объект → токен →
 * string-ресурс/цвет в экране. Без Android-зависимостей — JVM-тестируемо.
 */

/** Локализуемая подпись статуса группы возможностей. */
enum class CapabilityStatusLabel {
    AVAILABLE,
    PERMISSION_REQUIRED,
    USER_ACTION_REQUIRED,
    UNSUPPORTED
}

fun capabilityStatusLabel(status: CapabilityStatus): CapabilityStatusLabel = when (status) {
    is CapabilityStatus.Available -> CapabilityStatusLabel.AVAILABLE
    is CapabilityStatus.PermissionRequired -> CapabilityStatusLabel.PERMISSION_REQUIRED
    is CapabilityStatus.UserActionRequired -> CapabilityStatusLabel.USER_ACTION_REQUIRED
    is CapabilityStatus.Unsupported -> CapabilityStatusLabel.UNSUPPORTED
}

/**
 * Точка-статус: зелёный (доступно) / жёлтый-внимание (можно включить —
 * разрешение или системный экран) / серый (на этом устройстве нельзя).
 * Warning, а не error: недоступность возможности — не авария.
 */
enum class CapabilityDotToken { OK, ATTENTION, OFF }

fun capabilityDot(status: CapabilityStatus): CapabilityDotToken = when (status) {
    is CapabilityStatus.Available -> CapabilityDotToken.OK
    is CapabilityStatus.PermissionRequired,
    is CapabilityStatus.UserActionRequired -> CapabilityDotToken.ATTENTION
    is CapabilityStatus.Unsupported -> CapabilityDotToken.OFF
}

/** Локализуемое имя группы OmniCapability (id в dot-нотации — ключ). */
enum class CapabilityGroupToken {
    BLUETOOTH,
    WIFI,
    BRIGHTNESS,
    SCREENSHOT,
    APPS,
    SMS,
    CALL,
    MEDIA,
    ACCESSIBILITY,
    LOCATION,
    OTHER
}

fun capabilityGroupToken(id: String): CapabilityGroupToken = when (id) {
    "device.bluetooth" -> CapabilityGroupToken.BLUETOOTH
    "device.wifi" -> CapabilityGroupToken.WIFI
    "device.brightness" -> CapabilityGroupToken.BRIGHTNESS
    "device.screenshot" -> CapabilityGroupToken.SCREENSHOT
    "device.apps" -> CapabilityGroupToken.APPS
    "communication.sms" -> CapabilityGroupToken.SMS
    "communication.call" -> CapabilityGroupToken.CALL
    "media" -> CapabilityGroupToken.MEDIA
    "accessibility" -> CapabilityGroupToken.ACCESSIBILITY
    "location" -> CapabilityGroupToken.LOCATION
    else -> CapabilityGroupToken.OTHER
}
