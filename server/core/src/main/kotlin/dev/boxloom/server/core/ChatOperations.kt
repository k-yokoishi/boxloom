package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface ChatOperations {
    fun say(request: SayRequest): CompletableFuture<SayResult>
}

data class SayRequest(
    val message: String,
)

data class SayResult(
    val message: String,
    val recipients: Int,
)
