from typing import Mapping, Optional

from boxloom import Block, BoxloomClient, Entity, Player, PlayerAttackResult, PlayerUseResult, PlayerItemUseResult, PlayerMiningResult, TargetedBlock, get_block, get_player, get_players, set_block, SetBlockResult
from typing_extensions import assert_type

player = get_player("Steve")
assert_type(player, Optional[Player])
assert player is not None
assert_type(player, Player)
assert_type(player.get_targeted_block(), Optional[TargetedBlock])
assert_type(player.attack(), PlayerAttackResult)
assert_type(player.use(), PlayerUseResult)
assert_type(player.use().entity, Optional[Entity])
assert_type(BoxloomClient(base_url="http://localhost:28886").use_player_item("Steve"), PlayerUseResult)
assert_type(player.attack().entity, Optional[Entity])
assert_type(player.start_mining(), PlayerMiningResult)
assert_type(player.stop_mining(), PlayerMiningResult)
assert_type(get_players()[0].attack(), PlayerAttackResult)
assert_type(BoxloomClient(base_url="http://localhost:28886").get_player("Steve"), Optional[Player])


def describe_block(block: Block) -> str:
    return f"{block.block} at {block.dimension}: {block.x}, {block.y}, {block.z}"


describe_block(get_block(0, 64, 0))
assert_type(get_block(0, 64, 0), Block)
target = player.get_targeted_block()
if target is not None:
    describe_block(target)

assert_type(get_block(0, 64, 0).properties, Mapping[str, str])
assert_type(set_block(0, 64, 0, "minecraft:wheat", properties={"age": "3"}), SetBlockResult)
assert_type(set_block(0, 64, 0, "minecraft:wheat").properties, Mapping[str, str])
if target is not None:
    assert_type(target.properties, Mapping[str, str])

assert_type(player.start_using_item(), PlayerItemUseResult)
assert_type(player.stop_using_item(), PlayerItemUseResult)
assert_type(player.start_using_item().item, Optional[str])
assert_type(player.start_using_item().remaining_ticks, int)
client = BoxloomClient(base_url="http://localhost:28886")
assert_type(client.start_using_player_item("Steve"), PlayerItemUseResult)
assert_type(client.stop_using_player_item("Steve"), PlayerItemUseResult)
