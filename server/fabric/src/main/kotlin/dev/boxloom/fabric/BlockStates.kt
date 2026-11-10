package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import net.minecraft.commands.arguments.blocks.BlockInput
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/** Canonical block-state names and values, with no per-block SDK schema. */
internal object BlockStates {
    fun input(block: Block, properties: Map<String, String>): BlockInput {
        val state = resolve(block, properties)
        val defined = properties.keys.map { block.stateDefinition.getProperty(it)!! }.toSet()
        return BlockInput(state, defined, null)
    }

    fun properties(state: BlockState): Map<String, String> =
        state.values.toList().associate { it.property().name to it.valueName() }

    fun resolve(block: Block, properties: Map<String, String>): BlockState {
        var state = block.defaultBlockState()
        for ((name, value) in properties) {
            val property = block.stateDefinition.getProperty(name)
                ?: throw ApiException(400, "INVALID_BLOCK_PROPERTY", "Block has no property '$name'")
            state = withProperty(state, property, value)
        }
        return state
    }

    private fun <T : Comparable<T>> withProperty(state: BlockState, property: Property<T>, value: String): BlockState {
        val parsed = property.getValue(value).orElseThrow {
            ApiException(400, "INVALID_BLOCK_PROPERTY", "Invalid value '$value' for property '${property.name}'")
        }
        return state.setValue(property, parsed)
    }
}
