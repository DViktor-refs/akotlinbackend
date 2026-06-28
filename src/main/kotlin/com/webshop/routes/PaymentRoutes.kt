package com.webshop.routes

import com.webshop.Barion
import com.webshop.Database
import com.webshop.ErrorResponse
import com.webshop.PaymentStatusResponse
import com.webshop.security.Security
import com.webshop.security.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("payment")

// Barion IPN callback. ?paymentId=... -el hiv (GET es POST is lehet).
private suspend fun io.ktor.server.application.ApplicationCall.handleCallback() {
    val fromQuery = request.queryParameters["paymentId"]
    val paymentId = fromQuery ?: runCatching { receiveParameters()["paymentId"] }.getOrNull()
    if (paymentId.isNullOrBlank()) {
        respondText("Hianyzo paymentId", status = HttpStatusCode.BadRequest)
        return
    }

    try {
        val state = Barion.getPaymentState(paymentId)
        val newStatus = Barion.mapBarionStatus(Barion.statusOf(state))

        val affectedUserId = Database.query { conn ->
            val uid = conn.prepareStatement(
                "UPDATE orders SET status = ?, updated_at = now() WHERE barion_payment_id = ? RETURNING user_id"
            ).use { ps ->
                ps.setString(1, newStatus)
                ps.setString(2, paymentId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt("user_id") else null }
            }
            // Sikeres fizetes -> a user kosarat uritjuk
            if (newStatus == "paid" && uid != null) {
                conn.prepareStatement("DELETE FROM carts WHERE user_id = ?").use { ps ->
                    ps.setInt(1, uid)
                    ps.executeUpdate()
                }
            }
            uid
        }
        // Barionnak eleg a 200-as valasz
        respondText("OK", status = HttpStatusCode.OK)
        log.debug("[payment] callback feldolgozva: paymentId=$paymentId, status=$newStatus, user=$affectedUserId")
    } catch (e: Exception) {
        log.error("[payment] callback hiba: ${e.message}")
        // 200-at kuldunk, hogy a Barion ne probalkozzon vegtelenul; a hibat logoljuk
        respondText("OK", status = HttpStatusCode.OK)
    }
}

fun Route.paymentRoutes() = route("/api/payment") {

    // Barion IPN callback (GET es POST)
    get("/callback") { call.handleCallback() }
    post("/callback") { call.handleCallback() }

    // GET /api/payment/status/{orderId} (bejelentkezve)
    authenticate(Security.JWT_AUTH) {
        get("/status/{orderId}") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val orderId = call.parameters["orderId"]?.toIntOrNull()
            if (orderId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rendeles nem talalhato."))
                return@get
            }

            // Rendeles betoltese
            data class OrderRow(
                val id: Int,
                var status: String,
                val barionPaymentId: String?,
                val total: Double,
                val currency: String,
            )

            val order = Database.query { conn ->
                conn.prepareStatement(
                    "SELECT id, status, barion_payment_id, total, currency FROM orders WHERE id = ? AND user_id = ?"
                ).use { ps ->
                    ps.setInt(1, orderId)
                    ps.setInt(2, uid)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            OrderRow(
                                id = rs.getInt("id"),
                                status = rs.getString("status"),
                                barionPaymentId = rs.getString("barion_payment_id"),
                                total = rs.getBigDecimal("total")?.toDouble() ?: 0.0,
                                currency = rs.getString("currency"),
                            )
                        } else null
                    }
                }
            }

            if (order == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rendeles nem talalhato."))
                return@get
            }

            var barionStatus: String? = null
            if (order.barionPaymentId != null) {
                try {
                    val state = Barion.getPaymentState(order.barionPaymentId)
                    barionStatus = Barion.statusOf(state)
                    val mapped = Barion.mapBarionStatus(barionStatus)
                    if (mapped != order.status) {
                        Database.query { conn ->
                            conn.prepareStatement(
                                "UPDATE orders SET status = ?, updated_at = now() WHERE id = ?"
                            ).use { ps ->
                                ps.setString(1, mapped)
                                ps.setInt(2, order.id)
                                ps.executeUpdate()
                            }
                        }
                        order.status = mapped
                    }
                } catch (_: Exception) {
                    // ha nem elerheto a Barion, a tarolt allapotot adjuk vissza
                }
            }

            call.respond(
                PaymentStatusResponse(
                    orderId = order.id,
                    status = order.status,
                    barionStatus = barionStatus,
                    total = order.total,
                    currency = order.currency,
                )
            )
        }
    }
}
