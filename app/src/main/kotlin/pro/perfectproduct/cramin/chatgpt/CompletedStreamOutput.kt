package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*

/** Completed output items are authoritative stream events; deltas alone never become a result. */
internal class CompletedStreamOutput {
    private val added = mutableMapOf<Int, String>()
    private val done = sortedMapOf<Int, JsonObject>()
    private val texts = mutableMapOf<Pair<Int, Int>, Pair<String, String>>()
    private val deltas = mutableMapOf<Pair<Int, Int>, StringBuilder>()
    private var invalid = false

    fun observe(event: JsonObject) {
        val type = event.string("type")
        if (type !in setOf("response.output_item.added", "response.output_item.done", "response.output_text.done", "response.output_text.delta")) return
        // Old/full terminal Responses objects remain supported; incomplete delta metadata is never used as proof.
        try {
            val index = event.getValue("output_index").jsonPrimitive.int
            require(index in 0..1023)
            when (type) {
                "response.output_item.added" -> {
                    val item = event.getValue("item").jsonObject
                    require(added.put(index, item.string("id") ?: error("item_id")) == null)
                }
                "response.output_item.done" -> {
                    val item = event.getValue("item").jsonObject
                    require(added[index] == item.string("id") && index !in done)
                    require(item.string("type") in setOf("message", "reasoning"))
                    done[index] = item
                }
                else -> {
                    val part = event.getValue("content_index").jsonPrimitive.int
                    require(part in 0..1023 && event.string("item_id") == added[index])
                    val key = index to part
                    if (type == "response.output_text.delta") {
                        require(key !in texts)
                        deltas.getOrPut(key) { StringBuilder() }.append(event.string("delta") ?: error("delta"))
                    } else {
                        val text = event.string("text") ?: error("text")
                        require(key !in texts)
                        deltas[key]?.let { require(it.toString() == text) }
                        texts[key] = (event.string("item_id") ?: error("item_id")) to text
                    }
                }
            }
        } catch (_: Exception) { invalid = true }
    }

    /** Called ONLY after response.completed + status + model checks. No EOF/partial fallback. */
    fun completedItems(terminal: JsonArray): JsonArray {
        if (terminal.isNotEmpty()) {
            if (done.isNotEmpty()) {
                require(!invalid && added.keys == done.keys)
                require(done.size == terminal.size)
                done.forEach { (idx, item) -> require(terminal[idx] == item) }
            }
            return terminal
        }
        require(!invalid && done.isNotEmpty() && added.keys == done.keys)
        require(done.keys.toList() == (0 until done.size).toList())
        var message = false
        for ((index, item) in done) {
            if (item.string("type") == "reasoning") continue
            require(item.string("role") == "assistant" && item.string("status") == "completed")
            val content = item.getValue("content").jsonArray
            require(content.isNotEmpty())
            for ((part, value) in content.withIndex()) {
                val c = value.jsonObject
                require(c.string("type") == "output_text")
                require(texts[index to part] == (item.string("id")!! to (c.string("text") ?: error("text"))))
            }
            message = true
        }
        require(message)
        return JsonArray(done.values.toList())
    }
}
