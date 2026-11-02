package dev.boxloom.server.core

import kotlin.math.hypot

data class EntitySnapshot(
    val uuid: String,
    val entity: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val name: String,
    val health: Float?,
)

data class EntityQueryCenter(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(entity: EntitySnapshot): Double =
        hypot(hypot(entity.x - x, entity.y - y), entity.z - z)
}

enum class EntitySort(val value: String) {
    ARBITRARY("arbitrary"),
    NEAREST("nearest"),
    FURTHEST("furthest"),
}

data class GetEntitiesRequest(
    val dimension: String,
    val entity: String? = null,
    val center: EntityQueryCenter? = null,
    val radius: Double? = null,
    val sort: EntitySort = EntitySort.ARBITRARY,
    val limit: Int? = null,
) {
    /** Select from live snapshots in the resolved dimension, using a resolved type ID. */
    fun select(entities: Sequence<EntitySnapshot>): List<EntitySnapshot> {
        var selected = entities.filter { snapshot ->
            (entity == null || snapshot.entity == entity) &&
                (radius == null || requireNotNull(center).distanceTo(snapshot) <= radius)
        }
        if (sort != EntitySort.ARBITRARY) {
            val origin = requireNotNull(center)
            val byDistance = if (sort == EntitySort.NEAREST) {
                compareBy<EntitySnapshot> { origin.distanceTo(it) }
            } else {
                compareByDescending<EntitySnapshot> { origin.distanceTo(it) }
            }
            selected = selected.sortedWith(byDistance.thenBy { it.uuid })
        }
        return (limit?.let { selected.take(it) } ?: selected).toList()
    }
}
