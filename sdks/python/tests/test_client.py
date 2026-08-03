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

    def do_GET(self):
        self.__class__.requests.append((self.path, dict(self.headers), None))
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


if __name__ == "__main__":
    unittest.main()
