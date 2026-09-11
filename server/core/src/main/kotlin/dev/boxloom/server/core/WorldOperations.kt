package dev.boxloom.server.core

import java.util.concurrent.CompletableFuture

interface WorldOperations {
    fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult>

    fun summon(request: SummonRequest): CompletableFuture<SummonResult>
}

data class SetBlockRequest(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)

data class SetBlockResult(
    val changed: Boolean,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
)

data class SummonRequest(
    val dimension: String,
    val entity: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val nbt: NbtValue.Compound? = null,
)

data class SummonResult(
    val uuid: String,
    val entity: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

sealed interface NbtValue {
    data class Compound(val values: Map<String, NbtValue>) : NbtValue

    data class ListValue(val values: List<NbtValue>) : NbtValue

    data class StringValue(val value: String) : NbtValue

    data class BooleanValue(val value: Boolean) : NbtValue

    data class IntValue(val value: Int) : NbtValue

    data class LongValue(val value: Long) : NbtValue

    data class DoubleValue(val value: Double) : NbtValue
}
