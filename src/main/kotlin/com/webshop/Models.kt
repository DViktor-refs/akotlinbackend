package com.webshop

import kotlinx.serialization.Serializable

// ---- Kozos ----
@Serializable
data class ErrorResponse(val error: String, val detail: String? = null)

@Serializable
data class OkResponse(val ok: Boolean = true)

// ---- Auth ----
@Serializable
data class LoginRequest(val username: String? = null, val password: String? = null)

@Serializable
data class RegisterRequest(
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
)

@Serializable
data class PublicUser(val id: Int, val username: String, val role: String)

@Serializable
data class AuthResponse(val token: String, val user: PublicUser)

@Serializable
data class MeResponse(
    val id: Int,
    val username: String,
    val role: String,
    val email: String? = null,
    val created_at: String? = null,
)

// ---- Kosar ----
@Serializable
data class CartAddRequest(val productId: Int? = null, val quantity: Int? = null)

@Serializable
data class CartQtyRequest(val quantity: Int? = null)

@Serializable
data class CartItem(
    val productId: Int,
    val title: String?,
    val thumbnail: String?,
    val price: Double,
    val quantity: Int,
    val lineTotal: Double,
    val stock: Int?,
)

@Serializable
data class CartResponse(val items: List<CartItem>, val total: Double, val count: Int)

// ---- Rendeles / checkout ----
@Serializable
data class CheckoutResponse(
    val orderId: Int,
    val paymentId: String?,
    val gatewayUrl: String?,
    val total: Double,
    val currency: String,
)

// ---- Fizetes statusz ----
@Serializable
data class PaymentStatusResponse(
    val orderId: Int,
    val status: String,
    val barionStatus: String? = null,
    val total: Double,
    val currency: String,
)

@Serializable
data class ReseedResponse(val ok: Boolean = true, val count: Int)
