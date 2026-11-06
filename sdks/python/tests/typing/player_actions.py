from typing import Optional

from boxloom import Block, BoxloomClient, Entity, Player, PlayerAttackResult, PlayerMiningResult, TargetedBlock, get_block, get_player, get_players
from typing_extensions import assert_type

player = get_player("Steve")
assert_type(player, Player)
assert_type(player.get_targeted_block(), Optional[TargetedBlock])
assert_type(player.attack(), PlayerAttackResult)
assert_type(player.attack().entity, Optional[Entity])
assert_type(player.start_mining(), PlayerMiningResult)
assert_type(player.stop_mining(), PlayerMiningResult)
assert_type(get_players()[0].attack(), PlayerAttackResult)
assert_type(BoxloomClient(base_url="http://localhost:28886").get_player("Steve"), Player)


def describe_block(block: Block) -> str:
    return f"{block.block} at {block.dimension}: {block.x}, {block.y}, {block.z}"


describe_block(get_block(0, 64, 0))
assert_type(get_block(0, 64, 0), Block)
target = player.get_targeted_block()
if target is not None:
    describe_block(target)
