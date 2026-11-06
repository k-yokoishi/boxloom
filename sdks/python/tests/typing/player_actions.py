from typing import Optional

from boxloom import BoxloomClient, Player, PlayerAttackResult, TargetedBlock, get_player, get_players
from typing_extensions import assert_type

player = get_player("Steve")
assert_type(player, Player)
assert_type(player.get_targeted_block(), Optional[TargetedBlock])
assert_type(player.attack(), PlayerAttackResult)
assert_type(get_players()[0].attack(), PlayerAttackResult)
assert_type(BoxloomClient(base_url="http://localhost:28886").get_player("Steve"), Player)
