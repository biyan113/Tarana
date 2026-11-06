package app.tarana.pad.yt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 空安全的 JSON 遍历小工具。InnerTube 的响应嵌套很深且常改版，
 * 和 radif 的 `?.` 链一样：任何一步缺失都返回 null，不抛异常。
 */
val JsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

typealias Node = JsonElement

operator fun JsonElement?.get(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

operator fun JsonElement?.get(index: Int): JsonElement? =
    (this as? JsonArray)?.getOrNull(index)

val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement?.num: Long?
    get() = (this as? JsonPrimitive)?.content?.toLongOrNull()

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

val JsonElement?.arr: List<JsonElement>
    get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.obj: JsonObject?
    get() = this as? JsonObject

/** 按路径取值：node.at("a", "b", 0, "c") */
fun JsonElement?.at(vararg path: Any): JsonElement? {
    var cur = this
    for (p in path) {
        cur = when (p) {
            is String -> cur[p]
            is Int -> cur[p]
            else -> null
        } ?: return null
    }
    return cur
}

/** 深度遍历，收集所有 key 等于 [key] 的值（对应 radif 的 collect / collectResponsiveRows）。 */
fun JsonElement?.collect(key: String, out: MutableList<JsonElement> = mutableListOf()): MutableList<JsonElement> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v !is JsonNull) out.add(v)
            v.collect(key, out)
        }
        is JsonArray -> for (v in this) v.collect(key, out)
        else -> Unit
    }
    return out
}

/** 深度优先找到第一个 key 对应的值。 */
fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.takeUnless { it is JsonNull }?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> Unit
    }
    return null
}

/** 读取 { runs:[{text}] } 或 { simpleText } 的文本。 */
fun JsonElement?.runsText(): String {
    if (this == null) return ""
    (this as? JsonPrimitive)?.let { return it.content }
    this["simpleText"].str?.let { return it }
    return this["runs"].arr.joinToString("") { it["text"].str ?: "" }
}

/** browseEndpoint 上的 pageType */
fun JsonElement?.pageType(): String? =
    this.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str
