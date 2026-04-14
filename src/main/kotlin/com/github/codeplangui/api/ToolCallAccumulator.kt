package com.github.codeplangui.api

data class AccumulatedToolCall(
    val index: Int,
    val id: String?,
    val functionName: String?,
    val argumentsJson: String
)

class ToolCallAccumulator {
    private data class MutableToolCallState(
        var id: String? = null,
        var functionName: String? = null,
        val argumentsBuffer: StringBuilder = StringBuilder()
    )

    private val statesByIndex = linkedMapOf<Int, MutableToolCallState>()

    fun append(delta: ToolCallDelta) {
        val state = statesByIndex.getOrPut(delta.index) { MutableToolCallState() }
        if (delta.id != null) state.id = delta.id
        if (delta.functionName != null) state.functionName = delta.functionName
        delta.argumentsChunk?.let(state.argumentsBuffer::append)
    }

    fun snapshot(): List<AccumulatedToolCall> =
        statesByIndex.toSortedMap().map { (index, state) ->
            AccumulatedToolCall(
                index = index,
                id = state.id,
                functionName = state.functionName,
                argumentsJson = state.argumentsBuffer.toString()
            )
        }

    fun clear() {
        statesByIndex.clear()
    }

    fun isEmpty(): Boolean = statesByIndex.isEmpty()
}
