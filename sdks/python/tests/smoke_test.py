from importlib.metadata import version

import boxloom
from boxloom import PlayerPosition, get_player_position, init, say, set_block


assert version("boxloom") == boxloom.__version__
assert callable(init)
assert callable(say)
assert callable(get_player_position)
assert callable(set_block)
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
