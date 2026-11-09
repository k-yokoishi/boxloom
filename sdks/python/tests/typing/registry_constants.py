"""Check completion-visible names and compatibility with the string-based API."""

from typing_extensions import assert_type

from boxloom import (
    BlockPos,
    Vec3,
    BoxloomClient,
    FillResult,
    GiveItemResult,
    KillResult,
    SetBlockResult,
    SummonResult,
    blocks,
    entities,
    items,
    fill,
    give_item,
    get_player,
    kill_entities,
    set_block,
    summon,
)


def check_constants(client: BoxloomClient) -> None:
    assert_type(set_block(BlockPos(0, 100, 0), blocks.DIAMOND_BLOCK), SetBlockResult)
    assert_type(fill(BlockPos(0, 100, 0), BlockPos(4, 100, 4), blocks.GOLD_BLOCK), FillResult)
    assert_type(summon(entities.ZOMBIE, Vec3(0, 100, 0)), SummonResult)
    assert_type(kill_entities(entities.ZOMBIE), KillResult)
    assert_type(client.set_block(BlockPos(0, 100, 0), blocks.DIAMOND_BLOCK), SetBlockResult)
    assert_type(client.fill(BlockPos(0, 100, 0), BlockPos(4, 100, 4), blocks.GOLD_BLOCK), FillResult)
    assert_type(client.summon(entities.ZOMBIE, Vec3(0, 100, 0)), SummonResult)
    assert_type(client.kill_entities(entities.ZOMBIE), KillResult)
    assert_type(items.BONE_MEAL, str)
    assert_type(items.DIAMOND_SWORD, str)
    assert_type(give_item("Steve", items.BONE_MEAL, 3), GiveItemResult)
    assert_type(client.give_item("Steve", items.DIAMOND_SWORD), GiveItemResult)
    player = get_player("Steve")
    if player is not None:
        assert_type(player.give_item(items.STONE, 64), GiveItemResult)

    # Plain strings, including Mod IDs, continue to be accepted.
    assert_type(set_block(BlockPos(0, 100, 0), "example:custom_block"), SetBlockResult)
    assert_type(summon("example:custom_entity", Vec3(0, 100, 0)), SummonResult)


def check_invalid_usage() -> None:
    set_block(BlockPos(0, 100, 0), blocks.DIAMON_BLOCK)  # type: ignore[attr-defined]
    summon(entities.ZOMBE, Vec3(0, 100, 0))  # type: ignore[attr-defined]
    give_item("Steve", items.BONE_MEA, 3)  # type: ignore[attr-defined]
