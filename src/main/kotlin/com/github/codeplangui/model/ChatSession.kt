package com.github.codeplangui.model

class ChatSession {
    private val messages = mutableListOf<Message>()

    fun add(msg: Message) {
        messages.add(msg)
    }

    /** Returns system messages + last 40 non-system messages. */
    fun getApiMessages(): List<Message> {
        val system = messages.filter { it.role == MessageRole.SYSTEM }
        val history = messages.filter { it.role != MessageRole.SYSTEM }.takeLast(40)
        return system + history
    }

    fun clear() {
        messages.clear()
    }
}
