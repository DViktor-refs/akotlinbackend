package com.webshop.routes

import com.webshop.Barion
import com.webshop.CheckoutResponse
import com.webshop.Config
import com.webshop.Database
import com.webshop.ErrorResponse
import com.webshop.jsonbOrNull
import com.webshop.security.Security
import com.webshop.security.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.postgresql.util.PGobject
import java.sql.ResultSet
import java.util.UUID
import kotlin.math.roundToLong

private fun jsonbParam(value: JsonElement): PGobject =
    PGobject().apply { type = "jsonb"; this.value = value.toString() }

private fun orderRowToJson(rs: ResultSet): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(rs.getInt("id")))
    val userId = rs.getInt("user_id")
    put("user_id", if (rs.wasNull()) JsonNull else JsonPrimitive(userId))
    put("status", JsonPrimitive(rs.getString("status")))
    put("total", rs.getBigDecimal("total")?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull)
    put("currency", JsonPrimitive(rs.getString("currency")))
    put("items", rs.jsonbOrNull("items") ?: JsonNull)
    put("barion_payment_id", rs.getString("barion_payment_id")?.let { JsonPrimitive(it) } ?: JsonNull)
    put("payment_request_id", rs.getString("payment_request_id")?.let { JsonPrimitive(it) } ?: JsonNull)
    put("created_at", rs.getString("created_at")?.let { JsonPrimitive(it) } ?: JsonNull)
    put("updated_at", rs.getString("updated_at")?.let { JsonPrimitive(it) } ?: JsonNull)
}

fun Route.orderRoutes() = authenticate(Security.JWT_AUTH) {
    route("/api/orders") {

        // POST /api/orders/checkout
        post("/checkout") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val currency = Config.currency

            // 1) Kosar
            val cart = Database.query { conn -> getCart(conn, uid) }
            if (cart.items.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A kosar ures."))
                return@post
            }

            val itemsForOrder = cart.items.map {
                Barion.OrderItem(title = it.title ?: "", price = it.price, quantity = it.quantity)
            }
            val total = Barion.roundAmount(
                cart.items.sumOf { Barion.roundAmount(it.price, currency) * it.quantity },
                currency,
            )
            val paymentRequestId = UUID.randomUUID().toString()

            val itemsJson: JsonArray = buildJsonArray {
                cart.items.forEach {
                    add(buildJsonObject {
                        put("productId", JsonPrimitive(it.productId))
                        put("title", JsonPrimitive(it.title))
                        put("price", JsonPrimitive(it.price))
                        put("quantity", JsonPrimitive(it.quantity))
                    })
                }
            }

            // 2) Rendeles rogzitese 'created' allapotban + user email
            val (orderId, payerEmail) = Database.query { conn ->
                val newId = conn.prepareStatement(
                    """INSERT INTO orders (user_id, status, total, currency, items, payment_request_id)
                       VALUES (?, 'created', ?, ?, ?, ?)
                       RETURNING id"""
                ).use { ps ->
                    ps.setInt(1, uid)
                    ps.setDouble(2, total)
                    ps.setString(3, currency)
                    ps.setObject(4, jsonbParam(itemsJson))
                    ps.setString(5, paymentRequestId)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt("id") }
                }
                val email = conn.prepareStatement("SELECT email FROM users WHERE id = ?").use { ps ->
                    ps.setInt(1, uid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString("email") else null }
                }
                newId to (email ?: Config.barionPayee)
            }

            // 3) Barion fizetes inditasa
            val base = baseUrl(call)
            val redirectUrl = Config.frontendUrl ?: "$base/payment-result"
            val callbackUrl = "$base/api/payment/callback"

            val orderForPayment = Barion.OrderForPayment(
                id = orderId,
                currency = currency,
                paymentRequestId = paymentRequestId,
                items = itemsForOrder,
            )

            try {
                val payment = Barion.startPayment(orderForPayment, payerEmail, redirectUrl, callbackUrl)
                val paymentId = (payment["PaymentId"] as? JsonPrimitive)?.content
                val gatewayUrl = (payment["GatewayUrl"] as? JsonPrimitive)?.content

                Database.query { conn ->
                    conn.prepareStatement(
                        "UPDATE orders SET status = 'pending', barion_payment_id = ?, updated_at = now() WHERE id = ?"
                    ).use { ps ->
                        ps.setString(1, paymentId)
                        ps.setInt(2, orderId)
                        ps.executeUpdate()
                    }
                }

                call.respond(
                    HttpStatusCode.Created,
                    CheckoutResponse(
                        orderId = orderId,
                        paymentId = paymentId,
                        gatewayUrl = gatewayUrl,
                        total = total,
                        currency = currency,
                    ),
                )
            } catch (e: Exception) {
                Database.query { conn ->
                    conn.prepareStatement(
                        "UPDATE orders SET status = 'payment_init_failed', updated_at = now() WHERE id = ?"
                    ).use { ps ->
                        ps.setInt(1, orderId)
                        ps.executeUpdate()
                    }
                }
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("Fizetes inditasa sikertelen.", e.message),
                )
            }
        }

        // GET /api/orders  -> sajat rendelesek
        get {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val orders = Database.query { conn ->
                buildJsonArray {
                    conn.prepareStatement(
                        "SELECT * FROM orders WHERE user_id = ? ORDER BY created_at DESC"
                    ).use { ps ->
                        ps.setInt(1, uid)
                        ps.executeQuery().use { rs -> while (rs.next()) add(orderRowToJson(rs)) }
                    }
                }
            }
            call.respond(orders)
        }

        // GET /api/orders/{id}
        get("/{id}") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rendeles nem talalhato."))
                return@get
            }
            val order = Database.query { conn ->
                conn.prepareStatement("SELECT * FROM orders WHERE id = ? AND user_id = ?").use { ps ->
                    ps.setInt(1, id)
                    ps.setInt(2, uid)
                    ps.executeQuery().use { rs -> if (rs.next()) orderRowToJson(rs) else null }
                }
            }
            if (order == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rendeles nem talalhato."))
            } else {
                call.respond(order)
            }
        }
    }
}

// A backend publikus URL-je (Barion ide hivja vissza a callbacket).
// BASE_URL env elsobbseget elvez; kulonben a keresbol probaljuk (x-forwarded-proto / host).
private fun baseUrl(call: io.ktor.server.application.ApplicationCall): String {
    Config.baseUrl?.let { return it }
    val origin = call.request.origin
    val proto = call.request.headers["x-forwarded-proto"] ?: origin.scheme
    val host = call.request.headers["host"] ?: "${origin.serverHost}:${origin.serverPort}"
    return "$proto://$host"
}
