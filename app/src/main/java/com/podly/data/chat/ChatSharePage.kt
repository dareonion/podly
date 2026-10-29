package com.podly.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import java.util.IdentityHashMap

/**
 * Reads a ChatGPT share page (`chatgpt.com/share/<id>`) into its title and the
 * assistant's messages.
 *
 * The page renders client-side, but the server HTML carries the whole conversation:
 * a `streamController.enqueue("…")` script holds a React Router "turbo-stream"
 * payload, a flat JSON array in which objects are `{"_<key index>": <value index>}`
 * and arrays list indices, so every string appears once and is referenced by
 * position. None of this is documented, so it will change; [parse] fails loudly
 * rather than returning a partial chat when it does (layout read 2026-10-29).
 *
 * Links in a message's text are placeholders (`<Link url={turn5search2.url.uri}>`);
 * their targets are in the message's `metadata.model_dil_v2.appData.opGenui.modelDataBindings`.
 */
object ChatSharePage {

    data class Message(
        /** The message text: Markdown plus ChatGPT's own layout tags. */
        val text: String,
        /** Placeholder name (`turn5search2`) -> URL. */
        val links: Map<String, String>,
    )

    data class Chat(val title: String?, val messages: List<Message>)

    private val SHARE_URL = Regex("""https?://(?:www\.)?chatgpt\.com/share/[0-9a-fA-F-]{20,}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** The first share link in [text], e.g. from the ChatGPT app's share sheet. */
    fun shareUrlIn(text: String): String? = SHARE_URL.find(text)?.value

    fun parse(html: String): Chat {
        val literal = enqueuedLiteral(html) ?: error("This doesn't look like a ChatGPT share page.")
        val payload = json.decodeFromString<String>(literal)
        val flat = json.parseToJsonElement(payload.lineSequence().first()).jsonArray
        val root = TurboStream(flat).decode(0)
        val conversation = findConversation(root)
            ?: error("Couldn't find the conversation in this ChatGPT page.")
        val mapping = conversation["mapping"] as Map<*, *>

        // The thread shown is the path from current_node back to the root; other
        // branches are edits and regenerations nobody is looking at.
        val thread = ArrayDeque<Map<*, *>>()
        var nodeId = conversation["current_node"] as? String
        val seen = mutableSetOf<String>()
        while (nodeId != null && seen.add(nodeId)) {
            val node = mapping[nodeId] as? Map<*, *> ?: break
            thread.addFirst(node)
            nodeId = node["parent"] as? String
        }
        val messages = thread.mapNotNull { node -> assistantMessage(node["message"] as? Map<*, *>) }
        if (messages.isEmpty()) error("This ChatGPT chat has no answers in it.")
        return Chat(conversation["title"] as? String, messages)
    }

    private fun assistantMessage(message: Map<*, *>?): Message? {
        message ?: return null
        if ((message["author"] as? Map<*, *>)?.get("role") != "assistant") return null
        val recipient = message["recipient"] as? String
        if (recipient != null && recipient != "all") return null
        val content = message["content"] as? Map<*, *> ?: return null
        if (content["content_type"] != "text") return null
        val text = (content["parts"] as? List<*>).orEmpty()
            .filterIsInstance<String>()
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (text.isBlank()) return null
        val bindings = (message["metadata"] as? Map<*, *>)
            ?.path("model_dil_v2", "appData", "opGenui", "modelDataBindings") as? Map<*, *>
        val links = bindings.orEmpty().mapNotNull { (name, value) ->
            val uri = (value as? Map<*, *>)?.path("url", "uri") as? String
            if (name is String && uri != null) name to uri else null
        }.toMap()
        return Message(text, links)
    }

    private fun Map<*, *>.path(vararg keys: String): Any? =
        keys.fold(this as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }

    /** The conversation object: the one map with both a `mapping` and a `current_node`. */
    private fun findConversation(root: Any?): Map<*, *>? {
        val stack = ArrayDeque<Any?>().apply { add(root) }
        val visited = IdentityHashMap<Any, Unit>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast() ?: continue
            if (visited.put(node, Unit) != null) continue
            when (node) {
                is Map<*, *> -> {
                    if (node["mapping"] is Map<*, *> && node.containsKey("current_node")) return node
                    stack.addAll(node.values)
                }
                is List<*> -> stack.addAll(node)
            }
        }
        return null
    }

    /**
     * The argument of the first `streamController.enqueue("…")`, still a JSON string
     * literal. Scanned by hand: a regex over a megabyte-long literal overflows the
     * stack on Java's backtracking engine.
     */
    internal fun enqueuedLiteral(html: String): String? {
        val marker = "streamController.enqueue(\""
        val start = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length - 1) ?: return null
        var i = start + 1
        while (i < html.length) {
            when (html[i]) {
                '\\' -> i += 2
                '"' -> return html.substring(start, i + 1)
                else -> i++
            }
        }
        return null
    }

    /** Rebuilds plain Kotlin values (maps, lists, strings, numbers) from the flat array. */
    private class TurboStream(private val flat: JsonArray) {
        private val memo = HashMap<Int, Any?>()

        fun decode(index: Int): Any? {
            if (index < 0 || index >= flat.size) return null // negative = undefined, null, NaN…
            if (memo.containsKey(index)) return memo[index]
            return when (val element = flat[index]) {
                JsonNull -> null
                is JsonObject -> LinkedHashMap<String, Any?>().also { map ->
                    memo[index] = map // before the fields: values may refer back to it
                    for ((key, value) in element) {
                        val name = key.removePrefix("_").toIntOrNull()?.let(::decode) as? String ?: continue
                        map[name] = (value as? JsonPrimitive)?.intOrNull?.let(::decode)
                    }
                }
                is JsonArray -> {
                    // A leading string tags a special value (["D", ms] is a Date, ["P", …]
                    // a promise); nothing this reader needs.
                    if ((element.firstOrNull() as? JsonPrimitive)?.isString == true) {
                        memo[index] = null
                        null
                    } else {
                        ArrayList<Any?>().also { list ->
                            memo[index] = list
                            element.forEach { list += (it as? JsonPrimitive)?.intOrNull?.let(::decode) }
                        }
                    }
                }
                is JsonPrimitive -> when {
                    element.isString -> element.content
                    else -> element.longOrNull ?: element.doubleOrNull ?: element.booleanOrNull
                }.also { memo[index] = it }
            }
        }
    }
}
