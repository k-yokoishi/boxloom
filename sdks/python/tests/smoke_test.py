import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
    summon,
    watch_chat,
)


assert version("boxloom") == boxloom.__version__
assert callable(init)
assert callable(say)
assert callable(get_player_position)
assert callable(get_players)
assert callable(set_block)
assert callable(summon)
assert callable(watch_chat)
assert boxloom.ChatEventStream is ChatEventStream
assert issubclass(EventCursorExpiredError, boxloom.ApiError)


class _ApiHandler(BaseHTTPRequestHandler):
    path = None
    authorization = None

    def do_GET(self):
        self.__class__.path = self.path
        self.__class__.authorization = self.headers.get("Authorization")
        encoded = json.dumps({"players": []}).encode("utf-8")
        self.send_response(200)
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
summon_result = boxloom.SummonResult(
    uuid="00000000-0000-0000-0000-000000000001",
    entity="minecraft:pig",
    dimension="minecraft:overworld",
    x=0.0,
    y=64.0,
    z=0.0,
)
assert summon_result.entity == "minecraft:pig"
