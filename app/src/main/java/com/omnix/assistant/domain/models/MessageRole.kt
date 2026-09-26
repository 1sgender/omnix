package com.omnix.assistant.domain.models

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),

    /**
     * A failed round trip (mock 2026-09-26): rendered as its own styled
     * message in the chat, not as a regular OMNIX reply. Stored as a plain
     * string in Room — no schema change, old rows are unaffected.
     */
    ERROR("error");

    companion object {
        fun fromString(value: String): MessageRole {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: USER
        }
    }
}
