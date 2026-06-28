package com.webshop.routes

import com.webshop.Database
import com.webshop.ErrorResponse
import com.webshop.Jx
import com.webshop.OkResponse
import com.webshop.ReseedResponse
import com.webshop.jsonbOrNull
import com.webshop.security.Security
import com.webshop.security.isAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.ResultSet

// --- DB sor -> dummyjson-szeru (camelCase) JSON objektum ---
private fun strOrNull(rs: ResultSet, col: String): JsonElement {
    val v = rs.getString(col)
    return if (v == null) JsonNull else JsonPrimitive(v)
}

private fun doubleOrNull(rs: ResultSet, col: String): JsonElement {
    val v = rs.getBigDecimal(col)
    return if (rs.wasNull() || v == null) JsonNull else JsonPrimitive(v.toDouble())
}

private fun intOrNull(rs: ResultSet, col: String): JsonElement {
    val v = rs.getInt(col)
    return if (rs.wasNull()) JsonNull else JsonPrimitive(v)
}

private fun jsonbField(rs: ResultSet, col: String): JsonElement =
    rs.jsonbOrNull(col) ?: JsonNull

private fun rowToProduct(rs: ResultSet): JsonObject = buildJsonObject {
    put("id", intOrNull(rs, "id"))
    put("title", strOrNull(rs, "title"))
    put("description", strOrNull(rs, "description"))
    put("category", strOrNull(rs, "category"))
    put("price", doubleOrNull(rs, "price"))
    put("discountPercentage", doubleOrNull(rs, "discount_percentage"))
    put("rating", doubleOrNull(rs, "rating"))
    put("stock", intOrNull(rs, "stock"))
    put("tags", jsonbField(rs, "tags"))
    put("brand", strOrNull(rs, "brand"))
    put("sku", strOrNull(rs, "sku"))
    put("weight", doubleOrNull(rs, "weight"))
    put("dimensions", jsonbField(rs, "dimensions"))
    put("warrantyInformation", strOrNull(rs, "warranty_information"))
    put("shippingInformation", strOrNull(rs, "shipping_information"))
    put("availabilityStatus", strOrNull(rs, "availability_status"))
    put("reviews", jsonbField(rs, "reviews"))
    put("returnPolicy", strOrNull(rs, "return_policy"))
    put("minimumOrderQuantity", intOrNull(rs, "minimum_order_quantity"))
    put("meta", jsonbField(rs, "meta"))
    put("images", jsonbField(rs, "images"))
    put("thumbnail", strOrNull(rs, "thumbnail"))
}

