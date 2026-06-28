package com.webshop

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Segedfuggvenyek dummyjson JsonObject mezoinek tipusos kiolvasasara. */
object Jx {
    private fun prim(o: JsonObject, key: String): JsonPrimitive? = o[key] as? JsonPrimitive

    fun str(o: JsonObject, key: String): String? = prim(o, key)?.contentOrNull

    fun int(o: JsonObject, key: String): Int? = prim(o, key)?.intOrNull

    fun num(o: JsonObject, key: String): Double? = prim(o, key)?.doubleOrNull
}

/** JSONB oszlop kiolvasasa JsonElement-kent (vagy null). */
fun java.sql.ResultSet.jsonbOrNull(col: String): kotlinx.serialization.json.JsonElement? {
    val s = getString(col) ?: return null
    return runCatching { appJson.parseToJsonElement(s) }.getOrNull()
}
