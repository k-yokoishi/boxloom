"""Check completion-visible names and compatibility with the string-based API."""

from typing_extensions import assert_type

from boxloom import (
    BoxloomClient,
    FillResult,
    KillResult,
    SetBlockResult,
    SummonResult,
    blocks,
    entities,
    fill,
    kill_entities,
    set_block,
    summon,
)


def check_constants(client: BoxloomClient) -> None:
    assert_type(set_block(0, 100, 0, blocks.DIAMOND_BLOCK), SetBlockResult)
    assert_type(fill(0, 100, 0, 4, 100, 4, blocks.GOLD_BLOCK), FillResult)
    assert_type(summon(entities.ZOMBIE, 0, 100, 0), SummonResult)
    assert_type(kill_entities(entities.ZOMBIE), KillResult)
    assert_type(client.set_block(0, 100, 0, blocks.DIAMOND_BLOCK), SetBlockResult)
    assert_type(client.fill(0, 100, 0, 4, 100, 4, blocks.GOLD_BLOCK), FillResult)
    assert_type(client.summon(entities.ZOMBIE, 0, 100, 0), SummonResult)
    assert_type(client.kill_entities(entities.ZOMBIE), KillResult)

    # Plain strings, including Mod IDs, continue to be accepted.
    assert_type(set_block(0, 100, 0, "example:custom_block"), SetBlockResult)
    assert_type(summon("example:custom_entity", 0, 100, 0), SummonResult)


def check_invalid_usage() -> None:
    set_block(0, 100, 0, blocks.DIAMON_BLOCK)  # type: ignore[attr-defined]
    summon(entities.ZOMBE, 0, 100, 0)  # type: ignore[attr-defined]
