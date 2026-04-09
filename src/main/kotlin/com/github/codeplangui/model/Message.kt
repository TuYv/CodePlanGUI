package com.github.codeplangui.model

import kotlinx.serialization.Serializable

enum class MessageRole { SYSTEM, USER, ASSISTANT }

@Serializable
data class Message(
    val role: MessageRole,
    val content: String
)
