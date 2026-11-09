import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.metadata import version
from importlib.resources import files

import boxloom
from boxloom.events import ChatEventStream, EventStream, EventSubscription, EventType, PlayerDeathEvent
from boxloom import (
    BlockPos,
    Vec3,
    ChatEvent,
    EventCursorExpiredError,
    Entity,
    FillResult,
    Block,
    KillResult,
    Player,
    PlayerMiningResult,
    PlayerUseResult,
    PlayerItemUseResult,
    GameMode,
    PlayerGameMode,
    PlayerPosition,
    blocks,
    entities,
    items,
    fill,
    get_block,
    get_entities,
    get_player,
    get_player_position,
    get_players,
    init,
    kill_entities,
    kill_entity,
    say,
    set_block,
    set_player_gamemode,
    summon,
    teleport_player,
    watch_chat,
    watch_event,
    subscribe_event,
)


assert version("boxloom") == boxloom.__version__
assert callable(init)
assert callable(say)
assert callable(fill)
assert callable(get_block)
assert callable(get_entities)
assert Entity.__name__ == "Entity"
assert callable(get_player_position)
assert callable(get_players)
assert callable(set_block)
assert callable(set_player_gamemode)
assert boxloom.GameMode is GameMode
assert PlayerGameMode("Steve", "test-uuid", "survival").mode == "survival"
assert blocks.DIAMOND_BLOCK == "minecraft:diamond_block"
assert entities.ZOMBIE == "minecraft:zombie"
assert items.BONE_MEAL == "minecraft:bone_meal"
assert items.DIAMOND_SWORD == "minecraft:diamond_sword"
assert items.STONE == blocks.STONE
assert boxloom.items is items
assert "items" in boxloom.__all__
assert blocks.MINECRAFT_VERSION == entities.MINECRAFT_VERSION == items.MINECRAFT_VERSION
assert blocks.MINECRAFT_VERSION
assert not hasattr(boxloom, "setblock")
assert callable(summon)
assert callable(kill_entity)
assert callable(kill_entities)
assert KillResult.__name__ == "KillResult"
assert callable(teleport_player)
assert callable(watch_chat)
assert callable(watch_event)
assert callable(subscribe_event)
assert boxloom.EventSubscription is EventSubscription
assert boxloom.EventStream is EventStream
assert boxloom.EventType is EventType
assert boxloom.PlayerDeathEvent is PlayerDeathEvent
for event_name, cls in (
    ("player.swing", boxloom.PlayerSwingEvent),
    ("block.attack", boxloom.BlockAttackEvent),
    ("block.use", boxloom.BlockUseEvent),
    ("item.use", boxloom.ItemUseEvent),
):
    assert getattr(boxloom.events, cls.__name__) is cls
assert files("boxloom").joinpath("py.typed").is_file()
assert FillResult.__name__ == "FillResult"
assert Block.__name__ == "Block"
assert Block("minecraft:overworld", BlockPos(0, 64, 0), "minecraft:wheat", {"age": "3"}).properties == {"age": "3"}
assert PlayerMiningResult(True, None, False).active
assert PlayerUseResult(False, False, None).used is False
assert callable(Player.use)
assert callable(Player.select_slot)
assert callable(boxloom.select_slot)
assert callable(boxloom.BoxloomClient.select_slot)
assert boxloom.SelectSlotResult("Steve", "test-uuid", 0).slot == 0
assert boxloom.GivenItemSlot(2, 4).count == 4
assert boxloom.GiveItemResult("Steve", "test-uuid", "minecraft:stone", 1).slots == ()
assert callable(Player.start_using_item)
assert callable(Player.stop_using_item)
assert PlayerItemUseResult(False, None, 0).remaining_ticks == 0
assert callable(Player.start_mining)
assert callable(Player.stop_mining)
assert boxloom.ChatEventStream is ChatEventStream
assert issubclass(EventCursorExpiredError, boxloom.ApiError)


class _ApiHandler(BaseHTTPRequestHandler):
    path = None
    authorization = None

    def do_GET(self):
        self.__class__.path = self.path
        self.__class__.authorization = self.headers.get("Authorization")
        missing = self.path.endswith("/position")
        payload = {"error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}} if missing else {"players": []}
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(404 if missing else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


server = ThreadingHTTPServer(("127.0.0.1", 0), _ApiHandler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    os.environ["BOXLOOM_BASE_URL"] = f"http://127.0.0.1:{server.server_port}"
    os.environ.pop("BOXLOOM_AUTH_TOKEN", None)
    assert get_players() == []
    assert _ApiHandler.path == "/v1/players"
    assert _ApiHandler.authorization is None
    assert get_player("Missing") is None
    assert _ApiHandler.path == "/v1/players/Missing/position"
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=2)

player = Player(
    username="Player",
    uuid="00000000-0000-0000-0000-000000000000",
)
assert player.username == "Player"
position = PlayerPosition(
    username="Player",
    uuid="00000000-0000-0000-0000-000000000000",
    dimension="minecraft:overworld",
    position=Vec3(1.9, 64.0, -0.1),
    yaw=0.0,
    pitch=0.0,
)
assert position.position.to_block_pos() == BlockPos(1, 64, -1)
event = ChatEvent(
    id="00000000-0000-0000-0000-000000000000:1",
    timestamp="2026-08-14T00:00:00Z",
    message="hello",
    player=player,
)
assert event.player.username == "Player"
assert event.type == "chat.message"
death = PlayerDeathEvent(
    id="00000000-0000-0000-0000-000000000000:2",
    timestamp="2026-08-14T00:00:01Z",
    message="Player died",
    player=player,
    dimension="minecraft:overworld",
    position=Vec3(1.5, 64.0, -2.25),
)
assert death.type == "player.death"
with watch_event("player.death", reconnect=False) as deaths:
    assert isinstance(deaths, EventStream)
for event_name in ("player.swing", "block.attack", "block.use", "item.use"):
    with watch_event(event_name, reconnect=False) as actions:
        assert isinstance(actions, EventStream)
summon_result = boxloom.SummonResult(
    uuid="00000000-0000-0000-0000-000000000001",
    entity="minecraft:pig",
    dimension="minecraft:overworld",
    position=Vec3(0.0, 64.0, 0.0),
)
assert summon_result.entity == "minecraft:pig"
