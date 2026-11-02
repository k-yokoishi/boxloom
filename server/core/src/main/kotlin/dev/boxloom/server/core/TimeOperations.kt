package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface TimeOperations {
    fun getTime(request: GetTimeRequest): CompletableFuture<TimeResult>

    fun setTime(request: SetTimeRequest): CompletableFuture<TimeResult>

    fun addTime(request: AddTimeRequest): CompletableFuture<TimeResult>
}

data class GetTimeRequest(val clock: String)

data class SetTimeRequest(val clock: String, val time: TimeValue)

data class AddTimeRequest(val clock: String, val ticks: Int)

data class TimeResult(val clock: String, val ticks: Long)

sealed interface TimeValue {
    data class Ticks(val ticks: Int) : TimeValue

    data class Marker(val marker: String) : TimeValue
}
