import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import boxloom


class _ApiHandler(BaseHTTPRequestHandler):
    requests = []
    response_status = 200
    response_body = {}
    stream_responses = []

    def do_GET(self):
        self.__class__.requests.append((self.path, dict(self.headers), None))
        if self.path == "/v1/events" and self.__class__.stream_responses:
            self._send_stream_response()
            return
        self._send_response()

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        self.__class__.requests.append(
            (self.path, dict(self.headers), json.loads(body.decode("utf-8")))
        )

        self._send_response()

    def _send_response(self):
        encoded = json.dumps(self.__class__.response_body).encode("utf-8")
        self.send_response(self.__class__.response_status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _send_stream_response(self):
        status, content_type, encoded = self.__class__.stream_responses.pop(0)
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)
        self.wfile.flush()

    def log_message(self, format, *args):
        return


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _ApiHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def setUp(self):
        _ApiHandler.requests = []
        _ApiHandler.response_status = 200
        _ApiHandler.response_body = {}
        _ApiHandler.stream_responses = []
        boxloom.init(base_url=self.base_url, auth_token="unit-test-secret")

    def test_say_posts_authenticated_message(self):
        _ApiHandler.response_body = {"message": "Hello!", "recipients": 2}

        result = boxloom.say("Hello!")

        self.assertEqual(boxloom.SayResult("Hello!", 2), result)
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/chat/messages", path)
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertEqual({"message": "Hello!"}, body)

    def test_say_omits_authorization_when_token_is_not_configured(self):
        _ApiHandler.response_body = {"message": "Local", "recipients": 1}

        with patch.dict(os.environ, {}, clear=True):
            boxloom.init(base_url=self.base_url)
            result = boxloom.say("Local")

        self.assertEqual(boxloom.SayResult("Local", 1), result)
        self.assertNotIn("Authorization", _ApiHandler.requests[0][1])

    def test_get_player_position_reads_authenticated_position(self):
        _ApiHandler.response_body = {
            "username": "Player_123",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:the_nether",
            "x": -0.2,
            "y": 64,
            "z": -3.2,
            "yaw": 90,
            "pitch": -12.5,
        }

        result = boxloom.get_player_position("Player_123")

        self.assertEqual("Player_123", result.username)
        self.assertEqual("minecraft:the_nether", result.dimension)
        self.assertEqual((-1, 64, -4), result.block_coordinates())
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/players/Player_123/position", path)
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

    def test_get_players_reads_authenticated_player_list(self):
        _ApiHandler.response_body = {
            "players": [
                {
                    "username": "Player_123",
                    "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                },
                {
                    "username": "Builder",
                    "uuid": "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7",
                },
            ]
        }

        result = boxloom.get_players()

        self.assertEqual(
            [
                boxloom.Player(
                    "Player_123", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
                ),
                boxloom.Player("Builder", "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"),
            ],
            result,
        )
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/players", path)
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

    def test_get_players_rejects_invalid_player_list(self):
        _ApiHandler.response_body = {"players": ["not-an-object"]}

        with self.assertRaises(boxloom.ProtocolError):
            boxloom.get_players()

    def test_player_teleport_posts_required_coordinates(self):
        _ApiHandler.response_body = {
            "players": [
                {
                    "username": "Player_123",
                    "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                }
            ]
        }
        player = boxloom.get_players()[0]
        _ApiHandler.response_body = {
            "username": "Player_123",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:the_nether",
            "x": 100,
            "y": 64,
            "z": -20,
            "yaw": 45,
            "pitch": 10,
        }

        result = player.teleport(100, 64, -20)

        self.assertEqual("minecraft:the_nether", result.dimension)
        path, headers, body = _ApiHandler.requests[1]
        self.assertEqual("/v1/players/Player_123/teleport", path)
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertEqual({"x": 100, "y": 64, "z": -20}, body)

    def test_teleport_player_posts_optional_destination_fields(self):
        _ApiHandler.response_body = {
            "username": "Player_123",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:the_end",
            "x": 1.5,
            "y": 72,
            "z": -2.25,
            "yaw": 90,
            "pitch": -12.5,
        }
        client = boxloom.BoxloomClient(
            base_url=self.base_url,
            auth_token="unit-test-secret",
        )

        result = client.teleport_player(
            "Player_123",
            1.5,
            72,
            -2.25,
            dimension="minecraft:the_end",
            yaw=90,
            pitch=-12.5,
        )

        self.assertEqual((1, 72, -3), result.block_coordinates())
        self.assertEqual(
            {
                "x": 1.5,
                "y": 72,
                "z": -2.25,
                "dimension": "minecraft:the_end",
                "yaw": 90,
                "pitch": -12.5,
            },
            _ApiHandler.requests[0][2],
        )

    def test_teleport_rejects_invalid_coordinates_before_request(self):
        player = boxloom.Player(
            "Player_123",
            "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
        )
        with self.assertRaises(boxloom.ConfigurationError):
            player.teleport(0, 64, 0)

        client = boxloom.BoxloomClient(base_url=self.base_url)
        with self.assertRaises(ValueError):
            client.teleport_player("Player_123", float("inf"), 64, 0)

        self.assertEqual([], _ApiHandler.requests)

    def test_watch_chat_streams_an_authenticated_player_message(self):
        event_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:1"
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream; charset=utf-8",
                _sse(
                    "chat.message",
                    event_id,
                    {
                        "type": "chat.message",
                        "id": event_id,
                        "timestamp": "2026-08-14T00:00:00Z",
                        "message": "hello from Minecraft",
                        "player": {
                            "username": "Player_123",
                            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                        },
                    },
                ),
            )
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            event = next(events)
            self.assertEqual(event_id, events.last_event_id)

        self.assertEqual(event_id, event.id)
        self.assertEqual("hello from Minecraft", event.message)
        self.assertEqual("Player_123", event.player.username)
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/events", path)
        self.assertEqual("text/event-stream", headers["Accept"])
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

        _ApiHandler.response_body = {
            "username": "Player_123",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "yaw": 0,
            "pitch": 0,
        }
        event.player.teleport(0, 64, 0)
        self.assertEqual(
            "/v1/players/Player_123/teleport",
            _ApiHandler.requests[1][0],
        )

    def test_watch_chat_skips_ready_and_unknown_events(self):
        ready_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:0"
        event_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:1"
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _sse(
                    "stream.ready",
                    ready_id,
                    {"type": "stream.ready", "cursor": ready_id},
                )
                + _sse(
                    "future.event",
                    ready_id,
                    {"type": "future.event", "value": "ignored"},
                )
                + _chat_sse(event_id, "hello"),
            )
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            event = next(events)

        self.assertEqual(event_id, event.id)
        self.assertEqual("hello", event.message)

    def test_watch_chat_rejects_a_mismatched_event_discriminator(self):
        event_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:1"
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _sse(
                    "chat.message",
                    event_id,
                    {"type": "stream.reset"},
                ),
            )
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            with self.assertRaises(boxloom.ProtocolError):
                next(events)

    def test_watch_chat_reconnects_with_the_last_event_id(self):
        first_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:1"
        second_id = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af:2"
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                b"retry: 1\n\n" + _chat_sse(first_id, "one"),
            ),
            (200, "text/event-stream", _chat_sse(second_id, "two")),
        ]

        with boxloom.watch_chat() as events:
            first = next(events)
            second = next(events)

        self.assertEqual("one", first.message)
        self.assertEqual("two", second.message)
        self.assertEqual(2, len(_ApiHandler.requests))
        first_headers = {
            name.lower(): value for name, value in _ApiHandler.requests[0][1].items()
        }
        second_headers = {
            name.lower(): value for name, value in _ApiHandler.requests[1][1].items()
        }
        self.assertNotIn("last-event-id", first_headers)
        self.assertEqual(first_id, second_headers["last-event-id"])

    def test_watch_chat_maps_an_expired_cursor_response(self):
        _ApiHandler.response_status = 410
        _ApiHandler.response_body = {
            "error": {
                "code": "EVENT_CURSOR_EXPIRED",
                "message": "The requested events are no longer retained",
            }
        }

        with boxloom.watch_chat(last_event_id="old:1") as events:
            with self.assertRaises(boxloom.EventCursorExpiredError):
                next(events)

    def test_watch_chat_maps_a_stream_reset(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _sse(
                    "stream.reset",
                    None,
                    {
                        "type": "stream.reset",
                        "code": "EVENT_CURSOR_EXPIRED",
                        "message": "The server session changed",
                    },
                ),
            )
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            with self.assertRaises(boxloom.EventCursorExpiredError):
                next(events)

    def test_get_player_position_rejects_invalid_username(self):
        with self.assertRaises(ValueError):
            boxloom.get_player_position("not/a/player")

        self.assertEqual([], _ApiHandler.requests)

    def test_set_block_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "changed": True,
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 80,
            "z": -2,
            "block": "minecraft:gold_block",
        }

        result = boxloom.set_block(1, 80, -2, "minecraft:gold_block")

        self.assertTrue(result.changed)
        self.assertEqual("minecraft:gold_block", result.block)
        self.assertEqual(
            {
                "dimension": "minecraft:overworld",
                "x": 1,
                "y": 80,
                "z": -2,
                "block": "minecraft:gold_block",
            },
            _ApiHandler.requests[0][2],
        )

    def test_setblock_alias_calls_same_api(self):
        _ApiHandler.response_body = {
            "changed": False,
            "dimension": "minecraft:the_nether",
            "x": 0,
            "y": 64,
            "z": 0,
            "block": "minecraft:stone",
        }

        result = boxloom.setblock(
            0, 64, 0, "minecraft:stone", dimension="minecraft:the_nether"
        )

        self.assertFalse(result.changed)
        self.assertEqual("minecraft:the_nether", result.dimension)

    def test_summon_posts_nested_nbt(self):
        _ApiHandler.response_body = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:arrow",
            "dimension": "minecraft:overworld",
            "x": 1.5,
            "y": 72.0,
            "z": -2.25,
        }
        nbt = {
            "Motion": [0.0, -1.5, 0.0],
            "Rotation": [0.0, 90.0],
            "NoGravity": False,
            "Tags": ["boxloom", "example"],
        }

        result = boxloom.summon("minecraft:arrow", 1.5, 72, -2.25, nbt=nbt)

        self.assertEqual(
            boxloom.SummonResult(
                "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                "minecraft:arrow",
                "minecraft:overworld",
                1.5,
                72.0,
                -2.25,
            ),
            result,
        )
        self.assertEqual("/v1/world/entities", _ApiHandler.requests[0][0])
        self.assertEqual(
            {
                "dimension": "minecraft:overworld",
                "entity": "minecraft:arrow",
                "x": 1.5,
                "y": 72,
                "z": -2.25,
                "nbt": nbt,
            },
            _ApiHandler.requests[0][2],
        )

    def test_summon_omits_nbt_when_not_supplied(self):
        _ApiHandler.response_body = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:pig",
            "dimension": "minecraft:the_nether",
            "x": 0,
            "y": 64,
            "z": 0,
        }

        boxloom.summon(
            "minecraft:pig", 0, 64, 0, dimension="minecraft:the_nether"
        )

        self.assertNotIn("nbt", _ApiHandler.requests[0][2])

    def test_summon_rejects_values_without_nbt_representation(self):
        invalid_values = (
            {"value": None},
            {"value": float("nan")},
            {"value": 2**63},
            {1: "non-string key"},
            {"value": (1, 2, 3)},
        )

        for nbt in invalid_values:
            with self.subTest(nbt=nbt):
                with self.assertRaises((TypeError, ValueError)):
                    boxloom.summon("minecraft:pig", 0, 64, 0, nbt=nbt)

        self.assertEqual([], _ApiHandler.requests)

    def test_summon_rejects_non_finite_coordinates(self):
        with self.assertRaises(ValueError):
            boxloom.summon("minecraft:pig", float("inf"), 64, 0)

        self.assertEqual([], _ApiHandler.requests)

    def test_api_error_does_not_expose_auth_token(self):
        _ApiHandler.response_status = 403
        _ApiHandler.response_body = {
            "error": {"code": "FORBIDDEN", "message": "The token is invalid"}
        }

        with self.assertRaises(boxloom.ApiError) as raised:
            boxloom.say("Hello")

        self.assertEqual(403, raised.exception.status)
        self.assertEqual("FORBIDDEN", raised.exception.code)
        self.assertNotIn("unit-test-secret", str(raised.exception))

    def test_lazy_configuration_reads_environment(self):
        _ApiHandler.response_body = {"message": "From env", "recipients": 0}
        boxloom._reset_default_client_for_testing()

        with patch.dict(
            os.environ,
            {"BOXLOOM_BASE_URL": self.base_url, "BOXLOOM_AUTH_TOKEN": "env-secret"},
            clear=False,
        ):
            boxloom.say("From env")

        self.assertEqual("Bearer env-secret", _ApiHandler.requests[0][1]["Authorization"])

    def test_get_players_lazily_initializes_without_auth_token(self):
        _ApiHandler.response_body = {"players": []}
        boxloom._reset_default_client_for_testing()

        with patch.dict(
            os.environ,
            {"BOXLOOM_BASE_URL": self.base_url},
            clear=True,
        ):
            result = boxloom.get_players()

        self.assertEqual([], result)
        self.assertEqual("/v1/players", _ApiHandler.requests[0][0])
        self.assertNotIn("Authorization", _ApiHandler.requests[0][1])

    def test_get_players_lazily_initializes_with_empty_auth_token(self):
        _ApiHandler.response_body = {"players": []}
        boxloom._reset_default_client_for_testing()

        with patch.dict(
            os.environ,
            {
                "BOXLOOM_BASE_URL": self.base_url,
                "BOXLOOM_AUTH_TOKEN": "",
            },
            clear=True,
        ):
            result = boxloom.get_players()

        self.assertEqual([], result)
        self.assertEqual("/v1/players", _ApiHandler.requests[0][0])
        self.assertNotIn("Authorization", _ApiHandler.requests[0][1])


def _sse(event, event_id, payload):
    fields = [f"event: {event}"]
    if event_id is not None:
        fields.append(f"id: {event_id}")
    fields.append(
        "data: " + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    )
    return ("\n".join(fields) + "\n\n").encode("utf-8")


def _chat_sse(event_id, message):
    return _sse(
        "chat.message",
        event_id,
        {
            "type": "chat.message",
            "id": event_id,
            "timestamp": "2026-08-14T00:00:00Z",
            "message": message,
            "player": {
                "username": "Player_123",
                "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            },
        },
    )


if __name__ == "__main__":
    unittest.main()
