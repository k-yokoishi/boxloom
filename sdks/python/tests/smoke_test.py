from importlib.metadata import version

import boxloom
from boxloom import (
    Player,
    PlayerPosition,
    SummonResult,
    get_player_position,
    get_players,
    init,
    say,
    set_block,
    summon,
)


assert version("boxloom") == boxloom.__version__
assert callable(init)
assert callable(say)
assert callable(get_player_position)
assert callable(get_players)
assert callable(set_block)
assert callable(summon)
player = Player(
    username="Player",
    uuid="00000000-0000-0000-0000-000000000000",
)
assert player.username == "Player"
position = PlayerPosition(
    username="Player",
    uuid="00000000-0000-0000-0000-000000000000",
    dimension="minecraft:overworld",
    x=1.9,
    y=64.0,
    z=-0.1,
    yaw=0.0,
    pitch=0.0,
)
assert position.block_coordinates() == (
    1,
    64,
    -1,
)
summon_result = SummonResult(
    uuid="00000000-0000-0000-0000-000000000001",
    entity="minecraft:pig",
    dimension="minecraft:overworld",
    x=1.0,
    y=64.0,
    z=2.0,
)
assert summon_result.entity == "minecraft:pig"
