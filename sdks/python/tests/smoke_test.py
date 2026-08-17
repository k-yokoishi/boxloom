from importlib.metadata import version

import boxloom
from boxloom.events import ChatEventStream
from boxloom import (
    ChatEvent,
    EventCursorExpiredError,
    Player,
    PlayerPosition,
    get_player_position,
    get_players,
    init,
    say,
    set_block,
    watch_chat,
)


assert version("boxloom") == boxloom.__version__
assert callable(init)
assert callable(say)
assert callable(get_player_position)
assert callable(get_players)
assert callable(set_block)
assert callable(watch_chat)
assert boxloom.ChatEventStream is ChatEventStream
assert issubclass(EventCursorExpiredError, boxloom.ApiError)
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
event = ChatEvent(
    id="00000000-0000-0000-0000-000000000000:1",
    timestamp="2026-08-14T00:00:00Z",
    message="hello",
    player=player,
)
assert event.player.username == "Player"
