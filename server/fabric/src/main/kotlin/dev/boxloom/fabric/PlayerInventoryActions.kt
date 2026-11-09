package dev.boxloom.fabric

import dev.boxloom.server.core.ApiException
import dev.boxloom.server.core.GiveItemRequest
import dev.boxloom.server.core.GiveItemResult
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.commands.GiveCommand
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal object PlayerInventoryActions {
    fun give(player: ServerPlayer, request: GiveItemRequest): GiveItemResult {
        val id = Identifier.tryParse(request.item)
            ?: throw ApiException(400, "INVALID_ITEM", "Field 'item' is not a valid item ID")
        val item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow {
            ApiException(400, "INVALID_ITEM", "Item '${request.item}' does not exist")
        }
        val template = ItemStack(item)
        if (item == Items.AIR || !template.isItemEnabled(player.level().enabledFeatures())) {
            throw ApiException(400, "INVALID_ITEM", "Item '${request.item}' cannot be given in this world")
        }
        val maxStackSize = template.maxStackSize
        val maxCount = maxStackSize * GiveCommand.MAX_ALLOWED_ITEMSTACKS
        if (request.count !in 1..maxCount) {
            throw ApiException(400, "INVALID_COUNT", "Field 'count' must be between 1 and $maxCount for this item")
        }

        // Follow vanilla GiveCommand: split into legal stacks, then insert or drop.
        // Inventory.add also preserves vanilla's creative-mode overflow behavior.
        var remaining = request.count
        while (remaining > 0) {
            val count = minOf(remaining, maxStackSize)
            remaining -= count
            val stack = template.copyWithCount(count)
            val added = player.inventory.add(stack)
            if (added && stack.isEmpty) {
                player.createItemStackToDrop(template.copy(), false, false)?.let { drop ->
                    drop.makeFakeItem()
                    drop.level().addFreshEntity(drop)
                }
                player.level().playSound(
                    null, player.x, player.y, player.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                    0.2f, ((player.random.nextFloat() - player.random.nextFloat()) * 0.7f + 1.0f) * 2.0f,
                )
            } else {
                player.createItemStackToDrop(stack, false, false)?.let { drop ->
                    drop.setNoPickUpDelay()
                    drop.setTarget(player.uuid)
                    drop.level().addFreshEntity(drop)
                }
            }
        }
        player.inventory.setChanged()
        player.inventoryMenu.broadcastChanges()
        if (player.containerMenu !== player.inventoryMenu) player.containerMenu.broadcastChanges()
        return GiveItemResult(player.name.string, player.uuid.toString(), id.toString(), request.count)
    }
}
