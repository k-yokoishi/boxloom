package dev.boxloom.server.core

import java.util.UUID
import java.util.concurrent.CompletableFuture

interface WorldOperations {
    fun getEntities(request: GetEntitiesRequest): CompletableFuture<List<EntitySnapshot>>

    fun getBlock(request: GetBlockRequest): CompletableFuture<GetBlockResult>

    fun getBlocks(request: GetBlocksRequest): CompletableFuture<List<GetBlockResult>>

    fun setBlock(request: SetBlockRequest): CompletableFuture<SetBlockResult>

    fun fill(request: FillRequest): CompletableFuture<FillResult>

    fun summon(request: SummonRequest): CompletableFuture<SummonResult>

    fun killEntity(request: KillEntityRequest): CompletableFuture<KillResult>

    fun killEntities(request: KillEntitiesRequest): CompletableFuture<KillResult>
}

data class GetBlockRequest(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

data class GetBlockResult(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
    val properties: Map<String, String> = emptyMap(),
)

/** Inclusive cuboid, read from its minimum corner with X changing fastest, then Y, then Z. */
data class GetBlocksRequest(
    val dimension: String,
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
) {
    init {
        var volume = 1L
        val spans = listOf(
            kotlin.math.abs(x2.toLong() - x1.toLong()) + 1L,
            kotlin.math.abs(y2.toLong() - y1.toLong()) + 1L,
            kotlin.math.abs(z2.toLong() - z1.toLong()) + 1L,
        )
        for (span in spans) {
            if (span > 32_768 / volume) {
                throw JsonSupport.invalid("Get blocks region must contain at most 32768 blocks")
            }
            volume *= span
        }
    }
}

data class SetBlockRequest(
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
    val properties: Map<String, String> = emptyMap(),
)

data class SetBlockResult(
    val changed: Boolean,
    val dimension: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val block: String,
    val properties: Map<String, String> = emptyMap(),
)

enum class FillMode(val value: String) {
    REPLACE("replace"), DESTROY("destroy"), KEEP("keep"),
    OUTLINE("outline"), HOLLOW("hollow"), STRICT("strict"),
}

data class FillRequest(
    val dimension: String,
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
    val block: String,
    val mode: FillMode = FillMode.REPLACE,
    val replace: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val replaceProperties: Map<String, String> = emptyMap(),
) {
    init {
        if (mode == FillMode.KEEP && replace != null) {
            throw JsonSupport.invalid("Field 'replace' cannot be used with mode 'keep'")
        }
        if (replace == null && replaceProperties.isNotEmpty()) {
            throw JsonSupport.invalid("Field 'replaceProperties' requires 'replace'")
        }
    }
}

data class FillResult(
    val changedBlocks: Int,
    val dimension: String,
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
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

data class KillEntityRequest(
    val dimension: String,
    val uuid: UUID,
)

data class KillEntitiesRequest(
    val dimension: String,
    val entity: String,
    val range: EntityKillRange? = null,
)

data class EntityKillRange(
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
)

data class KillResult(
    val dimension: String,
    val killedEntities: Int,
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
