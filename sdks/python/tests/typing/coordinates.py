"""Check coordinate types throughout the public SDK and reject implicit mixing."""

from typing_extensions import assert_type

from boxloom import (
    Block, BlockAttackEvent, BlockPos, BoxloomClient, Entity, FillResult,
    PlayerDeathEvent, PlayerPosition, SetBlockResult, SummonResult, Vec3,
    blocks, entities, fill, get_block, get_entities, get_player_position,
    kill_entities, set_block, summon, teleport_player,
)


def check_coordinates(client: BoxloomClient, event: BlockAttackEvent) -> None:
    position = Vec3(1.5, 64, -0.2)
    block = position.to_block_pos()
    assert_type(position.x, float)
    assert_type(block, BlockPos)
    assert_type(block.x, int)
    assert_type(block.to_vec3(), Vec3)
    assert_type(position + Vec3(0, 1, 0), Vec3)
    assert_type(position - Vec3(0, 1, 0), Vec3)
    assert_type(position * 0.5, Vec3)
    assert_type(2 * position, Vec3)
    assert_type(-position, Vec3)
    assert_type(block + BlockPos(0, 1, 0), BlockPos)
    assert_type(block - BlockPos(0, 1, 0), BlockPos)
    assert_type(block * 2, BlockPos)
    assert_type(2 * block, BlockPos)
    assert_type(-block, BlockPos)
    assert_type(get_player_position("Steve").position, Vec3)
    assert_type(teleport_player("Steve", position), PlayerPosition)
    assert_type(client.teleport_player("Steve", position).position, Vec3)
    assert_type(get_block(block), Block)
    assert_type(client.get_block(block).position, BlockPos)
    assert_type(set_block(block, blocks.STONE), SetBlockResult)
    assert_type(client.set_block(block, blocks.STONE).position, BlockPos)
    assert_type(fill(block, block + BlockPos(2, 0, 0), blocks.STONE), FillResult)
    assert_type(client.fill(block, block, blocks.STONE).start, BlockPos)
    assert_type(client.fill(block, block, blocks.STONE).end, BlockPos)
    assert_type(summon(entities.PIG, position), SummonResult)
    assert_type(client.summon(entities.PIG, position).position, Vec3)
    assert_type(get_entities(center=position)[0].position, Vec3)
    assert_type(client.get_entities(center=position)[0], Entity)
    assert_type(event.position, Vec3)
    assert_type(event.block_position, BlockPos)
    player = client.get_player("Steve")
    if player is not None:
        assert_type(player.get_position(), PlayerPosition)
        assert_type(player.get_position().position, Vec3)
        target = player.get_targeted_block()
        if target is not None:
            assert_type(target.position, BlockPos)
    kill_entities(entities.PIG, center=position, radius=10)
    client.kill_entities(entities.PIG, center=position, radius=10)


def check_death(event: PlayerDeathEvent) -> None:
    assert_type(event.position, Vec3)


def check_invalid_usage(client: BoxloomClient) -> None:
    vector = Vec3(0, 64, 0)
    block = BlockPos(0, 64, 0)
    get_block(vector)  # type: ignore[arg-type]
    client.set_block(vector, blocks.STONE)  # type: ignore[arg-type]
    fill(block, vector, blocks.STONE)  # type: ignore[arg-type]
    summon(entities.PIG, block)  # type: ignore[arg-type]
    client.teleport_player("Steve", block)  # type: ignore[arg-type]
    get_entities(center=block)  # type: ignore[arg-type]
    client.kill_entities(entities.PIG, center=block, radius=10)  # type: ignore[arg-type]
    BlockPos(0.5, 64, 0)  # type: ignore[arg-type]
    vector + block  # type: ignore[operator]
    block - vector  # type: ignore[operator]
    block * 0.5  # type: ignore[operator]
