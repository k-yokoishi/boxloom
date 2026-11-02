package dev.boxloom.server.core

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityQueryTest {
    private val origin = EntityQueryCenter(0.0, 64.0, 0.0)
    private val far = snapshot("far", "minecraft:zombie", 6.0)
    private val boundary = snapshot("boundary", "minecraft:zombie", 3.0, 68.0)
    private val center = snapshot("center", "minecraft:zombie", 0.0)
    private val item = snapshot("item", "minecraft:item", 1.0)
    private val entities = listOf(far, item, boundary, center)

    @Test
    fun `default query includes every type in input order`() {
        assertEquals(entities, GetEntitiesRequest("minecraft:overworld").select(entities.asSequence()))
        assertEquals(listOf(far), GetEntitiesRequest("minecraft:overworld", limit = 1).select(entities.asSequence()))
    }

    @Test
    fun `filters exact type and inclusive three dimensional sphere before sorting and limiting`() {
        val request = GetEntitiesRequest("minecraft:overworld", "minecraft:zombie", origin, 5.0, EntitySort.NEAREST, 1)
        assertEquals(listOf(center), request.select(entities.asSequence()))
        assertEquals(listOf(boundary, center), request.copy(sort = EntitySort.ARBITRARY, limit = null).select(entities.asSequence()))
        assertEquals(listOf(center), request.copy(radius = 0.0).select(entities.asSequence()))
    }

    @Test
    fun `distance sorting works without a radius and returns the furthest first`() {
        val request = GetEntitiesRequest("minecraft:overworld", center = origin, sort = EntitySort.FURTHEST, limit = 2)
        assertEquals(listOf(far, boundary), request.select(entities.asSequence()))
        assertEquals(listOf(center, item), request.copy(sort = EntitySort.NEAREST).select(entities.asSequence()))
    }

    @Test
    fun `equal distances use uuid as a stable tiebreaker in either direction`() {
        val a = snapshot("a", "minecraft:pig", -1.0)
        val b = snapshot("b", "minecraft:pig", 1.0)
        for (sort in listOf(EntitySort.NEAREST, EntitySort.FURTHEST)) {
            val request = GetEntitiesRequest("minecraft:overworld", center = origin, sort = sort)
            assertEquals(listOf(a, b), request.select(sequenceOf(b, a)))
        }
    }

    @Test
    fun `empty matches return an empty list and large distances do not overflow when squared`() {
        assertEquals(emptyList(), GetEntitiesRequest("minecraft:overworld", "minecraft:arrow").select(entities.asSequence()))
        val request = GetEntitiesRequest("minecraft:overworld", center = origin, radius = 1e200)
        val outside = snapshot("outside", "minecraft:zombie", 2e200)
        assertEquals(listOf(center), request.select(sequenceOf(outside, center)))
    }

    private fun snapshot(uuid: String, type: String, x: Double, y: Double = 64.0) =
        EntitySnapshot(uuid, type, "minecraft:overworld", x, y, 0.0, 0f, 0f, type, null)
}
