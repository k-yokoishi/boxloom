import json
from importlib import import_module
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from urllib.error import URLError

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
    def test_give_item_supports_top_level_client_and_bound_player(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="give-secret")
        player = self._player(client)
        for give in (lambda item, count=1: boxloom.give_item("Steve", item, count),
                     lambda item, count=1: client.give_item("Steve", item, count), player.give_item):
            for item, count in ((boxloom.blocks.STONE, 65), (boxloom.items.BONE_MEAL, 3),
                                (boxloom.items.DIAMOND_SWORD, 1), ("diamond_sword", 1)):
                canonical = item if ":" in item else f"minecraft:{item}"
                _ApiHandler.response_body = {"username": "Steve", "uuid": "test-uuid", "item": canonical, "count": count}
                result = give(item) if count == 1 else give(item, count)
                self.assertEqual(boxloom.GiveItemResult("Steve", "test-uuid", canonical, count), result)
                self.assertEqual("/v1/players/Steve/give", _ApiHandler.requests[-1][0])
                self.assertEqual({"item": item, "count": count}, _ApiHandler.requests[-1][2])
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        player.give_item("diamond_sword")
        self.assertEqual("Bearer give-secret", _ApiHandler.requests[-1][1]["Authorization"])
        with self.assertRaises(boxloom.ConfigurationError):
            boxloom.Player("Steve", "test-uuid").give_item("stone")

    def test_give_item_rejects_invalid_arguments_before_requesting(self):
        for username, item, count in (
            ("ab", "stone", 1), (None, "stone", 1), ("Steve", None, 1), ("Steve", "", 1),
            ("Steve", "Stone", 1), ("Steve", "stone; say hi", 1), ("Steve", "stone[foo=bar]", 1),
            ("Steve", "stone", 0), ("Steve", "stone", -1), ("Steve", "stone", True),
            ("Steve", "stone", 1.5), ("Steve", "stone", "1"), ("Steve", "stone", 2_147_483_648),
        ):
            with self.subTest(username=username, item=item, count=count), self.assertRaises(ValueError):
                boxloom.give_item(username, item, count)
        self.assertEqual([], _ApiHandler.requests)

    def test_give_item_rejects_malformed_results(self):
        valid = {"username": "Steve", "uuid": "test-uuid", "item": "minecraft:stone", "count": 1}
        invalid = [{k: v for k, v in valid.items() if k != field} for field in valid]
        invalid.extend({**valid, key: value} for key, value in (
            ("count", True), ("count", 0), ("count", 2), ("count", 1.5),
            ("item", "minecraft:dirt"), ("username", "Alex"), ("uuid", None),
        ))
        for body in invalid:
            with self.subTest(body=body):
                _ApiHandler.response_body = body
                with self.assertRaises(boxloom.ProtocolError):
                    boxloom.give_item("Steve", "stone")

    def test_give_item_preserves_api_errors_without_retrying(self):
        for status, code in ((400, "INVALID_ITEM"), (400, "INVALID_COUNT"), (404, "PLAYER_NOT_CONNECTED"), (504, "TIMEOUT")):
            _ApiHandler.response_status = status
            _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot give"}}
            before = len(_ApiHandler.requests)
            with self.assertRaises(boxloom.ApiError) as raised:
                boxloom.give_item("Steve", "stone")
            self.assertEqual((status, code), (raised.exception.status, raised.exception.code))
            self.assertEqual(before + 1, len(_ApiHandler.requests))

    def _player(self, client=None):
        _ApiHandler.response_body = {
            "username": "Steve", "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:overworld", "x": 0, "y": 64, "z": 0,
            "yaw": 0, "pitch": 0,
        }
        return (client or boxloom).get_player("Steve")

    @staticmethod
    def _target():
        return {
            "dimension": "minecraft:overworld", "x": -1, "y": 64, "z": 3,
            "block": "minecraft:stone", "face": "north", "distance": 2.5,
        }

    def test_get_player_returns_none_when_player_is_not_connected(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="lookup-secret")
        _ApiHandler.response_status = 404
        _ApiHandler.response_body = {"error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}}
        for lookup in (client.get_player, boxloom.get_player):
            self.assertIsNone(lookup("Steve"))
            self.assertEqual("/v1/players/Steve/position", _ApiHandler.requests[-1][0])
        self.assertEqual("Bearer lookup-secret", _ApiHandler.requests[0][1]["Authorization"])

    def test_get_player_propagates_other_api_errors(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for status, code in ((401, "UNAUTHORIZED"), (403, "FORBIDDEN"), (404, "NOT_FOUND"),
                             (500, "PLAYER_NOT_CONNECTED"), (500, "INTERNAL_ERROR")):
            _ApiHandler.response_status = status
            _ApiHandler.response_body = {"error": {"code": code, "message": "lookup failed"}}
            for lookup in (client.get_player, boxloom.get_player):
                with self.subTest(status=status, code=code, lookup=lookup):
                    with self.assertRaises(boxloom.ApiError) as raised:
                        lookup("Steve")
                    self.assertEqual((status, code), (raised.exception.status, raised.exception.code))

    def test_get_player_rejects_invalid_usernames_without_requesting(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for lookup in (client.get_player, boxloom.get_player):
            for username in ("", "ab", "not/a/player", None, 123):
                with self.subTest(username=username, lookup=lookup):
                    with self.assertRaises(ValueError):
                        lookup(username)
        self.assertEqual([], _ApiHandler.requests)

    def test_get_player_propagates_protocol_and_connection_errors(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for lookup in (client.get_player, boxloom.get_player):
            with self.assertRaises(boxloom.ProtocolError):
                lookup("Steve")
            with patch.object(import_module("boxloom._client"), "urlopen", side_effect=URLError("offline")):
                with self.assertRaises(boxloom.ConnectionError):
                    lookup("Steve")

    def test_player_methods_keep_their_originating_client_after_reinit(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="origin-secret")
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        _ApiHandler.response_body = {"target": self._target()}
        target = player.get_targeted_block()
        self.assertEqual(boxloom.TargetedBlock(**self._target()), target)
        self.assertIsInstance(target, boxloom.Block)
        self.assertEqual("/v1/players/Steve/targeted-block", _ApiHandler.requests[-1][0])
        self.assertEqual("Bearer origin-secret", _ApiHandler.requests[-1][1]["Authorization"])
        _ApiHandler.response_body = {"swung": True, "target": self._target(), "broken": True}
        result = player.attack()
        self.assertEqual(boxloom.PlayerAttackResult(True, target, True), result)
        self.assertEqual("/v1/players/Steve/attack", _ApiHandler.requests[-1][0])
        self.assertEqual({}, _ApiHandler.requests[-1][2])

    def test_listed_players_are_actionable_and_identity_equality_is_preserved(self):
        _ApiHandler.response_body = {"players": [{"username": "Steve", "uuid": "test-uuid"}]}
        player = boxloom.get_players()[0]
        self.assertEqual(boxloom.Player("Steve", "test-uuid"), player)
        _ApiHandler.response_body = {"target": None}
        self.assertIsNone(player.get_targeted_block())
        _ApiHandler.response_body = {"swung": True, "target": None, "broken": False}
        self.assertEqual(boxloom.PlayerAttackResult(True, None, False), player.attack())

    def test_snapshot_players_require_an_explicit_client_binding(self):
        player = boxloom.Player("Steve", "test-uuid")
        for operation in (player.get_targeted_block, player.attack, player.use, player.start_mining, player.stop_mining, player.start_using_item, player.stop_using_item):
            with self.assertRaises(boxloom.ConfigurationError):
                operation()
        self.assertEqual([], _ApiHandler.requests)

    def test_targeted_block_rejects_malformed_snapshots(self):
        player = self._player()
        invalid = [{}, {"target": []}, {"target": "stone"}]
        for key, value in (("face", "front"), ("x", True), ("y", 2.5),
                           ("distance", -1), ("distance", float("inf")),
                           ("distance", float("nan")), ("block", None)):
            invalid.append({"target": {**self._target(), key: value}})
        for body in invalid:
            with self.subTest(body=body):
                _ApiHandler.response_body = body
                with self.assertRaises(boxloom.ProtocolError):
                    player.get_targeted_block()

    def test_player_use_uses_its_bound_client_and_returns_block_or_air_result(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="origin-secret")
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        target = boxloom.TargetedBlock(**self._target())
        for used, swung, snapshot in ((True, True, self._target()), (False, False, None)):
            _ApiHandler.response_body = {"used": used, "swung": swung, "target": snapshot, "entity": None}
            result = player.use()
            self.assertEqual(boxloom.PlayerUseResult(used, swung, target if snapshot else None), result)
            path, headers, body = _ApiHandler.requests[-1]
            self.assertEqual("/v1/players/Steve/use", path)
            self.assertEqual("Bearer origin-secret", headers["Authorization"])
            self.assertEqual({}, body)

    def test_use_returns_an_entity_snapshot(self):
        player = self._player()
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", "entity": "minecraft:cow",
            "dimension": "minecraft:overworld", "x": 1, "y": 64, "z": 3,
            "yaw": 0, "pitch": 0, "name": "Cow", "health": 10,
        }
        _ApiHandler.response_body = {"used": True, "swung": False, "target": None, "entity": entity}
        self.assertEqual(boxloom.Entity(**entity), player.use().entity)
        _ApiHandler.response_body["target"] = self._target()
        with self.assertRaises(boxloom.ProtocolError):
            player.use()

    def test_use_rejects_invalid_usernames_and_malformed_results(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for username in ("", "ab", "has-dash", None, 42):
            with self.assertRaises(ValueError):
                client.use_player_item(username)
        self.assertEqual([], _ApiHandler.requests)
        valid = {"used": True, "swung": True, "target": None, "entity": None}
        invalid = [{}, {**valid, "used": 1}, {**valid, "swung": "true"},
                   {**valid, "used": False}, {**valid, "target": []},
                   {**valid, "entity": "cow"}]
        invalid.extend({k: v for k, v in valid.items() if k != field} for field in valid)
        for body in invalid:
            with self.subTest(body=body):
                _ApiHandler.response_body = body
                with self.assertRaises(boxloom.ProtocolError):
                    client.use_player_item("Steve")

    def test_use_preserves_api_errors_without_retrying(self):
        player = self._player()
        for status, code in ((404, "PLAYER_NOT_CONNECTED"), (409, "PLAYER_CANNOT_USE"),
                             (504, "MINECRAFT_TIMEOUT")):
            _ApiHandler.response_status = status
            _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot use"}}
            before = len(_ApiHandler.requests)
            with self.assertRaises(boxloom.ApiError) as raised:
                player.use()
            self.assertEqual((status, code), (raised.exception.status, raised.exception.code))
            self.assertEqual(before + 1, len(_ApiHandler.requests))

    def test_continuous_item_use_keeps_client_binding_and_returns_native_state(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="origin-secret")
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        _ApiHandler.response_body = {"active": True, "item": "minecraft:apple", "remaining_ticks": 32}
        self.assertEqual(boxloom.PlayerItemUseResult(True, "minecraft:apple", 32), player.start_using_item())
        self.assertEqual("/v1/players/Steve/item-use/start", _ApiHandler.requests[-1][0])
        _ApiHandler.response_body = {"active": False, "item": None, "remaining_ticks": 0}
        self.assertEqual(boxloom.PlayerItemUseResult(False, None, 0), player.stop_using_item())
        self.assertEqual("/v1/players/Steve/item-use/stop", _ApiHandler.requests[-1][0])
        for path, headers, body in _ApiHandler.requests[-2:]:
            self.assertEqual("Bearer origin-secret", headers["Authorization"])
            self.assertEqual({}, body)

    def test_continuous_item_use_refusal_and_idempotent_stop(self):
        player = self._player()
        _ApiHandler.response_body = {"active": False, "item": None, "remaining_ticks": 0}
        for operation in (player.start_using_item, player.stop_using_item, player.stop_using_item):
            self.assertEqual(boxloom.PlayerItemUseResult(False, None, 0), operation())

    def test_continuous_item_use_rejects_malformed_results(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        valid = {"active": True, "item": "minecraft:bow", "remaining_ticks": 72000}
        invalid = [{}, {**valid, "active": 1}, {**valid, "item": ""}, {**valid, "item": None},
                   {**valid, "item": 1}, {**valid, "remaining_ticks": -1},
                   {**valid, "remaining_ticks": True},
                   {**valid, "remaining_ticks": 2.5}, {**valid, "remaining_ticks": "32"},
                   {"active": False, "item": None, "remaining_ticks": 32},
                   {"active": False, "item": "minecraft:apple", "remaining_ticks": 0}]
        invalid.extend({k: v for k, v in valid.items() if k != field} for field in valid)
        for operation in (client.start_using_player_item, client.stop_using_player_item):
            for body in invalid:
                with self.subTest(operation=operation, body=body):
                    _ApiHandler.response_body = body
                    with self.assertRaises(boxloom.ProtocolError):
                        operation("Steve")
        _ApiHandler.response_body = valid
        with self.assertRaises(boxloom.ProtocolError):
            client.stop_using_player_item("Steve")

    def test_continuous_item_use_validates_names_before_network_io(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for operation in (client.start_using_player_item, client.stop_using_player_item):
            for name in ("", "ab", "has-dash", "not/a/player", None, 42):
                with self.assertRaises(ValueError):
                    operation(name)
        self.assertEqual([], _ApiHandler.requests)

    def test_continuous_item_use_propagates_errors_without_retrying(self):
        player = self._player()
        for status, code in ((404, "PLAYER_NOT_CONNECTED"), (409, "PLAYER_CANNOT_USE"),
                             (409, "ITEM_NOT_CONTINUOUSLY_USABLE"), (504, "MINECRAFT_TIMEOUT")):
            _ApiHandler.response_status = status
            _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot use"}}
            for operation in (player.start_using_item, player.stop_using_item):
                before = len(_ApiHandler.requests)
                with self.assertRaises(boxloom.ApiError) as raised:
                    operation()
                self.assertEqual((status, code), (raised.exception.status, raised.exception.code))
                self.assertEqual(before + 1, len(_ApiHandler.requests))

    def test_attack_rejects_malformed_results(self):
        player = self._player()
        for body in ({}, {"swung": 1, "target": None, "broken": False},
                     {"swung": True, "target": None, "broken": "false"},
                     {"swung": True, "target": None, "broken": True}):
            _ApiHandler.response_body = body
            with self.assertRaises(boxloom.ProtocolError):
                player.attack()

    def test_entity_attack_returns_post_attack_snapshot(self):
        player = self._player()
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", "entity": "minecraft:zombie",
            "dimension": "minecraft:overworld", "x": 1, "y": 64, "z": 3,
            "yaw": 0, "pitch": 0, "name": "Zombie", "health": 19,
        }
        _ApiHandler.response_body = {"swung": True, "target": None, "broken": False, "entity": entity}
        self.assertEqual(boxloom.Entity(**entity), player.attack().entity)
        _ApiHandler.response_body["target"] = self._target()
        with self.assertRaises(boxloom.ProtocolError):
            player.attack()
        _ApiHandler.response_body["target"] = None
        _ApiHandler.response_body["entity"] = "not-an-entity"
        with self.assertRaises(boxloom.ProtocolError):
            player.attack()

    def test_player_mining_start_and_stop_use_bound_client(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="mining-secret")
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1")
        _ApiHandler.response_body = {"active": True, "target": self._target(), "broken": False}
        self.assertEqual(boxloom.PlayerMiningResult(True, boxloom.TargetedBlock(**self._target()), False), player.start_mining())
        self.assertEqual("/v1/players/Steve/mining/start", _ApiHandler.requests[-1][0])
        self.assertEqual("Bearer mining-secret", _ApiHandler.requests[-1][1]["Authorization"])
        self.assertEqual({}, _ApiHandler.requests[-1][2])
        _ApiHandler.response_body = {"active": False, "target": None, "broken": False}
        self.assertEqual(boxloom.PlayerMiningResult(False, None, False), player.stop_mining())
        self.assertEqual("/v1/players/Steve/mining/stop", _ApiHandler.requests[-1][0])
        _ApiHandler.response_body = {"active": True, "target": None, "broken": False}
        self.assertEqual(boxloom.PlayerMiningResult(True, None, False), player.start_mining())
        _ApiHandler.response_body = {"active": True, "target": self._target(), "broken": True}
        self.assertTrue(player.start_mining().broken)
        self.assertTrue(player.start_mining().active)

    def test_mining_validates_arguments_results_and_server_rejection(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for action in (client.start_player_mining, client.stop_player_mining):
            with self.assertRaises(ValueError):
                action("ab")
        self.assertEqual([], _ApiHandler.requests)
        player = self._player()
        for body in ({}, {"active": 1, "target": None, "broken": False},
                     {"active": True, "target": None, "broken": True},
                     {"active": True, "target": self._target(), "broken": 1}):
            _ApiHandler.response_body = body
            with self.assertRaises(boxloom.ProtocolError):
                player.start_mining()
        _ApiHandler.response_body = {"active": True, "target": None, "broken": False}
        with self.assertRaises(boxloom.ProtocolError):
            player.stop_mining()
        _ApiHandler.response_status = 409
        _ApiHandler.response_body = {"error": {"code": "PLAYER_CANNOT_ATTACK", "message": "spectator"}}
        with self.assertRaises(boxloom.ApiError) as rejected:
            player.start_mining()
        self.assertEqual("PLAYER_CANNOT_ATTACK", rejected.exception.code)

    def test_player_actions_propagate_api_errors(self):
        player = self._player()
        for operation in (player.get_targeted_block, player.attack):
            _ApiHandler.response_status = 404
            _ApiHandler.response_body = {"error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}}
            with self.assertRaises(boxloom.ApiError) as raised:
                operation()
            self.assertEqual("PLAYER_NOT_CONNECTED", raised.exception.code)

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

    def test_get_entities_reads_snapshots_and_encodes_filters(self):
        item = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:item", "dimension": "minecraft:the_nether",
            "x": 1.5, "y": 64, "z": -2.25, "yaw": 90, "pitch": -12.5,
            "name": "Diamond", "health": None,
        }
        zombie = {**item, "entity": "minecraft:zombie", "name": "Zombie", "health": 20}
        _ApiHandler.response_body = {"entities": [item, zombie]}

        result = boxloom.get_entities(
            "minecraft:zombie", x=1.5, y=64, z=-2.25, radius=20,
            dimension="minecraft:the_nether", sort="nearest", limit=2,
        )

        self.assertEqual([boxloom.Entity(**item), boxloom.Entity(**zombie)], result)
        self.assertIsNone(result[0].health)
        self.assertEqual(20.0, result[1].health)
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/world/entities", urlsplit(path).path)
        self.assertEqual({
            "dimension": ["minecraft:the_nether"], "entity": ["minecraft:zombie"],
            "x": ["1.5"], "y": ["64"], "z": ["-2.25"], "radius": ["20"],
            "sort": ["nearest"], "limit": ["2"],
        }, parse_qs(urlsplit(path).query))
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

    def test_get_entities_defaults_to_all_types_and_supports_sorting_without_radius(self):
        _ApiHandler.response_body = {"entities": []}
        self.assertEqual([], boxloom.get_entities())
        self.assertEqual({"dimension": ["minecraft:overworld"], "sort": ["arbitrary"]},
                         parse_qs(urlsplit(_ApiHandler.requests[0][0]).query))
        client = boxloom.BoxloomClient(base_url=self.base_url)
        self.assertEqual([], client.get_entities(x=0, y=64, z=0, sort="furthest", limit=1))
        query = parse_qs(urlsplit(_ApiHandler.requests[1][0]).query)
        self.assertNotIn("radius", query)
        self.assertEqual(["furthest"], query["sort"])

    def test_get_entities_rejects_invalid_arguments_before_network_io(self):
        invalid = [
            {"entity": ""}, {"entity": True}, {"dimension": ""}, {"dimension": None},
            {"x": 0}, {"x": 0, "y": 64}, {"radius": 20},
            {"sort": "nearest"}, {"sort": "furthest"}, {"sort": "random"}, {"sort": []},
            {"limit": 0}, {"limit": -1}, {"limit": True}, {"limit": 1.5}, {"limit": 2**31},
        ]
        for field in ("x", "y", "z", "radius"):
            for value in (True, "1", float("nan"), float("inf"), -float("inf"), 10**1000):
                invalid.append({**dict(x=0, y=64, z=0, radius=20), field: value})
        invalid.append(dict(x=0, y=64, z=0, radius=-1))
        for kwargs in invalid:
            with self.subTest(kwargs=kwargs):
                with self.assertRaises((TypeError, ValueError)):
                    boxloom.get_entities(**kwargs)
        self.assertEqual([], _ApiHandler.requests)

    def test_get_entities_rejects_malformed_response_fields(self):
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", "entity": "minecraft:zombie",
            "dimension": "minecraft:overworld", "x": 0, "y": 64, "z": 0,
            "yaw": 0, "pitch": 0, "name": "Zombie", "health": 20,
        }
        invalid = [{}, {"entities": None}, {"entities": {}}, {"entities": ["invalid"]}]
        for field in entity:
            missing = dict(entity)
            del missing[field]
            invalid.append({"entities": [missing]})
            invalid.append({"entities": [{**entity, field: True}]})
        invalid.append({"entities": [{**entity, "health": "20"}]})
        for body in invalid:
            with self.subTest(body=body):
                _ApiHandler.response_body = body
                with self.assertRaises(boxloom.ProtocolError):
                    boxloom.get_entities()

    def test_get_entities_preserves_structured_api_errors(self):
        _ApiHandler.response_status = 400
        _ApiHandler.response_body = {"error": {"code": "INVALID_ENTITY", "message": "Unknown type"}}
        with self.assertRaises(boxloom.ApiError) as raised:
            boxloom.get_entities("minecraft:missing")
        self.assertEqual("INVALID_ENTITY", raised.exception.code)
        self.assertEqual(400, raised.exception.status)

    def test_set_player_gamemode_posts_both_modes_with_authentication(self):
        for mode in ("creative", "survival"):
            with self.subTest(mode=mode):
                _ApiHandler.response_body = {
                    "username": "Steve",
                    "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                    "mode": mode,
                }
                result = boxloom.set_player_gamemode("steve", mode)
                self.assertEqual(
                    boxloom.PlayerGameMode("Steve", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", mode), result,
                )
                path, headers, body = _ApiHandler.requests[-1]
                self.assertEqual("/v1/players/steve/gamemode", path)
                self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
                self.assertEqual("application/json", headers["Content-Type"])
                self.assertEqual({"mode": mode}, body)

    def test_explicit_client_sets_player_gamemode(self):
        _ApiHandler.response_body = {"username": "Steve", "uuid": "test-uuid", "mode": "survival"}
        client = boxloom.BoxloomClient(base_url=self.base_url)
        self.assertEqual("survival", client.set_player_gamemode("Steve", "survival").mode)
        self.assertNotIn("Authorization", _ApiHandler.requests[0][1])

    def test_set_player_gamemode_rejects_invalid_inputs_before_request(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for operation in (boxloom.set_player_gamemode, client.set_player_gamemode):
            for username in (None, 123, "", "ab", "a" * 17, "@a", "Steve/kill", "has-dash"):
                with self.subTest(username=username), self.assertRaises(ValueError):
                    operation(username, "creative")
            for mode in (None, 1, True, [], {}, "", "CREATIVE", " survival", "adventure", "spectator"):
                with self.subTest(mode=mode), self.assertRaises(ValueError):
                    operation("Steve", mode)
        self.assertEqual([], _ApiHandler.requests)

    def test_set_player_gamemode_rejects_malformed_or_mismatched_response(self):
        valid = {"username": "Steve", "uuid": "test-uuid", "mode": "creative"}
        for field in valid:
            for value in (None, 1, True, [], ""):
                with self.subTest(field=field, value=value), self.assertRaises(boxloom.ProtocolError):
                    _ApiHandler.response_body = {**valid, field: value}
                    boxloom.set_player_gamemode("Steve", "creative")
            with self.subTest(missing=field), self.assertRaises(boxloom.ProtocolError):
                _ApiHandler.response_body = {key: value for key, value in valid.items() if key != field}
                boxloom.set_player_gamemode("Steve", "creative")
        for mode in ("survival", "spectator", "CREATIVE"):
            with self.subTest(mode=mode), self.assertRaises(boxloom.ProtocolError):
                _ApiHandler.response_body = {**valid, "mode": mode}
                boxloom.set_player_gamemode("Steve", "creative")

    def test_set_player_gamemode_preserves_api_errors(self):
        for status, code in ((404, "PLAYER_NOT_CONNECTED"), (409, "GAME_MODE_CHANGE_FAILED")):
            _ApiHandler.response_status = status
            _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot change mode"}}
            with self.subTest(code=code), self.assertRaises(boxloom.ApiError) as raised:
                boxloom.set_player_gamemode("Steve", "survival")
            self.assertEqual(status, raised.exception.status)
            self.assertEqual(code, raised.exception.code)

    def test_teleport_player_posts_required_coordinates(self):
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

        result = boxloom.teleport_player("Player_123", 100, 64, -20)

        self.assertEqual("minecraft:the_nether", result.dimension)
        path, headers, body = _ApiHandler.requests[0]
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
        self.assertEqual(
            boxloom.Player(
                "Player_123",
                "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            ),
            event.player,
        )
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/events", path)
        self.assertEqual("text/event-stream", headers["Accept"])
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

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

    def test_watch_event_returns_mixed_events_with_type_discriminators(self):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream",
             _sse("stream.ready", "session:0",
                  {"type": "stream.ready", "cursor": "session:0"})
             + _chat_sse("session:1", "hello")
             + _sse("future.event", "session:2", {"type": "future.event"})
             + _death_sse("session:3"))
        ]

        with boxloom.watch_event(reconnect=False) as events:
            chat = next(events)
            death = next(events)
            self.assertEqual("session:3", events.last_event_id)

        self.assertIsInstance(chat, boxloom.ChatEvent)
        self.assertEqual("chat.message", chat.type)
        self.assertEqual("hello", chat.message)
        self.assertIsInstance(death, boxloom.PlayerDeathEvent)
        self.assertEqual("player.death", death.type)
        self.assertEqual("session:3", death.id)
        self.assertEqual(1, len(_ApiHandler.requests))

    def test_watch_event_decodes_all_player_actions_and_filters_by_type(self):
        kinds = ("player.swing", "block.attack", "block.use", "item.use")
        classes = (
            boxloom.PlayerSwingEvent, boxloom.BlockAttackEvent,
            boxloom.BlockUseEvent, boxloom.ItemUseEvent,
        )
        for kind, cls in zip(kinds, classes):
            with self.subTest(kind=kind):
                _ApiHandler.stream_responses = [(200, "text/event-stream",
                    _chat_sse("session:1", "skip")
                    + _action_sse(kind, "session:2", hand="off_hand", item=None))]
                with boxloom.watch_event(kind, reconnect=False) as events:
                    event = next(events)
                    self.assertEqual("session:2", events.last_event_id)
                self.assertIsInstance(event, cls)
                self.assertEqual(kind, event.type)
                self.assertEqual("Player_123", event.player.username)
                self.assertEqual("off_hand", event.hand)
                self.assertIsNone(event.item)
                self.assertEqual((1.5, 64.0, -2.25), (event.x, event.y, event.z))
                if kind.startswith("block."):
                    self.assertEqual("minecraft:stone", event.block)
                    self.assertEqual((-2, 63, 4), (event.block_x, event.block_y, event.block_z))
                    self.assertEqual("up", event.face)

        _ApiHandler.stream_responses = [(200, "text/event-stream", b"".join(
            _action_sse(kind, f"session:{i}") for i, kind in enumerate(kinds, 1)
        ))]
        with boxloom.watch_event(reconnect=False) as events:
            self.assertEqual(list(kinds), [next(events).type for _ in kinds])

    def test_action_events_are_skipped_by_chat_filter_and_advance_resume_cursor(self):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", b"retry: 1\n\n"
             + _action_sse("player.swing", "session:1")
             + _action_sse("block.attack", "session:2")),
            (200, "text/event-stream", _chat_sse("session:3", "after swing")),
        ]
        with boxloom.watch_chat() as events:
            self.assertEqual("after swing", next(events).message)
        self.assertEqual("session:2", _ApiHandler.requests[1][1]["Last-Event-Id"])

    def test_watch_event_rejects_malformed_action_payloads(self):
        for kind in ("player.swing", "block.attack", "block.use", "item.use"):
            cases = [
                {"id": "wrong"}, {"type": "wrong"}, {"player": None},
                {"player": {"username": "Steve"}}, {"timestamp": None},
                {"dimension": None}, {"x": True}, {"y": "64"},
                {"hand": "left"}, {"hand": None}, {"item": 1},
            ]
            if kind.startswith("block."):
                cases += [{"block": None}, {"block_x": 1.5}, {"block_y": True},
                          {"block_z": "4"}, {"face": "diagonal"}]
            for changes in cases:
                with self.subTest(kind=kind, changes=changes):
                    _ApiHandler.stream_responses = [(200, "text/event-stream",
                        _action_sse(kind, "session:1", **changes))]
                    with boxloom.watch_event(kind, reconnect=False) as events:
                        with self.assertRaises(boxloom.ProtocolError):
                            next(events)
        payload = _action_payload("player.swing", "session:1")
        del payload["item"]
        _ApiHandler.stream_responses = [(200, "text/event-stream",
            _sse("player.swing", "session:1", payload))]
        with boxloom.watch_event("player.swing", reconnect=False) as events:
            with self.assertRaises(boxloom.ProtocolError):
                next(events)

    def test_watch_event_filters_chat_by_keyword(self):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _death_sse("session:1")
             + _chat_sse("session:2", "hello"))
        ]
        client = boxloom.BoxloomClient(base_url=self.base_url)

        with client.watch_event(event_type="chat.message", reconnect=False) as events:
            event = next(events)
        self.assertEqual("chat.message", event.type)
        self.assertEqual("session:2", event.id)

    def test_watch_event_streams_an_authenticated_death_and_skips_chat(self):
        event_id = "session:2"
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _chat_sse("session:1", "hello")
             + _death_sse(event_id))
        ]

        with boxloom.watch_event("player.death", reconnect=False) as events:
            event = next(events)
            self.assertEqual(event_id, events.last_event_id)
            self.assertIsInstance(events, boxloom.EventStream)

        self.assertEqual(
            boxloom.PlayerDeathEvent(
                id=event_id,
                timestamp="2026-08-14T00:00:00Z",
                message="Player_123 fell from a high place",
                player=boxloom.Player(
                    "Player_123", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
                ),
                dimension="minecraft:the_nether",
                x=1.5,
                y=64.0,
                z=-2.25,
            ),
            event,
        )
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual("/v1/events", path)
        self.assertEqual("text/event-stream", headers["Accept"])
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

    def test_watch_chat_skips_player_deaths(self):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _death_sse("session:1")
             + _chat_sse("session:2", "still here"))
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            self.assertEqual("still here", next(events).message)

    def test_watch_event_resumes_after_skipped_events_on_reconnect(self):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", b"retry: 1\n\n"
             + _sse("stream.ready", "session:0",
                    {"type": "stream.ready", "cursor": "session:0"})
             + _chat_sse("session:1", "hello")
             + _sse("future.event", "session:2", {"type": "future.event"})),
            (200, "text/event-stream", _death_sse("session:3")),
        ]
        client = boxloom.BoxloomClient(base_url=self.base_url)

        with client.watch_event("player.death", last_event_id="saved:0") as events:
            self.assertEqual("session:3", next(events).id)

        headers = [
            {name.lower(): value for name, value in request[1].items()}
            for request in _ApiHandler.requests
        ]
        self.assertEqual("saved:0", headers[0]["last-event-id"])
        self.assertEqual("session:2", headers[1]["last-event-id"])

    def test_watch_event_rejects_malformed_death_payloads(self):
        cases = [
            {"type": "chat.message"},
            {"id": "different:1"},
            {"id": None},
            {"player": None},
            {"player": {"username": "Player_123"}},
            {"timestamp": None},
            {"message": None},
            {"dimension": None},
            {"x": True},
            {"y": "64"},
            {"z": None},
        ]
        for changes in cases:
            with self.subTest(changes=changes):
                _ApiHandler.stream_responses = [
                    (200, "text/event-stream", _death_sse("session:1", **changes))
                ]
                with boxloom.watch_event("player.death", reconnect=False) as events:
                    with self.assertRaises(boxloom.ProtocolError):
                        next(events)

    def test_watch_event_maps_expired_cursor_and_stream_reset(self):
        _ApiHandler.response_status = 410
        _ApiHandler.response_body = {
            "error": {"code": "EVENT_CURSOR_EXPIRED", "message": "Expired"},
        }
        with boxloom.watch_event("player.death", last_event_id="saved:1") as events:
            with self.assertRaises(boxloom.EventCursorExpiredError):
                next(events)

        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _sse("stream.reset", None, {
                "type": "stream.reset",
                "code": "EVENT_CURSOR_EXPIRED",
                "message": "The server session changed",
            }))
        ]
        with boxloom.watch_event("player.death", reconnect=False) as events:
            with self.assertRaises(boxloom.EventCursorExpiredError):
                next(events)

    def test_watch_event_validates_options_and_stops_when_closed(self):
        for cursor in ("", "\n", "saved:1\r\nInjected: value", 1):
            with self.subTest(cursor=cursor):
                with self.assertRaises(boxloom.ConfigurationError):
                    boxloom.watch_event("player.death", last_event_id=cursor)
        with self.assertRaises(boxloom.ConfigurationError):
            boxloom.watch_event("player.death", reconnect="yes")
        for event_type in ("", "player.deth", 1, ["player.death"]):
            with self.subTest(event_type=event_type):
                with self.assertRaises(ValueError):
                    boxloom.watch_event(event_type)
        events = boxloom.watch_event("player.death")
        events.close()
        with self.assertRaises(StopIteration):
            next(events)
        self.assertEqual([], _ApiHandler.requests)

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

    def test_get_time_reads_default_or_explicit_clock_and_64_bit_ticks(self):
        for clock in ("minecraft:overworld", "minecraft:the_end"):
            with self.subTest(clock=clock):
                _ApiHandler.response_body = {"clock": clock, "ticks": 3_000_000_000}
                if clock == "minecraft:overworld":
                    result = boxloom.get_time()
                else:
                    result = boxloom.BoxloomClient(base_url=self.base_url).get_time(clock=clock)
                self.assertEqual(boxloom.TimeResult(clock, 3_000_000_000), result)
                path, headers, body = _ApiHandler.requests[-1]
                self.assertEqual("/v1/world/time?clock=" + clock.replace(":", "%3A"), path)
                self.assertIsNone(body)
                if clock == "minecraft:overworld":
                    self.assertEqual("Bearer unit-test-secret", headers["Authorization"])

    def test_set_time_posts_markers_and_absolute_ticks(self):
        _ApiHandler.response_body = {"clock": "minecraft:overworld", "ticks": 25_000}
        for time in ("day", "noon", "night", "midnight", "example:dawn", 0, 2**31 - 1):
            with self.subTest(time=time):
                result = boxloom.set_time(time)
                self.assertEqual(boxloom.TimeResult("minecraft:overworld", 25_000), result)
                path, headers, body = _ApiHandler.requests[-1]
                self.assertEqual("/v1/world/time", path)
                self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
                self.assertEqual({"clock": "minecraft:overworld", "time": time}, body)

    def test_set_time_client_posts_an_explicit_clock(self):
        _ApiHandler.response_body = {"clock": "example:clock", "ticks": 6000}
        result = boxloom.BoxloomClient(base_url=self.base_url).set_time(
            6000, clock="example:clock"
        )
        self.assertEqual(boxloom.TimeResult("example:clock", 6000), result)
        self.assertEqual({"clock": "example:clock", "time": 6000}, _ApiHandler.requests[0][2])

    def test_add_time_posts_signed_ticks(self):
        _ApiHandler.response_body = {"clock": "minecraft:overworld", "ticks": 0}
        for ticks in (-(2**31), -20, 0, 6000, 2**31 - 1):
            with self.subTest(ticks=ticks):
                result = boxloom.add_time(ticks)
                self.assertEqual(boxloom.TimeResult("minecraft:overworld", 0), result)
                path, headers, body = _ApiHandler.requests[-1]
                self.assertEqual("/v1/world/time/add", path)
                self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
                self.assertEqual({"clock": "minecraft:overworld", "ticks": ticks}, body)
        _ApiHandler.response_body = {"clock": "minecraft:the_end", "ticks": 20}
        result = boxloom.BoxloomClient(base_url=self.base_url).add_time(
            20, clock="minecraft:the_end"
        )
        self.assertEqual(boxloom.TimeResult("minecraft:the_end", 20), result)
        self.assertEqual({"clock": "minecraft:the_end", "ticks": 20}, _ApiHandler.requests[-1][2])

    def test_time_rejects_invalid_inputs_before_request(self):
        for time in (True, False, 1.5, None, {}, [], -1, 2**31, "", " "):
            with self.subTest(time=time):
                with self.assertRaises((TypeError, ValueError)):
                    boxloom.set_time(time)
        for ticks in (True, 1.5, None, "20", -(2**31) - 1, 2**31):
            with self.subTest(ticks=ticks):
                with self.assertRaises((TypeError, ValueError)):
                    boxloom.add_time(ticks)
        for clock in (None, True, 1, "", " "):
            with self.subTest(clock=clock):
                for operation in (
                    lambda: boxloom.get_time(clock=clock),
                    lambda: boxloom.set_time("day", clock=clock),
                    lambda: boxloom.add_time(20, clock=clock),
                ):
                    with self.assertRaises(ValueError):
                        operation()
        self.assertEqual([], _ApiHandler.requests)

    def test_time_rejects_malformed_responses(self):
        invalid_payloads = (
            {}, {"clock": None, "ticks": 20}, {"clock": "minecraft:overworld"},
            *({"clock": "minecraft:overworld", "ticks": ticks}
              for ticks in (True, "20", 1.5, -1, 2**63)),
        )
        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                _ApiHandler.response_body = payload
                for operation in (boxloom.get_time, lambda: boxloom.set_time("day"),
                                  lambda: boxloom.add_time(20)):
                    with self.assertRaises(boxloom.ProtocolError):
                        operation()

    def test_time_preserves_clock_and_marker_errors(self):
        for status, code in (
            (404, "CLOCK_NOT_FOUND"),
            (404, "TIME_MARKER_NOT_FOUND"),
            (409, "TIME_ALREADY_AT_MARKER"),
        ):
            with self.subTest(code=code):
                _ApiHandler.response_status = status
                _ApiHandler.response_body = {"error": {"code": code, "message": "Missing"}}
                with self.assertRaises(boxloom.ApiError) as raised:
                    boxloom.set_time("day")
                self.assertEqual(code, raised.exception.code)
                self.assertEqual(status, raised.exception.status)

    def test_block_properties_round_trip_as_strings(self):
        snapshot = {"dimension": "minecraft:overworld", "x": 1, "y": 64, "z": 2,
                    "block": "minecraft:wheat", "properties": {"age": "3"}}
        _ApiHandler.response_body = {**snapshot, "changed": True}
        result = boxloom.set_block(1, 64, 2, boxloom.blocks.WHEAT, properties={"age": "3"})
        self.assertEqual({"age": "3"}, result.properties)
        self.assertEqual({"age": "3"}, _ApiHandler.requests[-1][2]["properties"])
        _ApiHandler.response_body = snapshot
        result = boxloom.get_block(1, 64, 2)
        self.assertEqual(3, int(result.properties["age"]))
        _ApiHandler.response_body = {**snapshot, "properties": {}}
        self.assertEqual({}, boxloom.get_block(1, 64, 2).properties)

    def test_block_properties_mappings_are_immutable_snapshots(self):
        from types import MappingProxyType
        properties = {"age": "3"}
        block = boxloom.Block("minecraft:overworld", 1, 64, 2, "minecraft:wheat", properties)
        target = boxloom.TargetedBlock("minecraft:overworld", 1, 64, 2, "minecraft:wheat", "up", 2.0,
                                      properties=properties)
        result = boxloom.SetBlockResult(True, "minecraft:overworld", 1, 64, 2, "minecraft:wheat", properties)
        properties["age"] = "7"
        for value in (block, target, result):
            self.assertEqual({"age": "3"}, value.properties)
            with self.assertRaises(TypeError):
                value.properties["age"] = "4"
        self.assertIsInstance(target, boxloom.Block)
        self.assertIsInstance(hash(block), int)
        _ApiHandler.response_body = {"changed": False, "dimension": "minecraft:overworld",
                                     "x": 1, "y": 64, "z": 2, "block": "minecraft:wheat", "properties": {"age": "3"}}
        boxloom.BoxloomClient(base_url=self.base_url).set_block(
            1, 64, 2, "minecraft:wheat", properties=MappingProxyType({"age": "3"}))
        self.assertEqual({"age": "3"}, _ApiHandler.requests[-1][2]["properties"])

    def test_set_block_rejects_malformed_properties_before_network_io(self):
        for properties in ([], "age=3", 3, True, {"age": 3}, {"lit": True},
                           {1: "3"}, {"age": None}, {"age": []}, {"": "3"}, {"age": ""}):
            with self.subTest(properties=properties):
                with self.assertRaises((TypeError, ValueError)):
                    boxloom.set_block(1, 64, 2, "minecraft:wheat", properties=properties)
        self.assertEqual([], _ApiHandler.requests)

    def test_block_property_responses_reject_non_string_values(self):
        player = self._player()
        target = self._target()
        snapshot = {"dimension": "minecraft:overworld", "x": 1, "y": 64, "z": 2, "block": "minecraft:wheat"}
        for properties in (None, [], "age=3", {"age": 3}, {"lit": True}, {"age": None}, {"": "3"}, {"age": ""}):
            with self.subTest(properties=properties):
                _ApiHandler.response_body = {**snapshot, "properties": properties, "changed": True}
                with self.assertRaises(boxloom.ProtocolError):
                    boxloom.get_block(1, 64, 2)
                with self.assertRaises(boxloom.ProtocolError):
                    boxloom.set_block(1, 64, 2, "minecraft:wheat")
                _ApiHandler.response_body = {"target": {**target, "properties": properties}}
                with self.assertRaises(boxloom.ProtocolError):
                    player.get_targeted_block()

    def test_targeted_block_includes_immutable_properties(self):
        player = self._player()
        _ApiHandler.response_body = {"target": {**self._target(), "properties": {"age": "3"}}}
        self.assertEqual({"age": "3"}, player.get_targeted_block().properties)

    def test_set_block_preserves_property_validation_errors_without_retry(self):
        _ApiHandler.response_status = 400
        _ApiHandler.response_body = {"error": {"code": "INVALID_BLOCK_PROPERTY", "message": "Invalid age"}}
        with self.assertRaises(boxloom.ApiError) as raised:
            boxloom.set_block(1, 64, 2, "minecraft:wheat", properties={"age": "8"})
        self.assertEqual((400, "INVALID_BLOCK_PROPERTY"), (raised.exception.status, raised.exception.code))
        self.assertEqual(1, len(_ApiHandler.requests))

    def test_set_block_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "changed": True,
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 80,
            "z": -2,
            "block": "minecraft:gold_block",
        }

        result = boxloom.set_block(1, 80, -2, boxloom.blocks.GOLD_BLOCK)

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

    def test_get_block_returns_block_result(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:the_nether",
            "x": 1,
            "y": 64,
            "z": -2,
            "block": "minecraft:stone",
        }

        result = boxloom.get_block(
            1, 64, -2, dimension="minecraft:the_nether"
        )
        self.assertIs(type(result), boxloom.Block)

        self.assertEqual(
            boxloom.GetBlockResult(
                "minecraft:the_nether",
                1,
                64,
                -2,
                "minecraft:stone",
            ),
            result,
        )
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual(
            "/v1/world/blocks?dimension=minecraft%3Athe_nether&x=1&y=64&z=-2",
            path,
        )
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertIsNone(body)

    def test_get_block_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "block": "minecraft:air",
        }

        result = boxloom.BoxloomClient(base_url=self.base_url).get_block(0, 64, 0)

        self.assertEqual("minecraft:air", result.block)
        self.assertEqual("minecraft:overworld", result.dimension)
        self.assertEqual((0, 64, 0), (result.x, result.y, result.z))
        self.assertEqual(
            "/v1/world/blocks?dimension=minecraft%3Aoverworld&x=0&y=64&z=0",
            _ApiHandler.requests[0][0],
        )

    def test_get_block_rejects_invalid_coordinates_before_request(self):
        with self.assertRaises(TypeError):
            boxloom.get_block(1.5, 64, 0)
        with self.assertRaises(ValueError):
            boxloom.get_block(2**31, 64, 0)

        self.assertEqual([], _ApiHandler.requests)

    def test_fill_posts_an_inclusive_block_region(self):
        _ApiHandler.response_body = {
            "changedBlocks": 8,
            "dimension": "minecraft:the_nether",
            "x1": 2,
            "y1": 65,
            "z1": -1,
            "x2": 1,
            "y2": 64,
            "z2": -2,
            "block": "minecraft:stone",
        }

        result = boxloom.fill(
            2,
            65,
            -1,
            1,
            64,
            -2,
            "minecraft:stone",
            dimension="minecraft:the_nether",
        )

        self.assertEqual(
            boxloom.FillResult(
                8,
                "minecraft:the_nether",
                2,
                65,
                -1,
                1,
                64,
                -2,
                "minecraft:stone",
            ),
            result,
        )
        self.assertEqual("/v1/world/blocks/fill", _ApiHandler.requests[0][0])
        self.assertEqual(
            {
                "dimension": "minecraft:the_nether",
                "x1": 2,
                "y1": 65,
                "z1": -1,
                "x2": 1,
                "y2": 64,
                "z2": -2,
                "block": "minecraft:stone",
            },
            _ApiHandler.requests[0][2],
        )

    def test_fill_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "changedBlocks": 1,
            "dimension": "minecraft:overworld",
            "x1": 0,
            "y1": 64,
            "z1": 0,
            "x2": 0,
            "y2": 64,
            "z2": 0,
            "block": "minecraft:gold_block",
        }

        boxloom.BoxloomClient(base_url=self.base_url).fill(
            0, 64, 0, 0, 64, 0, boxloom.blocks.GOLD_BLOCK
        )

        self.assertEqual(
            "minecraft:overworld",
            _ApiHandler.requests[0][2]["dimension"],
        )

    def test_fill_rejects_invalid_or_oversized_regions_before_request(self):
        with self.assertRaises(TypeError):
            boxloom.fill(0, 0, 0, 1.5, 1, 1, "minecraft:stone")
        with self.assertRaises(ValueError):
            boxloom.fill(0, 0, 0, 32, 32, 30, "minecraft:stone")

        self.assertEqual([], _ApiHandler.requests)

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

        result = boxloom.summon(boxloom.entities.ARROW, 1.5, 72, -2.25, nbt=nbt)

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

    def test_kill_entity_posts_uuid_and_dimension(self):
        uuid = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        _ApiHandler.response_body = {"dimension": "minecraft:the_nether", "killedEntities": 1}

        result = boxloom.kill_entity(uuid, dimension="minecraft:the_nether")

        self.assertEqual(boxloom.KillResult("minecraft:the_nether", 1), result)
        path, headers, body = _ApiHandler.requests[0]
        self.assertEqual(f"/v1/world/entities/{uuid}/kill", path)
        self.assertEqual("Bearer unit-test-secret", headers["Authorization"])
        self.assertEqual({"dimension": "minecraft:the_nether"}, body)

    def test_kill_entities_posts_complete_range(self):
        _ApiHandler.response_body = {"dimension": "minecraft:the_end", "killedEntities": 3}

        result = boxloom.kill_entities(
            "minecraft:zombie", x=1.5, y=64, z=-2.25, radius=20,
            dimension="minecraft:the_end",
        )

        self.assertEqual(boxloom.KillResult("minecraft:the_end", 3), result)
        self.assertEqual("/v1/world/entities/kill", _ApiHandler.requests[0][0])
        self.assertEqual(
            {"dimension": "minecraft:the_end", "entity": "minecraft:zombie",
             "x": 1.5, "y": 64, "z": -2.25, "radius": 20},
            _ApiHandler.requests[0][2],
        )

    def test_client_kill_defaults_and_zero_results(self):
        _ApiHandler.response_body = {"dimension": "minecraft:overworld", "killedEntities": 0}
        client = boxloom.BoxloomClient(base_url=self.base_url)

        self.assertEqual(0, client.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af").killed_entities)
        self.assertEqual(0, client.kill_entities("minecraft:zombie").killed_entities)
        self.assertEqual({"dimension": "minecraft:overworld"}, _ApiHandler.requests[0][2])
        self.assertEqual(
            {"dimension": "minecraft:overworld", "entity": "minecraft:zombie"},
            _ApiHandler.requests[1][2],
        )

    def test_kill_entities_accepts_zero_radius(self):
        _ApiHandler.response_body = {"dimension": "minecraft:overworld", "killedEntities": 1}

        boxloom.kill_entities("minecraft:zombie", x=0, y=64, z=0, radius=0)

        self.assertEqual(0, _ApiHandler.requests[0][2]["radius"])

    def test_kill_rejects_invalid_parameters_before_request(self):
        for uuid in ("not-a-uuid", "1-1-1-1-1", "@e", "", None, 123):
            with self.subTest(uuid=uuid), self.assertRaises(ValueError):
                boxloom.kill_entity(uuid)
        for kwargs in (
            {"x": 0}, {"radius": 20}, {"x": 0, "y": 64, "z": 0},
            {"x": 0, "y": 64, "z": 0, "radius": -1},
            {"x": True, "y": 64, "z": 0, "radius": 20},
            {"x": "0", "y": 64, "z": 0, "radius": 20},
            {"x": float("inf"), "y": 64, "z": 0, "radius": 20},
            {"x": 0, "y": 64, "z": 0, "radius": float("nan")},
            {"x": 10**400, "y": 64, "z": 0, "radius": 20},
            {"dimension": ""},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises((TypeError, ValueError)):
                boxloom.kill_entities("minecraft:zombie", **kwargs)
        for entity in ("", " ", None, 123):
            with self.subTest(entity=entity), self.assertRaises(ValueError):
                boxloom.kill_entities(entity)
        with self.assertRaises(ValueError):
            boxloom.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", dimension="")
        self.assertEqual([], _ApiHandler.requests)

    def test_kill_rejects_malformed_responses(self):
        for payload in (
            {}, {"dimension": "minecraft:overworld"},
            {"dimension": "minecraft:overworld", "killedEntities": -1},
            {"dimension": "minecraft:overworld", "killedEntities": True},
            {"dimension": "minecraft:overworld", "killedEntities": 1.5},
            {"dimension": None, "killedEntities": 1},
        ):
            _ApiHandler.response_body = payload
            for operation in (
                lambda: boxloom.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"),
                lambda: boxloom.kill_entities("minecraft:zombie"),
            ):
                with self.subTest(payload=payload), self.assertRaises(boxloom.ProtocolError):
                    operation()

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


def _action_payload(kind, event_id):
    payload = {
        "type": kind, "id": event_id, "timestamp": "2026-11-06T00:00:00Z",
        "player": {"username": "Player_123", "uuid": "player-uuid"},
        "dimension": "minecraft:overworld", "x": 1.5, "y": 64, "z": -2.25,
        "hand": "main_hand", "item": "minecraft:diamond_sword",
    }
    if kind.startswith("block."):
        payload.update(block="minecraft:stone", block_x=-2, block_y=63, block_z=4, face="up")
    return payload


def _action_sse(kind, event_id, **changes):
    payload = _action_payload(kind, event_id)
    payload.update(changes)
    return _sse(kind, event_id, payload)


def _death_sse(event_id, **changes):
    payload = {
        "type": "player.death",
        "id": event_id,
        "timestamp": "2026-08-14T00:00:00Z",
        "message": "Player_123 fell from a high place",
        "player": {
            "username": "Player_123",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
        },
        "dimension": "minecraft:the_nether",
        "x": 1.5,
        "y": 64,
        "z": -2.25,
    }
    payload.update(changes)
    return _sse("player.death", event_id, payload)


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
