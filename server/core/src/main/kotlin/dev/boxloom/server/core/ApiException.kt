package dev.boxloom.server.core

class ApiException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException(message)
