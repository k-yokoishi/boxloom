import json
from http.server import BaseHTTPRequestHandler
from importlib.metadata import version
from importlib.resources import files

import pytest

import boxloom
from boxloom import (
    Block,
    BlockPos,
    ChatEvent,
    Entity,
    EventCursorExpiredError,
    FillResult,
    GameMode,
    KillResult,
    Player,
    PlayerGameMode,
    PlayerItemUseResult,
    PlayerMiningResult,
    PlayerLocation,
    PlayerUseResult,
    Vec3,
    blocks,
    entities,
    fill,
    get_block,
    get_entities,
    get_player,
    get_player_location,
    get_players,
    init,
    items,
    kill_entities,
    kill_entity,
    say,
    set_block,
    set_player_gamemode,
    subscribe_event,
    summon,
    teleport_player,
    watch_chat,
    watch_event,
)
from boxloom.events import (
    ChatEventStream,
    EventStream,
    EventSubscription,
    EventType,
    PlayerDeathEvent,
)


class _ApiHandler(BaseHTTPRequestHandler):
    path = None
    authorization = None

    def do_GET(self):
        self.__class__.path = self.path
        self.__class__.authorization = self.headers.get("Authorization")
        missing = self.path.endswith("/location")
        payload = (
            {"error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}}
            if missing
            else {"players": []}
        )
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(404 if missing else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


@pytest.mark.parametrize(
    "function",
    [
        init,
        say,
        fill,
        get_block,
        get_entities,
        get_player_location,
        get_players,
        set_block,
        set_player_gamemode,
        summon,
        kill_entity,
        kill_entities,
        teleport_player,
        watch_chat,
        watch_event,
        subscribe_event,
        Player.use,
        Player.select_slot,
        boxloom.select_slot,
        boxloom.BoxloomClient.select_slot,
        Player.start_using_item,
        Player.stop_using_item,
        Player.start_mining,
        Player.stop_mining,
    ],
)
def test_public_functions_are_callable(function):
    assert callable(function)


def test_public_api_exports():
    assert version("boxloom") == boxloom.__version__
    assert Entity.__name__ == "Entity"
    assert boxloom.GameMode is GameMode
    assert PlayerGameMode("Steve", "test-uuid", "survival").mode == "survival"
    assert blocks.DIAMOND_BLOCK == "minecraft:diamond_block"
    assert entities.ZOMBIE == "minecraft:zombie"
    assert items.BONE_MEAL == "minecraft:bone_meal"
    assert items.DIAMOND_SWORD == "minecraft:diamond_sword"
    assert items.STONE == blocks.STONE
    assert boxloom.items is items
    assert "items" in boxloom.__all__
    assert (
        blocks.MINECRAFT_VERSION
        == entities.MINECRAFT_VERSION
        == items.MINECRAFT_VERSION
    )
    assert blocks.MINECRAFT_VERSION
    assert not hasattr(boxloom, "setblock")
    assert KillResult.__name__ == "KillResult"
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
    assert Block(
        "minecraft:overworld", BlockPos(0, 64, 0), "minecraft:wheat", {"age": "3"}
    ).properties == {"age": "3"}
    assert PlayerMiningResult(True, None, False).active
    assert PlayerUseResult(False, False, None).used is False
    assert boxloom.SelectSlotResult("Steve", "test-uuid", 0).slot == 0
    assert boxloom.GivenItemSlot(2, 4).count == 4
    assert (
        boxloom.GiveItemResult("Steve", "test-uuid", "minecraft:stone", 1).slots == ()
    )
    assert PlayerItemUseResult(False, None, 0).remaining_ticks == 0
    assert boxloom.ChatEventStream is ChatEventStream
    assert issubclass(EventCursorExpiredError, boxloom.ApiError)


def test_environment_configures_the_installed_client(http_server, monkeypatch):
    server = http_server(_ApiHandler)
    monkeypatch.setenv("BOXLOOM_BASE_URL", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.delenv("BOXLOOM_AUTH_TOKEN", raising=False)
    assert get_players() == []
    assert _ApiHandler.path == "/v1/players"
    assert _ApiHandler.authorization is None
    assert get_player("Missing") is None
    assert _ApiHandler.path == "/v1/players/Missing/location"


def test_models_and_event_streams():
    player = Player(
        username="Player",
        uuid="00000000-0000-0000-0000-000000000000",
    )
    assert player.username == "Player"
    position = PlayerLocation(
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
