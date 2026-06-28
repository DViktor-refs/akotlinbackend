package com.webshop

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import io.ktor.http.encodeURLParameter
import kotlin.math.roundToLong

/**
 * Barion Smart Gateway integracio.
 * Doksi: https://docs.barion.com/Payment-Start-v2 , https://docs.barion.com/Payment-GetPaymentState-v2
 */
object Barion {
    private val base: String
        get() = if (Config.barionEnv == "prod") "https://api.barion.com" else "https://api.test.barion.com"

    fun env(): String = Config.barionEnv

    /** HUF-nal egesz szam kell, mas devizanal 2 tizedes. */
    fun roundAmount(value: Double, currency: String): Double {
        return if (currency == "HUF") value.roundToLong().toDouble()
        else (value * 100).roundToLong() / 100.0
    }

    data class OrderItem(val title: String, val price: Double, val quantity: Int)

    data class OrderForPayment(
        val id: Int,
        val currency: String,
        val paymentRequestId: String,
        val items: List<OrderItem>,
    )

    /** Fizetes inditasa. Visszaadja a Barion valaszat (PaymentId, GatewayUrl, ...). */
    suspend fun startPayment(
        order: OrderForPayment,
        payerEmail: String?,
        redirectUrl: String,
        callbackUrl: String,
    ): JsonObject {
        val posKey = Config.barionPosKey ?: throw IllegalStateException("BARION_POSKEY nincs beallitva.")
        val payee = Config.barionPayee ?: throw IllegalStateException("BARION_PAYEE (bolt email) nincs beallitva.")

        val items: List<JsonObject> = order.items.map { it ->
            val unitPrice = roundAmount(it.price, order.currency)
            val itemTotal = roundAmount(unitPrice * it.quantity, order.currency)
            buildJsonObject {
                put("Name", JsonPrimitive(it.title.take(250)))
                put("Description", JsonPrimitive(it.title.take(500)))
                put("Quantity", JsonPrimitive(it.quantity))
                put("Unit", JsonPrimitive("db"))
                put("UnitPrice", JsonPrimitive(unitPrice))
                put("ItemTotal", JsonPrimitive(itemTotal))
            }
        }

        val transactionTotal = roundAmount(
            items.sumOf { (it["ItemTotal"] as JsonPrimitive).contentOrNull!!.toDouble() },
            order.currency,
        )

        val body = buildJsonObject {
            put("POSKey", JsonPrimitive(posKey))
            put("PaymentType", JsonPrimitive("Immediate"))
            put("GuestCheckOut", JsonPrimitive(true))
            put("FundingSources", buildJsonArray { add(JsonPrimitive("All")) })
            put("PaymentRequestId", JsonPrimitive(order.paymentRequestId))
            if (!payerEmail.isNullOrBlank()) put("PayerHint", JsonPrimitive(payerEmail))
            put("Locale", JsonPrimitive(Config.barionLocale))
            put("Currency", JsonPrimitive(order.currency))
            put("RedirectUrl", JsonPrimitive(redirectUrl))
            put("CallbackUrl", JsonPrimitive(callbackUrl))
            put("Transactions", buildJsonArray {
                add(buildJsonObject {
                    put("POSTransactionId", JsonPrimitive("order-${order.id}"))
                    put("Payee", JsonPrimitive(payee))
                    put("Total", JsonPrimitive(transactionTotal))
                    put("Items", JsonArray(items))
                })
            })
        }

        val res: HttpResponse = httpClient.post("$base/v2/Payment/Start") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val data: JsonObject = runCatching { res.body<JsonElement>().jsonObject }.getOrElse { buildJsonObject {} }

        val errors = data["Errors"] as? JsonArray
        val ok = res.status.value in 200..299
        if (!ok || (errors != null && errors.isNotEmpty())) {
            val detail = if (errors != null) errors.toString() else "HTTP ${res.status.value}"
            throw RuntimeException("Barion Start hiba: $detail")
        }
        return data
    }

    /** Fizetes allapotanak lekerdezese. */
    suspend fun getPaymentState(paymentId: String): JsonObject {
        val posKey = Config.barionPosKey ?: ""
        val url = "$base/v2/Payment/GetPaymentState" +
            "?POSKey=${posKey.encodeURLParameter()}" +
            "&PaymentId=${paymentId.encodeURLParameter()}"
        val res: HttpResponse = httpClient.get(url)
        if (res.status.value !in 200..299) {
            throw RuntimeException("Barion GetPaymentState hiba: HTTP ${res.status.value}")
        }
        return runCatching { res.body<JsonElement>().jsonObject }.getOrElse { buildJsonObject {} }
    }

    /** Barion statusz -> sajat order statusz. */
    fun mapBarionStatus(barionStatus: String?): String = when (barionStatus) {
        "Succeeded" -> "paid"
        "Canceled" -> "canceled"
        "Expired" -> "expired"
        "Failed" -> "failed"
        "PartiallySucceeded" -> "partially_paid"
        else -> "pending" // Prepared / Started / InProgress / Reserved / Authorized
    }

    fun statusOf(state: JsonObject): String? =
        (state["Status"] as? JsonPrimitive)?.contentOrNull
}