fun Route.productRoutes() = route("/api/products") {

    // GET /api/products?limit=&skip=&q=&category=&sortBy=&order=
    get {
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceAtMost(200)
        val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0
        val search = (call.request.queryParameters["q"] ?: "").trim()
        val category = (call.request.queryParameters["category"] ?: "").trim()

        val sortCols = mapOf(
            "title" to "title", "price" to "price", "rating" to "rating",
            "stock" to "stock", "id" to "id",
        )
        val sortBy = sortCols[call.request.queryParameters["sortBy"]] ?: "id"
        val order = if ((call.request.queryParameters["order"] ?: "asc").lowercase() == "desc") "DESC" else "ASC"

        val where = mutableListOf<String>()
        if (search.isNotEmpty()) {
            where.add("(title ILIKE ? OR description ILIKE ? OR brand ILIKE ?)")
        }
        if (category.isNotEmpty()) {
            where.add("category = ?")
        }
        val whereSql = if (where.isNotEmpty()) "WHERE ${where.joinToString(" AND ")}" else ""

        val result = Database.query { conn ->
            // total
            val total = conn.prepareStatement("SELECT COUNT(*)::int AS c FROM products $whereSql").use { ps ->
                bindSearchParams(ps, search, category)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt("c") }
            }
            // page
            val products = buildJsonArray {
                conn.prepareStatement(
                    "SELECT * FROM products $whereSql ORDER BY $sortBy $order LIMIT ? OFFSET ?"
                ).use { ps ->
                    var idx = bindSearchParams(ps, search, category)
                    ps.setInt(idx++, limit)
                    ps.setInt(idx, skip)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) add(rowToProduct(rs))
                    }
                }
            }
            buildJsonObject {
                put("products", products)
                put("total", JsonPrimitive(total))
                put("skip", JsonPrimitive(skip))
                put("limit", JsonPrimitive(limit))
            }
        }
        call.respond(result)
    }

    // GET /api/products/categories  (az ID elott kell legyen)
    get("/categories") {
        val cats = Database.query { conn ->
            buildJsonArray {
                conn.prepareStatement(
                    "SELECT DISTINCT category FROM products WHERE category IS NOT NULL ORDER BY category"
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) add(JsonPrimitive(rs.getString("category")))
                    }
                }
            }
        }
        call.respond(cats)
    }

    // GET /api/products/category/{category}
    get("/category/{category}") {
        val category = call.parameters["category"] ?: ""
        val result = Database.query { conn ->
            val products = buildJsonArray {
                conn.prepareStatement("SELECT * FROM products WHERE category = ? ORDER BY id").use { ps ->
                    ps.setString(1, category)
                    ps.executeQuery().use { rs -> while (rs.next()) add(rowToProduct(rs)) }
                }
            }
            buildJsonObject {
                put("products", products)
                put("total", JsonPrimitive(products.size))
            }
        }
        call.respond(result)
    }

    // GET /api/products/{id}
    get("/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
            return@get
        }
        val product = Database.query { conn ->
            conn.prepareStatement("SELECT * FROM products WHERE id = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rowToProduct(rs) else null }
            }
        }
        if (product == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
        } else {
            call.respond(product)
        }
    }

    // ---- Admin vegpontok ----
    authenticate(Security.JWT_AUTH) {

        // POST /api/products/reseed
        post("/reseed") {
            if (!call.principal<JWTPrincipal>()!!.isAdmin) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Ehhez admin jogosultsag kell."))
                return@post
            }
            try {
                val count = Database.query { Database.seedProductsFromDummyJson(force = true) }
                call.respond(ReseedResponse(ok = true, count = count))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("Seedeles sikertelen", e.message))
            }
        }

        // POST /api/products
        post {
            if (!call.principal<JWTPrincipal>()!!.isAdmin) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Ehhez admin jogosultsag kell."))
                return@post
            }
            val p = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject {} }
            val id = Jx.int(p, "id")
            val title = Jx.str(p, "title")
            if (id == null || title.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("id es title kotelezo."))
                return@post
            }
            try {
                Database.query { conn ->
                    conn.prepareStatement(
                        """INSERT INTO products (id, title, description, category, price, discount_percentage,
                            rating, stock, brand, sku, weight, thumbnail, tags, dimensions, reviews, images, meta, raw)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
                    ).use { ps ->
                        ps.setInt(1, id)
                        ps.setString(2, title)
                        ps.setString(3, Jx.str(p, "description"))
                        ps.setString(4, Jx.str(p, "category"))
                        ps.setObject(5, Jx.num(p, "price"))
                        ps.setObject(6, Jx.num(p, "discountPercentage"))
                        ps.setObject(7, Jx.num(p, "rating"))
                        ps.setObject(8, Jx.int(p, "stock") ?: 0)
                        ps.setString(9, Jx.str(p, "brand"))
                        ps.setString(10, Jx.str(p, "sku"))
                        ps.setObject(11, Jx.num(p, "weight"))
                        ps.setString(12, Jx.str(p, "thumbnail"))
                        setJsonbParam(ps, 13, p["tags"])
                        setJsonbParam(ps, 14, p["dimensions"])
                        setJsonbParam(ps, 15, p["reviews"] ?: buildJsonArray { })
                        setJsonbParam(ps, 16, p["images"] ?: buildJsonArray { })
                        setJsonbParam(ps, 17, p["meta"])
                        setJsonbParam(ps, 18, p)
                        ps.executeUpdate()
                    }
                }
                call.respond(HttpStatusCode.Created, OkResponse())
            } catch (e: org.postgresql.util.PSQLException) {
                if (e.sqlState == "23505") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Ez az id mar letezik."))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "DB hiba"))
                }
            }
        }

        // PUT /api/products/{id}
        put("/{id}") {
            if (!call.principal<JWTPrincipal>()!!.isAdmin) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Ehhez admin jogosultsag kell."))
                return@put
            }
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
                return@put
            }
            val body = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject {} }
            val allowed = mapOf(
                "title" to "title", "description" to "description", "category" to "category",
                "price" to "price", "stock" to "stock", "brand" to "brand", "thumbnail" to "thumbnail",
            )
            val sets = mutableListOf<String>()
            val values = mutableListOf<Pair<String, JsonElement>>()
            for ((key, col) in allowed) {
                val v = body[key]
                if (v != null && v != JsonNull) {
                    sets.add("$col = ?")
                    values.add(col to v)
                }
            }
            if (sets.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nincs modositando mezo."))
                return@put
            }

            val updated = Database.query { conn ->
                conn.prepareStatement(
                    "UPDATE products SET ${sets.joinToString(", ")}, updated_at = now() WHERE id = ? RETURNING *"
                ).use { ps ->
                    var i = 1
                    for ((col, v) in values) {
                        when (col) {
                            "price" -> ps.setObject(i, v.jsonPrimitive.content.toDoubleOrNull())
                            "stock" -> ps.setObject(i, v.jsonPrimitive.content.toIntOrNull())
                            else -> ps.setString(i, v.jsonPrimitive.content)
                        }
                        i++
                    }
                    ps.setInt(i, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rowToProduct(rs) else null }
                }
            }
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
            } else {
                call.respond(updated)
            }
        }

        // DELETE /api/products/{id}
        delete("/{id}") {
            if (!call.principal<JWTPrincipal>()!!.isAdmin) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Ehhez admin jogosultsag kell."))
                return@delete
            }
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
                return@delete
            }
            val affected = Database.query { conn ->
                conn.prepareStatement("DELETE FROM products WHERE id = ?").use { ps ->
                    ps.setInt(1, id)
                    ps.executeUpdate()
                }
            }
            if (affected == 0) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem talalhato."))
            } else {
                call.respond(OkResponse())
            }
        }
    }
}

// A keresesi/kategoria parametereket kotjuk a COUNT es a SELECT lekerdezesekhez (azonos sorrend).
private fun bindSearchParams(ps: java.sql.PreparedStatement, search: String, category: String): Int {
    var idx = 1
    if (search.isNotEmpty()) {
        val like = "%$search%"
        ps.setString(idx++, like)
        ps.setString(idx++, like)
        ps.setString(idx++, like)
    }
    if (category.isNotEmpty()) {
        ps.setString(idx++, category)
    }
    return idx
}

private fun setJsonbParam(ps: java.sql.PreparedStatement, idx: Int, value: JsonElement?) {
    if (value == null || value == JsonNull) {
        ps.setNull(idx, java.sql.Types.OTHER)
        return
    }
    val obj = org.postgresql.util.PGobject()
    obj.type = "jsonb"
    obj.value = value.toString()
    ps.setObject(idx, obj)
}
