import json
from http.server import BaseHTTPRequestHandler
from importlib import import_module
from unittest.mock import patch
from urllib.error import URLError
from urllib.parse import parse_qs, urlsplit

import pytest

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


@pytest.fixture(scope="module")
def api_server(http_server):
    return http_server(_ApiHandler)


class TestClient:
    @pytest.fixture(autouse=True)
    def configure_client(self, api_server, isolated_client):
        self.base_url = f"http://127.0.0.1:{api_server.server_port}"
        _ApiHandler.requests = []
        _ApiHandler.response_status = 200
        _ApiHandler.response_body = {}
        _ApiHandler.stream_responses = []
        boxloom.init(base_url=self.base_url, auth_token="unit-test-secret")

    def test_give_item_supports_top_level_client_and_bound_player(self):
        client = boxloom.BoxloomClient(base_url=self.base_url, auth_token="give-secret")
        player = self._player(client)
        for give in (
            lambda item, count=1: boxloom.give_item("Steve", item, count),
            lambda item, count=1: client.give_item("Steve", item, count),
            player.give_item,
        ):
            for item, count in (
                (boxloom.blocks.STONE, 65),
                (boxloom.items.BONE_MEAL, 3),
                (boxloom.items.DIAMOND_SWORD, 1),
                ("diamond_sword", 1),
            ):
                canonical = item if ":" in item else f"minecraft:{item}"
                _ApiHandler.response_body = {
                    "username": "Steve",
                    "uuid": "test-uuid",
                    "item": canonical,
                    "count": count,
                    "slot": 2,
                    "slots": [{"slot": 2, "count": count}],
                }
                result = give(item) if count == 1 else give(item, count)
                assert result == boxloom.GiveItemResult(
                    "Steve",
                    "test-uuid",
                    canonical,
                    count,
                    2,
                    (boxloom.GivenItemSlot(2, count),),
                )
                assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/give"
                assert _ApiHandler.requests[-1][2] == {"item": item, "count": count}
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        player.give_item("diamond_sword")
        assert _ApiHandler.requests[-1][1]["Authorization"] == "Bearer give-secret"
        with pytest.raises(boxloom.ConfigurationError):
            boxloom.Player("Steve", "test-uuid").give_item("stone")

    @pytest.mark.parametrize(
        "username, item, count",
        (
            ("ab", "stone", 1),
            (None, "stone", 1),
            ("Steve", None, 1),
            ("Steve", "", 1),
            ("Steve", "Stone", 1),
            ("Steve", "stone; say hi", 1),
            ("Steve", "stone[foo=bar]", 1),
            ("Steve", "stone", 0),
            ("Steve", "stone", -1),
            ("Steve", "stone", True),
            ("Steve", "stone", 1.5),
            ("Steve", "stone", "1"),
            ("Steve", "stone", 2147483648),
        ),
    )
    def test_give_item_rejects_invalid_arguments_before_requesting(
        self, username, item, count
    ):
        with pytest.raises(ValueError):
            boxloom.give_item(username, item, count)
        assert _ApiHandler.requests == []

    def test_give_item_rejects_malformed_results(self):
        valid = {
            "username": "Steve",
            "uuid": "test-uuid",
            "item": "minecraft:stone",
            "count": 1,
            "slot": 0,
            "slots": [{"slot": 0, "count": 1}],
        }
        invalid = [{k: v for k, v in valid.items() if k != field} for field in valid]
        invalid.extend(
            {**valid, key: value}
            for key, value in (
                ("count", True),
                ("count", 0),
                ("count", 2),
                ("count", 1.5),
                ("item", "minecraft:dirt"),
                ("username", "Alex"),
                ("uuid", None),
                ("slot", True),
                ("slot", 0.0),
                ("slot", "0"),
                ("slot", None),
                ("slot", 9),
                ("slots", None),
                ("slots", {}),
                ("slots", [None]),
                ("slots", [{}]),
                ("slots", [{"slot": True, "count": 1}]),
                ("slots", [{"slot": -1, "count": 1}]),
                ("slots", [{"slot": 36, "count": 1}]),
                ("slots", [{"slot": 41, "count": 1}]),
                ("slots", [{"slot": 0, "count": 0}]),
                ("slots", [{"slot": 0, "count": True}]),
                ("slots", [{"slot": 0, "count": 2}]),
                ("slots", [{"slot": 0, "count": 1}, {"slot": 0, "count": 1}]),
            )
        )
        for body in invalid:
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                boxloom.give_item("Steve", "stone")

    @pytest.mark.parametrize(
        "entries, selected",
        (([(2, 4), (9, 61)], 2), ([(9, 64), (40, 1)], None), ([], None)),
    )
    def test_give_item_reports_added_counts_and_null_for_non_hotbar_destinations(
        self, entries, selected
    ):
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "test-uuid",
            "item": "minecraft:stone",
            "count": 65,
            "slot": selected,
            "slots": [{"slot": slot, "count": count} for slot, count in entries],
        }
        result = boxloom.give_item("Steve", "stone", 65)
        assert result.slot == selected
        assert result.slots == tuple(
            (boxloom.GivenItemSlot(*entry) for entry in entries)
        )

    @pytest.mark.parametrize(
        "slots, selected",
        (
            ([{"slot": 0, "count": 1}, {"slot": 0, "count": 1}], 0),
            ([{"slot": 2, "count": 1}, {"slot": 7, "count": 1}], 7),
        ),
    )
    def test_give_item_rejects_duplicate_slots_and_incorrect_representative(
        self, slots, selected
    ):
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "test-uuid",
            "item": "minecraft:stone",
            "count": 65,
            "slots": slots,
            "slot": selected,
        }
        with pytest.raises(boxloom.ProtocolError):
            boxloom.give_item("Steve", "stone", 65)

    def test_select_slot_supports_top_level_client_and_bound_player(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="select-secret"
        )
        player = self._player(client)
        for select in (
            lambda slot: boxloom.select_slot("Steve", slot),
            lambda slot: client.select_slot("Steve", slot),
            player.select_slot,
        ):
            for slot in (0, 8, 8):
                _ApiHandler.response_body = {
                    "username": "Steve",
                    "uuid": "test-uuid",
                    "slot": slot,
                }
                assert select(slot) == boxloom.SelectSlotResult(
                    "Steve", "test-uuid", slot
                )
                assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/select-slot"
                assert _ApiHandler.requests[-1][2] == {"slot": slot}
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        player.select_slot(8)
        assert _ApiHandler.requests[-1][1]["Authorization"] == "Bearer select-secret"
        with pytest.raises(boxloom.ConfigurationError):
            boxloom.Player("Steve", "test-uuid").select_slot(0)

    @pytest.mark.parametrize(
        "username, slot",
        (
            ("ab", 0),
            (None, 0),
            ("Steve", -1),
            ("Steve", 9),
            ("Steve", 40),
            ("Steve", True),
            ("Steve", False),
            ("Steve", None),
            ("Steve", 0.0),
            ("Steve", "1"),
        ),
    )
    def test_select_slot_rejects_invalid_arguments_before_requesting(
        self, username, slot
    ):
        with pytest.raises(ValueError):
            boxloom.select_slot(username, slot)
        assert _ApiHandler.requests == []

    def test_select_slot_rejects_malformed_results(self):
        valid = {"username": "Steve", "uuid": "test-uuid", "slot": 0}
        invalid = [{k: v for k, v in valid.items() if k != field} for field in valid]
        invalid.extend(
            {**valid, key: value}
            for key, value in (
                ("slot", True),
                ("slot", 0.0),
                ("slot", None),
                ("slot", "0"),
                ("slot", 1),
                ("username", "Alex"),
                ("uuid", None),
            )
        )
        for body in invalid:
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                boxloom.select_slot("Steve", 0)

    @pytest.mark.parametrize(
        "status, code",
        (
            (400, "INVALID_REQUEST"),
            (404, "PLAYER_NOT_CONNECTED"),
            (503, "WORLD_NOT_LOADED"),
            (504, "TIMEOUT"),
        ),
    )
    def test_select_slot_preserves_api_errors_without_retrying(self, status, code):
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {
            "error": {"code": code, "message": "Cannot select"}
        }
        before = len(_ApiHandler.requests)
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.select_slot("Steve", 0)
        assert (raised.value.status, raised.value.code) == (status, code)
        assert len(_ApiHandler.requests) == before + 1

    @pytest.mark.parametrize(
        "status, code",
        (
            (400, "INVALID_ITEM"),
            (400, "INVALID_COUNT"),
            (404, "PLAYER_NOT_CONNECTED"),
            (504, "TIMEOUT"),
        ),
    )
    def test_give_item_preserves_api_errors_without_retrying(self, status, code):
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot give"}}
        before = len(_ApiHandler.requests)
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.give_item("Steve", "stone")
        assert (raised.value.status, raised.value.code) == (status, code)
        assert len(_ApiHandler.requests) == before + 1

    def _player(self, client=None):
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "yaw": 0,
            "pitch": 0,
        }
        return (client or boxloom).get_player("Steve")

    @staticmethod
    def _target():
        return {
            "dimension": "minecraft:overworld",
            "x": -1,
            "y": 64,
            "z": 3,
            "block": "minecraft:stone",
            "face": "north",
            "distance": 2.5,
        }

    def test_get_player_returns_none_when_player_is_not_connected(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="lookup-secret"
        )
        _ApiHandler.response_status = 404
        _ApiHandler.response_body = {
            "error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}
        }
        for lookup in (client.get_player, boxloom.get_player):
            assert lookup("Steve") is None
            assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/location"
        assert _ApiHandler.requests[0][1]["Authorization"] == "Bearer lookup-secret"

    @pytest.mark.parametrize(
        "status, code",
        (
            (401, "UNAUTHORIZED"),
            (403, "FORBIDDEN"),
            (404, "NOT_FOUND"),
            (500, "PLAYER_NOT_CONNECTED"),
            (500, "INTERNAL_ERROR"),
        ),
    )
    def test_get_player_propagates_other_api_errors(self, status, code):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {
            "error": {"code": code, "message": "lookup failed"}
        }
        for lookup in (client.get_player, boxloom.get_player):
            with pytest.raises(boxloom.ApiError) as raised:
                lookup("Steve")
            assert (raised.value.status, raised.value.code) == (status, code)

    def test_get_player_rejects_invalid_usernames_without_requesting(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for lookup in (client.get_player, boxloom.get_player):
            for username in ("", "ab", "not/a/player", None, 123):
                with pytest.raises(ValueError):
                    lookup(username)
        assert _ApiHandler.requests == []

    def test_get_player_propagates_protocol_and_connection_errors(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for lookup in (client.get_player, boxloom.get_player):
            with pytest.raises(boxloom.ProtocolError):
                lookup("Steve")
            with patch.object(
                import_module("boxloom._client"),
                "urlopen",
                side_effect=URLError("offline"),
            ):
                with pytest.raises(boxloom.ConnectionError):
                    lookup("Steve")

    def test_player_methods_keep_their_originating_client_after_reinit(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="origin-secret"
        )
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        _ApiHandler.response_body = {"target": self._target()}
        target = player.get_targeted_block()
        assert target == boxloom.TargetedBlock(
            "minecraft:overworld",
            boxloom.BlockPos(-1, 64, 3),
            "minecraft:stone",
            "north",
            2.5,
        )
        assert isinstance(target, boxloom.Block)
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/targeted-block"
        assert _ApiHandler.requests[-1][1]["Authorization"] == "Bearer origin-secret"
        _ApiHandler.response_body = {
            "swung": True,
            "target": self._target(),
            "broken": True,
        }
        result = player.attack()
        assert result == boxloom.PlayerAttackResult(True, target, True)
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/attack"
        assert _ApiHandler.requests[-1][2] == {}

    def test_listed_players_are_actionable_and_identity_equality_is_preserved(self):
        _ApiHandler.response_body = {
            "players": [{"username": "Steve", "uuid": "test-uuid"}]
        }
        player = boxloom.get_players()[0]
        assert player == boxloom.Player("Steve", "test-uuid")
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "test-uuid",
            "dimension": "minecraft:overworld",
            "x": 1.5,
            "y": 64,
            "z": -2.25,
            "yaw": 90,
            "pitch": -12.5,
        }
        assert player.get_location() == boxloom.PlayerLocation(
            "Steve",
            "test-uuid",
            "minecraft:overworld",
            boxloom.Vec3(1.5, 64, -2.25),
            90,
            -12.5,
        )
        _ApiHandler.response_body = {"target": None}
        assert player.get_targeted_block() is None
        _ApiHandler.response_body = {"swung": True, "target": None, "broken": False}
        assert player.attack() == boxloom.PlayerAttackResult(True, None, False)

    @pytest.mark.parametrize(
        "operation",
        (
            boxloom.Player("Steve", "test-uuid").get_location,
            boxloom.Player("Steve", "test-uuid").get_targeted_block,
            boxloom.Player("Steve", "test-uuid").attack,
            boxloom.Player("Steve", "test-uuid").use,
            boxloom.Player("Steve", "test-uuid").start_mining,
            boxloom.Player("Steve", "test-uuid").stop_mining,
            boxloom.Player("Steve", "test-uuid").start_using_item,
            boxloom.Player("Steve", "test-uuid").stop_using_item,
        ),
    )
    def test_snapshot_players_require_an_explicit_client_binding(self, operation):
        with pytest.raises(boxloom.ConfigurationError):
            operation()
        assert _ApiHandler.requests == []

    def test_targeted_block_rejects_malformed_snapshots(self):
        player = self._player()
        invalid = [{}, {"target": []}, {"target": "stone"}]
        for key, value in (
            ("face", "front"),
            ("x", True),
            ("y", 2.5),
            ("distance", -1),
            ("distance", float("inf")),
            ("distance", float("nan")),
            ("block", None),
        ):
            invalid.append({"target": {**self._target(), key: value}})
        for body in invalid:
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                player.get_targeted_block()

    def test_player_use_uses_its_bound_client_and_returns_block_or_air_result(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="origin-secret"
        )
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        target = boxloom.TargetedBlock(
            "minecraft:overworld",
            boxloom.BlockPos(-1, 64, 3),
            "minecraft:stone",
            "north",
            2.5,
        )
        for used, swung, snapshot in (
            (True, True, self._target()),
            (False, False, None),
        ):
            _ApiHandler.response_body = {
                "used": used,
                "swung": swung,
                "target": snapshot,
                "entity": None,
            }
            result = player.use()
            assert result == boxloom.PlayerUseResult(
                used, swung, target if snapshot else None
            )
            path, headers, body = _ApiHandler.requests[-1]
            assert path == "/v1/players/Steve/use"
            assert headers["Authorization"] == "Bearer origin-secret"
            assert body == {}

    def test_use_returns_an_entity_snapshot(self):
        player = self._player()
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:cow",
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 64,
            "z": 3,
            "yaw": 0,
            "pitch": 0,
            "name": "Cow",
            "health": 10,
        }
        _ApiHandler.response_body = {
            "used": True,
            "swung": False,
            "target": None,
            "entity": entity,
        }
        assert player.use().entity == boxloom.Entity(
            entity["uuid"],
            "minecraft:cow",
            "minecraft:overworld",
            boxloom.Vec3(1, 64, 3),
            0,
            0,
            "Cow",
            10,
        )
        _ApiHandler.response_body["target"] = self._target()
        with pytest.raises(boxloom.ProtocolError):
            player.use()

    def test_use_rejects_invalid_usernames_and_malformed_results(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for username in ("", "ab", "has-dash", None, 42):
            with pytest.raises(ValueError):
                client.use_player_item(username)
        assert _ApiHandler.requests == []
        valid = {"used": True, "swung": True, "target": None, "entity": None}
        invalid = [
            {},
            {**valid, "used": 1},
            {**valid, "swung": "true"},
            {**valid, "used": False},
            {**valid, "target": []},
            {**valid, "entity": "cow"},
        ]
        invalid.extend(
            {k: v for k, v in valid.items() if k != field} for field in valid
        )
        for body in invalid:
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                client.use_player_item("Steve")

    @pytest.mark.parametrize(
        "status, code",
        (
            (404, "PLAYER_NOT_CONNECTED"),
            (409, "PLAYER_CANNOT_USE"),
            (504, "MINECRAFT_TIMEOUT"),
        ),
    )
    def test_use_preserves_api_errors_without_retrying(self, status, code):
        player = self._player()
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot use"}}
        before = len(_ApiHandler.requests)
        with pytest.raises(boxloom.ApiError) as raised:
            player.use()
        assert (raised.value.status, raised.value.code) == (status, code)
        assert len(_ApiHandler.requests) == before + 1

    def test_continuous_item_use_keeps_client_binding_and_returns_native_state(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="origin-secret"
        )
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
        _ApiHandler.response_body = {
            "active": True,
            "item": "minecraft:apple",
            "remaining_ticks": 32,
        }
        assert player.start_using_item() == boxloom.PlayerItemUseResult(
            True, "minecraft:apple", 32
        )
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/item-use/start"
        _ApiHandler.response_body = {
            "active": False,
            "item": None,
            "remaining_ticks": 0,
        }
        assert player.stop_using_item() == boxloom.PlayerItemUseResult(False, None, 0)
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/item-use/stop"
        for path, headers, body in _ApiHandler.requests[-2:]:
            assert headers["Authorization"] == "Bearer origin-secret"
            assert body == {}

    def test_continuous_item_use_refusal_and_idempotent_stop(self):
        player = self._player()
        _ApiHandler.response_body = {
            "active": False,
            "item": None,
            "remaining_ticks": 0,
        }
        for operation in (
            player.start_using_item,
            player.stop_using_item,
            player.stop_using_item,
        ):
            assert operation() == boxloom.PlayerItemUseResult(False, None, 0)

    def test_continuous_item_use_rejects_malformed_results(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        valid = {"active": True, "item": "minecraft:bow", "remaining_ticks": 72000}
        invalid = [
            {},
            {**valid, "active": 1},
            {**valid, "item": ""},
            {**valid, "item": None},
            {**valid, "item": 1},
            {**valid, "remaining_ticks": -1},
            {**valid, "remaining_ticks": True},
            {**valid, "remaining_ticks": 2.5},
            {**valid, "remaining_ticks": "32"},
            {"active": False, "item": None, "remaining_ticks": 32},
            {"active": False, "item": "minecraft:apple", "remaining_ticks": 0},
        ]
        invalid.extend(
            {k: v for k, v in valid.items() if k != field} for field in valid
        )
        for operation in (
            client.start_using_player_item,
            client.stop_using_player_item,
        ):
            for body in invalid:
                _ApiHandler.response_body = body
                with pytest.raises(boxloom.ProtocolError):
                    operation("Steve")
        _ApiHandler.response_body = valid
        with pytest.raises(boxloom.ProtocolError):
            client.stop_using_player_item("Steve")

    def test_continuous_item_use_validates_names_before_network_io(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for operation in (
            client.start_using_player_item,
            client.stop_using_player_item,
        ):
            for name in ("", "ab", "has-dash", "not/a/player", None, 42):
                with pytest.raises(ValueError):
                    operation(name)
        assert _ApiHandler.requests == []

    @pytest.mark.parametrize(
        "status, code",
        (
            (404, "PLAYER_NOT_CONNECTED"),
            (409, "PLAYER_CANNOT_USE"),
            (409, "ITEM_NOT_CONTINUOUSLY_USABLE"),
            (504, "MINECRAFT_TIMEOUT"),
        ),
    )
    def test_continuous_item_use_propagates_errors_without_retrying(self, status, code):
        player = self._player()
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {"error": {"code": code, "message": "Cannot use"}}
        for operation in (player.start_using_item, player.stop_using_item):
            before = len(_ApiHandler.requests)
            with pytest.raises(boxloom.ApiError) as raised:
                operation()
            assert (raised.value.status, raised.value.code) == (status, code)
            assert len(_ApiHandler.requests) == before + 1

    @pytest.mark.parametrize(
        "body",
        (
            {},
            {"swung": 1, "target": None, "broken": False},
            {"swung": True, "target": None, "broken": "false"},
            {"swung": True, "target": None, "broken": True},
        ),
    )
    def test_attack_rejects_malformed_results(self, body):
        player = self._player()
        _ApiHandler.response_body = body
        with pytest.raises(boxloom.ProtocolError):
            player.attack()

    def test_entity_attack_returns_post_attack_snapshot(self):
        player = self._player()
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:zombie",
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 64,
            "z": 3,
            "yaw": 0,
            "pitch": 0,
            "name": "Zombie",
            "health": 19,
        }
        _ApiHandler.response_body = {
            "swung": True,
            "target": None,
            "broken": False,
            "entity": entity,
        }
        assert player.attack().entity == boxloom.Entity(
            entity["uuid"],
            "minecraft:zombie",
            "minecraft:overworld",
            boxloom.Vec3(1, 64, 3),
            0,
            0,
            "Zombie",
            19,
        )
        _ApiHandler.response_body["target"] = self._target()
        with pytest.raises(boxloom.ProtocolError):
            player.attack()
        _ApiHandler.response_body["target"] = None
        _ApiHandler.response_body["entity"] = "not-an-entity"
        with pytest.raises(boxloom.ProtocolError):
            player.attack()

    def test_player_mining_start_and_stop_use_bound_client(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="mining-secret"
        )
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1")
        _ApiHandler.response_body = {
            "active": True,
            "target": self._target(),
            "broken": False,
        }
        assert player.start_mining() == boxloom.PlayerMiningResult(
            True,
            boxloom.TargetedBlock(
                "minecraft:overworld",
                boxloom.BlockPos(-1, 64, 3),
                "minecraft:stone",
                "north",
                2.5,
            ),
            False,
        )
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/mining/start"
        assert _ApiHandler.requests[-1][1]["Authorization"] == "Bearer mining-secret"
        assert _ApiHandler.requests[-1][2] == {}
        _ApiHandler.response_body = {"active": False, "target": None, "broken": False}
        assert player.stop_mining() == boxloom.PlayerMiningResult(False, None, False)
        assert _ApiHandler.requests[-1][0] == "/v1/players/Steve/mining/stop"
        _ApiHandler.response_body = {"active": True, "target": None, "broken": False}
        assert player.start_mining() == boxloom.PlayerMiningResult(True, None, False)
        _ApiHandler.response_body = {
            "active": True,
            "target": self._target(),
            "broken": True,
        }
        assert player.start_mining().broken
        assert player.start_mining().active

    def test_mining_validates_arguments_results_and_server_rejection(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for action in (client.start_player_mining, client.stop_player_mining):
            with pytest.raises(ValueError):
                action("ab")
        assert _ApiHandler.requests == []
        player = self._player()
        for body in (
            {},
            {"active": 1, "target": None, "broken": False},
            {"active": True, "target": None, "broken": True},
            {"active": True, "target": self._target(), "broken": 1},
        ):
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                player.start_mining()
        _ApiHandler.response_body = {"active": True, "target": None, "broken": False}
        with pytest.raises(boxloom.ProtocolError):
            player.stop_mining()
        _ApiHandler.response_status = 409
        _ApiHandler.response_body = {
            "error": {"code": "PLAYER_CANNOT_ATTACK", "message": "spectator"}
        }
        with pytest.raises(boxloom.ApiError) as rejected:
            player.start_mining()
        assert rejected.value.code == "PLAYER_CANNOT_ATTACK"

    def test_player_actions_propagate_api_errors(self):
        player = self._player()
        for operation in (
            player.get_location,
            player.get_targeted_block,
            player.attack,
        ):
            _ApiHandler.response_status = 404
            _ApiHandler.response_body = {
                "error": {"code": "PLAYER_NOT_CONNECTED", "message": "offline"}
            }
            with pytest.raises(boxloom.ApiError) as raised:
                operation()
            assert raised.value.code == "PLAYER_NOT_CONNECTED"


    def test_say_posts_authenticated_message(self):
        _ApiHandler.response_body = {"message": "Hello!", "recipients": 2}

        result = boxloom.say("Hello!")

        assert result == boxloom.SayResult("Hello!", 2)
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/chat/messages"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body == {"message": "Hello!"}

    def test_say_omits_authorization_when_token_is_not_configured(self, monkeypatch):
        _ApiHandler.response_body = {"message": "Local", "recipients": 1}

        boxloom.init(base_url=self.base_url)
        result = boxloom.say("Local")

        assert result == boxloom.SayResult("Local", 1)
        assert "Authorization" not in _ApiHandler.requests[0][1]

    def test_get_player_location_reads_authenticated_location(self):
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

        result = boxloom.get_player_location("Player_123")

        assert result.username == "Player_123"
        assert result.dimension == "minecraft:the_nether"
        assert result.yaw == 90
        assert result.pitch == -12.5
        assert result.position.to_block_pos() == boxloom.BlockPos(-1, 64, -4)
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/players/Player_123/location"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

    def test_player_get_location_refreshes_through_its_originating_client(self):
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="location-secret"
        )
        player = self._player(client)
        boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")

        for y in (70.25, 71.5):
            _ApiHandler.response_body = {
                "username": player.username,
                "uuid": player.uuid,
                "dimension": "minecraft:the_nether",
                "x": -0.2,
                "y": y,
                "z": -3.2,
                "yaw": 90,
                "pitch": -12.5,
            }
            before = len(_ApiHandler.requests)

            result = player.get_location()

            assert result == boxloom.PlayerLocation(
                player.username,
                player.uuid,
                "minecraft:the_nether",
                boxloom.Vec3(-0.2, y, -3.2),
                90,
                -12.5,
            )
            assert len(_ApiHandler.requests) == before + 1
            path, headers, body = _ApiHandler.requests[-1]
            assert path == "/v1/players/Steve/location"
            assert headers["Authorization"] == "Bearer location-secret"
            assert body is None

    def test_coordinate_types_are_checked_before_network_io(self):
        vector = boxloom.Vec3(0, 64, 0)
        block = boxloom.BlockPos(0, 64, 0)
        for api in (boxloom, boxloom.BoxloomClient(base_url=self.base_url)):
            for operation in (
                lambda: api.get_block(vector),
                lambda: api.set_block(vector, "minecraft:stone"),
                lambda: api.fill(vector, block, "minecraft:stone"),
                lambda: api.fill(block, vector, "minecraft:stone"),
                lambda: api.teleport_player("Steve", block),
                lambda: api.summon("minecraft:pig", block),
                lambda: api.get_entities(center=block),
                lambda: api.kill_entities("minecraft:pig", center=block, radius=10),
                lambda: api.get_block((0, 64, 0)),
                lambda: api.summon("minecraft:pig", {"x": 0, "y": 64, "z": 0}),
            ):
                with pytest.raises(TypeError):
                    operation()
        assert _ApiHandler.requests == []

    @pytest.mark.parametrize("axis", ("x", "y", "z"))
    def test_coordinate_decoding_reports_protocol_errors(self, axis):
        player = {
            "username": "Steve",
            "uuid": "test-uuid",
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "yaw": 0,
            "pitch": 0,
        }
        block = {
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "block": "minecraft:stone",
        }
        for value in (True, None, "1", float("nan"), float("inf"), 10**1000):
            _ApiHandler.response_body = {**player, axis: value}
            with pytest.raises(boxloom.ProtocolError):
                boxloom.get_player_location("Steve")
        for value in (True, None, "1", 1.5, 2**31, -(2**31) - 1):
            _ApiHandler.response_body = {**block, axis: value}
            with pytest.raises(boxloom.ProtocolError):
                boxloom.get_block(boxloom.BlockPos(0, 64, 0))

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

        assert result == [
            boxloom.Player("Player_123", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"),
            boxloom.Player("Builder", "9ec7c42e-b767-4a47-b8c8-a68dc65bbde7"),
        ]
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/players"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

    def test_get_players_rejects_invalid_player_list(self):
        _ApiHandler.response_body = {"players": ["not-an-object"]}

        with pytest.raises(boxloom.ProtocolError):
            boxloom.get_players()

    def test_get_entities_reads_snapshots_and_encodes_filters(self):
        item = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:item",
            "dimension": "minecraft:the_nether",
            "x": 1.5,
            "y": 64,
            "z": -2.25,
            "yaw": 90,
            "pitch": -12.5,
            "name": "Diamond",
            "health": None,
        }
        zombie = {**item, "entity": "minecraft:zombie", "name": "Zombie", "health": 20}
        _ApiHandler.response_body = {"entities": [item, zombie]}

        result = boxloom.get_entities(
            "minecraft:zombie",
            center=boxloom.Vec3(1.5, 64, -2.25),
            radius=20,
            dimension="minecraft:the_nether",
            sort="nearest",
            limit=2,
        )

        assert result == [
            boxloom.Entity(
                item["uuid"],
                "minecraft:item",
                "minecraft:the_nether",
                boxloom.Vec3(1.5, 64, -2.25),
                90,
                -12.5,
                "Diamond",
                None,
            ),
            boxloom.Entity(
                zombie["uuid"],
                "minecraft:zombie",
                "minecraft:the_nether",
                boxloom.Vec3(1.5, 64, -2.25),
                90,
                -12.5,
                "Zombie",
                20,
            ),
        ]
        assert result[0].health is None
        assert result[1].health == 20.0
        path, headers, body = _ApiHandler.requests[0]
        assert urlsplit(path).path == "/v1/world/entities"
        assert parse_qs(urlsplit(path).query) == {
            "dimension": ["minecraft:the_nether"],
            "entity": ["minecraft:zombie"],
            "x": ["1.5"],
            "y": ["64.0"],
            "z": ["-2.25"],
            "radius": ["20"],
            "sort": ["nearest"],
            "limit": ["2"],
        }
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

    def test_get_entities_defaults_to_all_types_and_supports_sorting_without_radius(
        self,
    ):
        _ApiHandler.response_body = {"entities": []}
        assert boxloom.get_entities() == []
        assert parse_qs(urlsplit(_ApiHandler.requests[0][0]).query) == {
            "dimension": ["minecraft:overworld"],
            "sort": ["arbitrary"],
        }
        client = boxloom.BoxloomClient(base_url=self.base_url)
        assert (
            client.get_entities(center=boxloom.Vec3(0, 64, 0), sort="furthest", limit=1)
            == []
        )
        query = parse_qs(urlsplit(_ApiHandler.requests[1][0]).query)
        assert "radius" not in query
        assert query["sort"] == ["furthest"]

    def test_get_entities_rejects_invalid_arguments_before_network_io(self):
        invalid = [
            {"entity": ""},
            {"entity": True},
            {"dimension": ""},
            {"dimension": None},
            {"center": boxloom.BlockPos(0, 64, 0)},
            {"center": (0, 64, 0)},
            {"radius": 20},
            {"sort": "nearest"},
            {"sort": "furthest"},
            {"sort": "random"},
            {"sort": []},
            {"limit": 0},
            {"limit": -1},
            {"limit": True},
            {"limit": 1.5},
            {"limit": 2**31},
        ]
        for value in (
            True,
            "1",
            float("nan"),
            float("inf"),
            -float("inf"),
            10**1000,
            -1,
        ):
            invalid.append({"center": boxloom.Vec3(0, 64, 0), "radius": value})
        invalid.extend({"center": value} for value in (True, "1", [], {}))
        for kwargs in invalid:
            with pytest.raises((TypeError, ValueError)):
                boxloom.get_entities(**kwargs)
        assert _ApiHandler.requests == []

    def test_get_entities_rejects_malformed_response_fields(self):
        entity = {
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "entity": "minecraft:zombie",
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "yaw": 0,
            "pitch": 0,
            "name": "Zombie",
            "health": 20,
        }
        invalid = [{}, {"entities": None}, {"entities": {}}, {"entities": ["invalid"]}]
        for field in entity:
            missing = dict(entity)
            del missing[field]
            invalid.append({"entities": [missing]})
            invalid.append({"entities": [{**entity, field: True}]})
        invalid.append({"entities": [{**entity, "health": "20"}]})
        for body in invalid:
            _ApiHandler.response_body = body
            with pytest.raises(boxloom.ProtocolError):
                boxloom.get_entities()

    def test_get_entities_preserves_structured_api_errors(self):
        _ApiHandler.response_status = 400
        _ApiHandler.response_body = {
            "error": {"code": "INVALID_ENTITY", "message": "Unknown type"}
        }
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.get_entities("minecraft:missing")
        assert raised.value.code == "INVALID_ENTITY"
        assert raised.value.status == 400

    @pytest.mark.parametrize("mode", ("creative", "survival"))
    def test_set_player_gamemode_posts_both_modes_with_authentication(self, mode):
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "mode": mode,
        }
        result = boxloom.set_player_gamemode("steve", mode)
        assert result == boxloom.PlayerGameMode(
            "Steve", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", mode
        )
        path, headers, body = _ApiHandler.requests[-1]
        assert path == "/v1/players/steve/gamemode"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert headers["Content-Type"] == "application/json"
        assert body == {"mode": mode}

    def test_explicit_client_sets_player_gamemode(self):
        _ApiHandler.response_body = {
            "username": "Steve",
            "uuid": "test-uuid",
            "mode": "survival",
        }
        client = boxloom.BoxloomClient(base_url=self.base_url)
        assert client.set_player_gamemode("Steve", "survival").mode == "survival"
        assert "Authorization" not in _ApiHandler.requests[0][1]

    def test_set_player_gamemode_rejects_invalid_inputs_before_request(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        for operation in (boxloom.set_player_gamemode, client.set_player_gamemode):
            for username in (
                None,
                123,
                "",
                "ab",
                "a" * 17,
                "@a",
                "Steve/kill",
                "has-dash",
            ):
                with pytest.raises(ValueError):
                    operation(username, "creative")
            for mode in (
                None,
                1,
                True,
                [],
                {},
                "",
                "CREATIVE",
                " survival",
                "adventure",
                "spectator",
            ):
                with pytest.raises(ValueError):
                    operation("Steve", mode)
        assert _ApiHandler.requests == []

    def test_set_player_gamemode_rejects_malformed_or_mismatched_response(self):
        valid = {"username": "Steve", "uuid": "test-uuid", "mode": "creative"}
        for field in valid:
            for value in (None, 1, True, [], ""):
                with pytest.raises(boxloom.ProtocolError):
                    _ApiHandler.response_body = {**valid, field: value}
                    boxloom.set_player_gamemode("Steve", "creative")
            with pytest.raises(boxloom.ProtocolError):
                _ApiHandler.response_body = {
                    key: value for key, value in valid.items() if key != field
                }
                boxloom.set_player_gamemode("Steve", "creative")
        for mode in ("survival", "spectator", "CREATIVE"):
            with pytest.raises(boxloom.ProtocolError):
                _ApiHandler.response_body = {**valid, "mode": mode}
                boxloom.set_player_gamemode("Steve", "creative")

    @pytest.mark.parametrize(
        "status, code",
        ((404, "PLAYER_NOT_CONNECTED"), (409, "GAME_MODE_CHANGE_FAILED")),
    )
    def test_set_player_gamemode_preserves_api_errors(self, status, code):
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {
            "error": {"code": code, "message": "Cannot change mode"}
        }
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.set_player_gamemode("Steve", "survival")
        assert raised.value.status == status
        assert raised.value.code == code

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

        result = boxloom.teleport_player("Player_123", boxloom.Vec3(100, 64, -20))

        assert result.dimension == "minecraft:the_nether"
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/players/Player_123/teleport"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body == {"x": 100, "y": 64, "z": -20}

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
            boxloom.Vec3(1.5, 72, -2.25),
            dimension="minecraft:the_end",
            yaw=90,
            pitch=-12.5,
        )

        assert result.position.to_block_pos() == boxloom.BlockPos(1, 72, -3)
        assert _ApiHandler.requests[0][2] == {
            "x": 1.5,
            "y": 72,
            "z": -2.25,
            "dimension": "minecraft:the_end",
            "yaw": 90,
            "pitch": -12.5,
        }

    def test_teleport_rejects_invalid_coordinates_before_request(self):
        client = boxloom.BoxloomClient(base_url=self.base_url)
        with pytest.raises(ValueError):
            client.teleport_player("Player_123", boxloom.Vec3(float("inf"), 64, 0))

        assert _ApiHandler.requests == []

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
            assert events.last_event_id == event_id

        assert event.id == event_id
        assert event.message == "hello from Minecraft"
        assert event.player == boxloom.Player(
            "Player_123", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        )
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/events"
        assert headers["Accept"] == "text/event-stream"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

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

        assert event.id == event_id
        assert event.message == "hello"

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
            with pytest.raises(boxloom.ProtocolError):
                next(events)

    def test_event_players_refresh_locations_through_the_originating_client(self):
        frames = [
            ("chat.message", _chat_sse("session:1", "chicken")),
            ("player.death", _death_sse("session:2")),
            *[
                (kind, _action_sse(kind, f"session:{i}"))
                for i, kind in enumerate(
                    ("player.swing", "block.attack", "block.use", "item.use"),
                    3,
                )
            ],
        ]
        sources = [(None, b"".join(frame for _, frame in frames), len(frames))]
        sources.extend((kind, frame, 1) for kind, frame in frames)
        sources.append(("watch_chat", frames[0][1], 1))
        client = boxloom.BoxloomClient(
            base_url=self.base_url, auth_token="event-secret"
        )
        for api in (boxloom, client):
            for kind, source, count in sources:
                boxloom.init(base_url=self.base_url, auth_token="event-secret")
                _ApiHandler.stream_responses = [(200, "text/event-stream", source)]
                stream = (
                    api.watch_chat(reconnect=False)
                    if kind == "watch_chat"
                    else api.watch_event(kind, reconnect=False)
                )
                boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
                before = len(_ApiHandler.requests)
                with stream:
                    events = [next(stream) for _ in range(count)]
                assert len(_ApiHandler.requests) == before + 1
                for event in events:
                    for y in (70.25, 71.5):
                        _ApiHandler.response_body = {
                            "username": event.player.username,
                            "uuid": event.player.uuid,
                            "dimension": "minecraft:the_nether",
                            "x": 1.5,
                            "y": y,
                            "z": -2.25,
                            "yaw": 90,
                            "pitch": -12.5,
                        }
                        state = event.player.get_location()
                        assert state.position == boxloom.Vec3(1.5, y, -2.25)
                        assert state.dimension == "minecraft:the_nether"
                        path, headers, _ = _ApiHandler.requests[-1]
                        assert path == "/v1/players/Player_123/location"
                        assert headers["Authorization"] == "Bearer event-secret"

    def test_watch_event_returns_mixed_events_with_type_discriminators(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _sse(
                    "stream.ready",
                    "session:0",
                    {"type": "stream.ready", "cursor": "session:0"},
                )
                + _chat_sse("session:1", "hello")
                + _sse("future.event", "session:2", {"type": "future.event"})
                + _death_sse("session:3"),
            )
        ]

        with boxloom.watch_event(reconnect=False) as events:
            chat = next(events)
            death = next(events)
            assert events.last_event_id == "session:3"

        assert isinstance(chat, boxloom.ChatEvent)
        assert chat.type == "chat.message"
        assert chat.message == "hello"
        assert isinstance(death, boxloom.PlayerDeathEvent)
        assert death.type == "player.death"
        assert death.id == "session:3"
        assert len(_ApiHandler.requests) == 1

    @pytest.mark.parametrize(
        "kind, cls",
        list(
            zip(
                ("player.swing", "block.attack", "block.use", "item.use"),
                (
                    boxloom.PlayerSwingEvent,
                    boxloom.BlockAttackEvent,
                    boxloom.BlockUseEvent,
                    boxloom.ItemUseEvent,
                ),
            )
        ),
    )
    def test_watch_event_decodes_all_player_actions_and_filters_by_type(
        self, kind, cls
    ):
        kinds = ("player.swing", "block.attack", "block.use", "item.use")
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _chat_sse("session:1", "skip")
                + _action_sse(kind, "session:2", hand="off_hand", item=None),
            )
        ]
        with boxloom.watch_event(kind, reconnect=False) as events:
            event = next(events)
            assert events.last_event_id == "session:2"
        assert isinstance(event, cls)
        assert event.type == kind
        assert event.player.username == "Player_123"
        assert event.hand == "off_hand"
        assert event.item is None
        assert event.position == boxloom.Vec3(1.5, 64.0, -2.25)
        if kind.startswith("block."):
            assert event.block == "minecraft:stone"
            assert event.block_position == boxloom.BlockPos(-2, 63, 4)
            assert event.face == "up"

        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                b"".join(
                    _action_sse(kind, f"session:{i}") for i, kind in enumerate(kinds, 1)
                ),
            )
        ]
        with boxloom.watch_event(reconnect=False) as events:
            assert [next(events).type for _ in kinds] == list(kinds)

    def test_action_events_are_skipped_by_chat_filter_and_advance_resume_cursor(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                b"retry: 1\n\n"
                + _action_sse("player.swing", "session:1")
                + _action_sse("block.attack", "session:2"),
            ),
            (200, "text/event-stream", _chat_sse("session:3", "after swing")),
        ]
        with boxloom.watch_chat() as events:
            assert next(events).message == "after swing"
        assert _ApiHandler.requests[1][1]["Last-Event-Id"] == "session:2"

    @pytest.mark.parametrize(
        "kind", ("player.swing", "block.attack", "block.use", "item.use")
    )
    def test_watch_event_rejects_malformed_action_payloads(self, kind):
        cases = [
            {"id": "wrong"},
            {"type": "wrong"},
            {"player": None},
            {"player": {"username": "Steve"}},
            {"timestamp": None},
            {"dimension": None},
            {"x": True},
            {"y": "64"},
            {"hand": "left"},
            {"hand": None},
            {"item": 1},
        ]
        if kind.startswith("block."):
            cases += [
                {"block": None},
                {"block_x": 1.5},
                {"block_y": True},
                {"block_z": "4"},
                {"face": "diagonal"},
            ]
        for changes in cases:
            _ApiHandler.stream_responses = [
                (200, "text/event-stream", _action_sse(kind, "session:1", **changes))
            ]
            with boxloom.watch_event(kind, reconnect=False) as events:
                with pytest.raises(boxloom.ProtocolError):
                    next(events)
        payload = _action_payload("player.swing", "session:1")
        del payload["item"]
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _sse("player.swing", "session:1", payload))
        ]
        with boxloom.watch_event("player.swing", reconnect=False) as events:
            with pytest.raises(boxloom.ProtocolError):
                next(events)

    def test_watch_event_filters_chat_by_keyword(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _death_sse("session:1") + _chat_sse("session:2", "hello"),
            )
        ]
        client = boxloom.BoxloomClient(base_url=self.base_url)

        with client.watch_event(event_type="chat.message", reconnect=False) as events:
            event = next(events)
        assert event.type == "chat.message"
        assert event.id == "session:2"

    def test_watch_event_streams_an_authenticated_death_and_skips_chat(self):
        event_id = "session:2"
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _chat_sse("session:1", "hello") + _death_sse(event_id),
            )
        ]

        with boxloom.watch_event("player.death", reconnect=False) as events:
            event = next(events)
            assert events.last_event_id == event_id
            assert isinstance(events, boxloom.EventStream)

        assert event == boxloom.PlayerDeathEvent(
            id=event_id,
            timestamp="2026-08-14T00:00:00Z",
            message="Player_123 fell from a high place",
            player=boxloom.Player("Player_123", "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"),
            dimension="minecraft:the_nether",
            position=boxloom.Vec3(1.5, 64.0, -2.25),
        )
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/events"
        assert headers["Accept"] == "text/event-stream"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

    def test_watch_chat_skips_player_deaths(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                _death_sse("session:1") + _chat_sse("session:2", "still here"),
            )
        ]

        with boxloom.watch_chat(reconnect=False) as events:
            assert next(events).message == "still here"

    def test_watch_event_resumes_after_skipped_events_on_reconnect(self):
        _ApiHandler.stream_responses = [
            (
                200,
                "text/event-stream",
                b"retry: 1\n\n"
                + _sse(
                    "stream.ready",
                    "session:0",
                    {"type": "stream.ready", "cursor": "session:0"},
                )
                + _chat_sse("session:1", "hello")
                + _sse("future.event", "session:2", {"type": "future.event"}),
            ),
            (200, "text/event-stream", _death_sse("session:3")),
        ]
        client = boxloom.BoxloomClient(base_url=self.base_url)

        with client.watch_event("player.death", last_event_id="saved:0") as events:
            assert next(events).id == "session:3"

        headers = [
            {name.lower(): value for name, value in request[1].items()}
            for request in _ApiHandler.requests
        ]
        assert headers[0]["last-event-id"] == "saved:0"
        assert headers[1]["last-event-id"] == "session:2"

    @pytest.mark.parametrize(
        "changes",
        [
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
        ],
    )
    def test_watch_event_rejects_malformed_death_payloads(self, changes):
        _ApiHandler.stream_responses = [
            (200, "text/event-stream", _death_sse("session:1", **changes))
        ]
        with boxloom.watch_event("player.death", reconnect=False) as events:
            with pytest.raises(boxloom.ProtocolError):
                next(events)

    def test_watch_event_maps_expired_cursor_and_stream_reset(self):
        _ApiHandler.response_status = 410
        _ApiHandler.response_body = {
            "error": {"code": "EVENT_CURSOR_EXPIRED", "message": "Expired"},
        }
        with boxloom.watch_event("player.death", last_event_id="saved:1") as events:
            with pytest.raises(boxloom.EventCursorExpiredError):
                next(events)

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
        with boxloom.watch_event("player.death", reconnect=False) as events:
            with pytest.raises(boxloom.EventCursorExpiredError):
                next(events)

    def test_watch_event_validates_options_and_stops_when_closed(self):
        for cursor in ("", "\n", "saved:1\r\nInjected: value", 1):
            with pytest.raises(boxloom.ConfigurationError):
                boxloom.watch_event("player.death", last_event_id=cursor)
        with pytest.raises(boxloom.ConfigurationError):
            boxloom.watch_event("player.death", reconnect="yes")
        for event_type in ("", "player.deth", 1, ["player.death"]):
            with pytest.raises(ValueError):
                boxloom.watch_event(event_type)
        events = boxloom.watch_event("player.death")
        events.close()
        with pytest.raises(StopIteration):
            next(events)
        assert _ApiHandler.requests == []

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

        assert first.message == "one"
        assert second.message == "two"
        assert len(_ApiHandler.requests) == 2
        first_headers = {
            name.lower(): value for name, value in _ApiHandler.requests[0][1].items()
        }
        second_headers = {
            name.lower(): value for name, value in _ApiHandler.requests[1][1].items()
        }
        assert "last-event-id" not in first_headers
        assert second_headers["last-event-id"] == first_id

    def test_watch_chat_maps_an_expired_cursor_response(self):
        _ApiHandler.response_status = 410
        _ApiHandler.response_body = {
            "error": {
                "code": "EVENT_CURSOR_EXPIRED",
                "message": "The requested events are no longer retained",
            }
        }

        with boxloom.watch_chat(last_event_id="old:1") as events:
            with pytest.raises(boxloom.EventCursorExpiredError):
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
            with pytest.raises(boxloom.EventCursorExpiredError):
                next(events)

    def test_get_player_location_rejects_invalid_username(self):
        with pytest.raises(ValueError):
            boxloom.get_player_location("not/a/player")

        assert _ApiHandler.requests == []

    @pytest.mark.parametrize("clock", ("minecraft:overworld", "minecraft:the_end"))
    def test_get_time_reads_default_or_explicit_clock_and_64_bit_ticks(self, clock):
        _ApiHandler.response_body = {"clock": clock, "ticks": 3_000_000_000}
        if clock == "minecraft:overworld":
            result = boxloom.get_time()
        else:
            result = boxloom.BoxloomClient(base_url=self.base_url).get_time(clock=clock)
        assert result == boxloom.TimeResult(clock, 3000000000)
        path, headers, body = _ApiHandler.requests[-1]
        assert path == "/v1/world/time?clock=" + clock.replace(":", "%3A")
        assert body is None
        if clock == "minecraft:overworld":
            assert headers["Authorization"] == "Bearer unit-test-secret"

    @pytest.mark.parametrize(
        "time", ("day", "noon", "night", "midnight", "example:dawn", 0, 2**31 - 1)
    )
    def test_set_time_posts_markers_and_absolute_ticks(self, time):
        _ApiHandler.response_body = {"clock": "minecraft:overworld", "ticks": 25_000}
        result = boxloom.set_time(time)
        assert result == boxloom.TimeResult("minecraft:overworld", 25000)
        path, headers, body = _ApiHandler.requests[-1]
        assert path == "/v1/world/time"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body == {"clock": "minecraft:overworld", "time": time}

    def test_set_time_client_posts_an_explicit_clock(self):
        _ApiHandler.response_body = {"clock": "example:clock", "ticks": 6000}
        result = boxloom.BoxloomClient(base_url=self.base_url).set_time(
            6000, clock="example:clock"
        )
        assert result == boxloom.TimeResult("example:clock", 6000)
        assert _ApiHandler.requests[0][2] == {"clock": "example:clock", "time": 6000}

    @pytest.mark.parametrize("ticks", (-(2**31), -20, 0, 6000, 2**31 - 1))
    def test_add_time_posts_signed_ticks(self, ticks):
        _ApiHandler.response_body = {"clock": "minecraft:overworld", "ticks": 0}
        result = boxloom.add_time(ticks)
        assert result == boxloom.TimeResult("minecraft:overworld", 0)
        path, headers, body = _ApiHandler.requests[-1]
        assert path == "/v1/world/time/add"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body == {"clock": "minecraft:overworld", "ticks": ticks}
        _ApiHandler.response_body = {"clock": "minecraft:the_end", "ticks": 20}
        result = boxloom.BoxloomClient(base_url=self.base_url).add_time(
            20, clock="minecraft:the_end"
        )
        assert result == boxloom.TimeResult("minecraft:the_end", 20)
        assert _ApiHandler.requests[-1][2] == {
            "clock": "minecraft:the_end",
            "ticks": 20,
        }

    def test_time_rejects_invalid_inputs_before_request(self):
        for time in (True, False, 1.5, None, {}, [], -1, 2**31, "", " "):
            with pytest.raises((TypeError, ValueError)):
                boxloom.set_time(time)
        for ticks in (True, 1.5, None, "20", -(2**31) - 1, 2**31):
            with pytest.raises((TypeError, ValueError)):
                boxloom.add_time(ticks)
        for clock in (None, True, 1, "", " "):
            for operation in (
                lambda: boxloom.get_time(clock=clock),
                lambda: boxloom.set_time("day", clock=clock),
                lambda: boxloom.add_time(20, clock=clock),
            ):
                with pytest.raises(ValueError):
                    operation()
        assert _ApiHandler.requests == []

    def test_time_rejects_malformed_responses(self):
        invalid_payloads = (
            {},
            {"clock": None, "ticks": 20},
            {"clock": "minecraft:overworld"},
            *(
                {"clock": "minecraft:overworld", "ticks": ticks}
                for ticks in (True, "20", 1.5, -1, 2**63)
            ),
        )
        for payload in invalid_payloads:
            _ApiHandler.response_body = payload
            for operation in (
                boxloom.get_time,
                lambda: boxloom.set_time("day"),
                lambda: boxloom.add_time(20),
            ):
                with pytest.raises(boxloom.ProtocolError):
                    operation()

    @pytest.mark.parametrize(
        "status, code",
        (
            (404, "CLOCK_NOT_FOUND"),
            (404, "TIME_MARKER_NOT_FOUND"),
            (409, "TIME_ALREADY_AT_MARKER"),
        ),
    )
    def test_time_preserves_clock_and_marker_errors(self, status, code):
        _ApiHandler.response_status = status
        _ApiHandler.response_body = {"error": {"code": code, "message": "Missing"}}
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.set_time("day")
        assert raised.value.code == code
        assert raised.value.status == status

    def test_block_properties_round_trip_as_strings(self):
        snapshot = {
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 64,
            "z": 2,
            "block": "minecraft:wheat",
            "properties": {"age": "3"},
        }
        _ApiHandler.response_body = {**snapshot, "changed": True}
        result = boxloom.set_block(
            boxloom.BlockPos(1, 64, 2), boxloom.blocks.WHEAT, properties={"age": "3"}
        )
        assert result.properties == {"age": "3"}
        assert _ApiHandler.requests[-1][2]["properties"] == {"age": "3"}
        _ApiHandler.response_body = snapshot
        result = boxloom.get_block(boxloom.BlockPos(1, 64, 2))
        assert int(result.properties["age"]) == 3
        _ApiHandler.response_body = {**snapshot, "properties": {}}
        assert boxloom.get_block(boxloom.BlockPos(1, 64, 2)).properties == {}

    def test_block_properties_mappings_are_immutable_snapshots(self):
        from types import MappingProxyType

        properties = {"age": "3"}
        block = boxloom.Block(
            "minecraft:overworld",
            boxloom.BlockPos(1, 64, 2),
            "minecraft:wheat",
            properties,
        )
        target = boxloom.TargetedBlock(
            "minecraft:overworld",
            boxloom.BlockPos(1, 64, 2),
            "minecraft:wheat",
            "up",
            2.0,
            properties=properties,
        )
        result = boxloom.SetBlockResult(
            True,
            "minecraft:overworld",
            boxloom.BlockPos(1, 64, 2),
            "minecraft:wheat",
            properties,
        )
        properties["age"] = "7"
        for value in (block, target, result):
            assert value.properties == {"age": "3"}
            with pytest.raises(TypeError):
                value.properties["age"] = "4"
        assert isinstance(target, boxloom.Block)
        assert isinstance(hash(block), int)
        _ApiHandler.response_body = {
            "changed": False,
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 64,
            "z": 2,
            "block": "minecraft:wheat",
            "properties": {"age": "3"},
        }
        boxloom.BoxloomClient(base_url=self.base_url).set_block(
            boxloom.BlockPos(1, 64, 2),
            "minecraft:wheat",
            properties=MappingProxyType({"age": "3"}),
        )
        assert _ApiHandler.requests[-1][2]["properties"] == {"age": "3"}

    @pytest.mark.parametrize(
        "properties",
        (
            [],
            "age=3",
            3,
            True,
            {"age": 3},
            {"lit": True},
            {1: "3"},
            {"age": None},
            {"age": []},
            {"": "3"},
            {"age": ""},
        ),
    )
    def test_set_block_rejects_malformed_properties_before_network_io(self, properties):
        with pytest.raises((TypeError, ValueError)):
            boxloom.set_block(
                boxloom.BlockPos(1, 64, 2), "minecraft:wheat", properties=properties
            )
        assert _ApiHandler.requests == []

    @pytest.mark.parametrize(
        "properties",
        (
            None,
            [],
            "age=3",
            {"age": 3},
            {"lit": True},
            {"age": None},
            {"": "3"},
            {"age": ""},
        ),
    )
    def test_block_property_responses_reject_non_string_values(self, properties):
        player = self._player()
        target = self._target()
        snapshot = {
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 64,
            "z": 2,
            "block": "minecraft:wheat",
        }
        _ApiHandler.response_body = {
            **snapshot,
            "properties": properties,
            "changed": True,
        }
        with pytest.raises(boxloom.ProtocolError):
            boxloom.get_block(boxloom.BlockPos(1, 64, 2))
        with pytest.raises(boxloom.ProtocolError):
            boxloom.set_block(boxloom.BlockPos(1, 64, 2), "minecraft:wheat")
        _ApiHandler.response_body = {"target": {**target, "properties": properties}}
        with pytest.raises(boxloom.ProtocolError):
            player.get_targeted_block()

    def test_targeted_block_includes_immutable_properties(self):
        player = self._player()
        _ApiHandler.response_body = {
            "target": {**self._target(), "properties": {"age": "3"}}
        }
        assert player.get_targeted_block().properties == {"age": "3"}

    def test_set_block_preserves_property_validation_errors_without_retry(self):
        _ApiHandler.response_status = 400
        _ApiHandler.response_body = {
            "error": {"code": "INVALID_BLOCK_PROPERTY", "message": "Invalid age"}
        }
        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.set_block(
                boxloom.BlockPos(1, 64, 2), "minecraft:wheat", properties={"age": "8"}
            )
        assert (raised.value.status, raised.value.code) == (
            400,
            "INVALID_BLOCK_PROPERTY",
        )
        assert len(_ApiHandler.requests) == 1

    def test_set_block_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "changed": True,
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 80,
            "z": -2,
            "block": "minecraft:gold_block",
        }

        result = boxloom.set_block(
            boxloom.BlockPos(1, 80, -2), boxloom.blocks.GOLD_BLOCK
        )

        assert result.changed
        assert result.block == "minecraft:gold_block"
        assert _ApiHandler.requests[0][2] == {
            "dimension": "minecraft:overworld",
            "x": 1,
            "y": 80,
            "z": -2,
            "block": "minecraft:gold_block",
        }

    def test_get_block_returns_block_result(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:the_nether",
            "x": 1,
            "y": 64,
            "z": -2,
            "block": "minecraft:stone",
        }

        result = boxloom.get_block(
            boxloom.BlockPos(1, 64, -2), dimension="minecraft:the_nether"
        )
        assert boxloom.Block is type(result)

        assert result == boxloom.Block(
            "minecraft:the_nether", boxloom.BlockPos(1, 64, -2), "minecraft:stone"
        )
        path, headers, body = _ApiHandler.requests[0]
        assert path == "/v1/world/blocks?dimension=minecraft%3Athe_nether&x=1&y=64&z=-2"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body is None

    def test_get_block_uses_overworld_by_default(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:overworld",
            "x": 0,
            "y": 64,
            "z": 0,
            "block": "minecraft:air",
        }

        result = boxloom.BoxloomClient(base_url=self.base_url).get_block(
            boxloom.BlockPos(0, 64, 0)
        )

        assert result.block == "minecraft:air"
        assert result.dimension == "minecraft:overworld"
        assert result.position == boxloom.BlockPos(0, 64, 0)
        assert (
            _ApiHandler.requests[0][0]
            == "/v1/world/blocks?dimension=minecraft%3Aoverworld&x=0&y=64&z=0"
        )

    def test_get_block_rejects_invalid_coordinates_before_request(self):
        with pytest.raises(TypeError):
            boxloom.get_block(boxloom.BlockPos(1.5, 64, 0))
        with pytest.raises(ValueError):
            boxloom.get_block(boxloom.BlockPos(2**31, 64, 0))

        assert _ApiHandler.requests == []

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
            boxloom.BlockPos(2, 65, -1),
            boxloom.BlockPos(1, 64, -2),
            "minecraft:stone",
            dimension="minecraft:the_nether",
        )

        assert result == boxloom.FillResult(
            8,
            "minecraft:the_nether",
            boxloom.BlockPos(2, 65, -1),
            boxloom.BlockPos(1, 64, -2),
            "minecraft:stone",
        )
        assert _ApiHandler.requests[0][0] == "/v1/world/blocks/fill"
        assert _ApiHandler.requests[0][2] == {
            "dimension": "minecraft:the_nether",
            "x1": 2,
            "y1": 65,
            "z1": -1,
            "x2": 1,
            "y2": 64,
            "z2": -2,
            "block": "minecraft:stone",
        }

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
            boxloom.BlockPos(0, 64, 0),
            boxloom.BlockPos(0, 64, 0),
            boxloom.blocks.GOLD_BLOCK,
        )

        assert _ApiHandler.requests[0][2]["dimension"] == "minecraft:overworld"

    def test_fill_rejects_invalid_or_oversized_regions_before_request(self):
        with pytest.raises(TypeError):
            boxloom.fill(
                boxloom.BlockPos(0, 0, 0),
                boxloom.BlockPos(1.5, 1, 1),
                "minecraft:stone",
            )
        with pytest.raises(ValueError):
            boxloom.fill(
                boxloom.BlockPos(0, 0, 0),
                boxloom.BlockPos(32, 32, 30),
                "minecraft:stone",
            )

        assert _ApiHandler.requests == []

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

        result = boxloom.summon(
            boxloom.entities.ARROW, boxloom.Vec3(1.5, 72, -2.25), nbt=nbt
        )

        assert result == boxloom.SummonResult(
            "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af",
            "minecraft:arrow",
            "minecraft:overworld",
            boxloom.Vec3(1.5, 72.0, -2.25),
        )
        assert _ApiHandler.requests[0][0] == "/v1/world/entities"
        assert _ApiHandler.requests[0][2] == {
            "dimension": "minecraft:overworld",
            "entity": "minecraft:arrow",
            "x": 1.5,
            "y": 72,
            "z": -2.25,
            "nbt": nbt,
        }

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
            "minecraft:pig", boxloom.Vec3(0, 64, 0), dimension="minecraft:the_nether"
        )

        assert "nbt" not in _ApiHandler.requests[0][2]

    @pytest.mark.parametrize(
        "nbt",
        (
            {"value": None},
            {"value": float("nan")},
            {"value": 2**63},
            {1: "non-string key"},
            {"value": (1, 2, 3)},
        ),
    )
    def test_summon_rejects_values_without_nbt_representation(self, nbt):

        with pytest.raises((TypeError, ValueError)):
            boxloom.summon("minecraft:pig", boxloom.Vec3(0, 64, 0), nbt=nbt)

        assert _ApiHandler.requests == []

    def test_summon_rejects_non_finite_coordinates(self):
        with pytest.raises(ValueError):
            boxloom.summon("minecraft:pig", boxloom.Vec3(float("inf"), 64, 0))

        assert _ApiHandler.requests == []

    def test_kill_entity_posts_uuid_and_dimension(self):
        uuid = "58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"
        _ApiHandler.response_body = {
            "dimension": "minecraft:the_nether",
            "killedEntities": 1,
        }

        result = boxloom.kill_entity(uuid, dimension="minecraft:the_nether")

        assert result == boxloom.KillResult("minecraft:the_nether", 1)
        path, headers, body = _ApiHandler.requests[0]
        assert path == f"/v1/world/entities/{uuid}/kill"
        assert headers["Authorization"] == "Bearer unit-test-secret"
        assert body == {"dimension": "minecraft:the_nether"}

    def test_kill_entities_posts_complete_range(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:the_end",
            "killedEntities": 3,
        }

        result = boxloom.kill_entities(
            "minecraft:zombie",
            center=boxloom.Vec3(1.5, 64, -2.25),
            radius=20,
            dimension="minecraft:the_end",
        )

        assert result == boxloom.KillResult("minecraft:the_end", 3)
        assert _ApiHandler.requests[0][0] == "/v1/world/entities/kill"
        assert _ApiHandler.requests[0][2] == {
            "dimension": "minecraft:the_end",
            "entity": "minecraft:zombie",
            "x": 1.5,
            "y": 64,
            "z": -2.25,
            "radius": 20,
        }

    def test_client_kill_defaults_and_zero_results(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:overworld",
            "killedEntities": 0,
        }
        client = boxloom.BoxloomClient(base_url=self.base_url)

        assert (
            client.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af").killed_entities
            == 0
        )
        assert client.kill_entities("minecraft:zombie").killed_entities == 0
        assert _ApiHandler.requests[0][2] == {"dimension": "minecraft:overworld"}
        assert _ApiHandler.requests[1][2] == {
            "dimension": "minecraft:overworld",
            "entity": "minecraft:zombie",
        }

    def test_kill_entities_accepts_zero_radius(self):
        _ApiHandler.response_body = {
            "dimension": "minecraft:overworld",
            "killedEntities": 1,
        }

        boxloom.kill_entities(
            "minecraft:zombie", center=boxloom.Vec3(0, 64, 0), radius=0
        )

        assert _ApiHandler.requests[0][2]["radius"] == 0

    def test_kill_rejects_invalid_parameters_before_request(self):
        for uuid in ("not-a-uuid", "1-1-1-1-1", "@e", "", None, 123):
            with pytest.raises(ValueError):
                boxloom.kill_entity(uuid)
        for kwargs in (
            {"center": boxloom.Vec3(0, 64, 0)},
            {"radius": 20},
            {"center": boxloom.Vec3(0, 64, 0), "radius": -1},
            {"center": boxloom.BlockPos(0, 64, 0), "radius": 20},
            {"center": (0, 64, 0), "radius": 20},
            {"center": True, "radius": 20},
            {"center": boxloom.Vec3(0, 64, 0), "radius": float("nan")},
            {"center": boxloom.Vec3(0, 64, 0), "radius": 10**1000},
            {"center": boxloom.Vec3(0, 64, 0), "radius": True},
            {"dimension": ""},
        ):
            with pytest.raises((TypeError, ValueError)):
                boxloom.kill_entities("minecraft:zombie", **kwargs)
        for entity in ("", " ", None, 123):
            with pytest.raises(ValueError):
                boxloom.kill_entities(entity)
        with pytest.raises(ValueError):
            boxloom.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af", dimension="")
        assert _ApiHandler.requests == []

    @pytest.mark.parametrize(
        "payload",
        (
            {},
            {"dimension": "minecraft:overworld"},
            {"dimension": "minecraft:overworld", "killedEntities": -1},
            {"dimension": "minecraft:overworld", "killedEntities": True},
            {"dimension": "minecraft:overworld", "killedEntities": 1.5},
            {"dimension": None, "killedEntities": 1},
        ),
    )
    def test_kill_rejects_malformed_responses(self, payload):
        _ApiHandler.response_body = payload
        for operation in (
            lambda: boxloom.kill_entity("58f6e634-15d9-4d4c-8ca0-8a4b23fe38af"),
            lambda: boxloom.kill_entities("minecraft:zombie"),
        ):
            with pytest.raises(boxloom.ProtocolError):
                operation()

    def test_api_error_does_not_expose_auth_token(self):
        _ApiHandler.response_status = 403
        _ApiHandler.response_body = {
            "error": {"code": "FORBIDDEN", "message": "The token is invalid"}
        }

        with pytest.raises(boxloom.ApiError) as raised:
            boxloom.say("Hello")

        assert raised.value.status == 403
        assert raised.value.code == "FORBIDDEN"
        assert "unit-test-secret" not in str(raised.value)

    def test_lazy_configuration_reads_environment(self, monkeypatch):
        _ApiHandler.response_body = {"message": "From env", "recipients": 0}
        boxloom._reset_default_client_for_testing()

        monkeypatch.setenv("BOXLOOM_BASE_URL", self.base_url)
        monkeypatch.setenv("BOXLOOM_AUTH_TOKEN", "env-secret")
        boxloom.say("From env")

        assert _ApiHandler.requests[0][1]["Authorization"] == "Bearer env-secret"

    def test_get_players_lazily_initializes_without_auth_token(self, monkeypatch):
        _ApiHandler.response_body = {"players": []}
        boxloom._reset_default_client_for_testing()

        monkeypatch.setenv("BOXLOOM_BASE_URL", self.base_url)
        result = boxloom.get_players()

        assert result == []
        assert _ApiHandler.requests[0][0] == "/v1/players"
        assert "Authorization" not in _ApiHandler.requests[0][1]

    def test_get_players_lazily_initializes_with_empty_auth_token(self, monkeypatch):
        _ApiHandler.response_body = {"players": []}
        boxloom._reset_default_client_for_testing()

        monkeypatch.setenv("BOXLOOM_BASE_URL", self.base_url)
        monkeypatch.setenv("BOXLOOM_AUTH_TOKEN", "")
        result = boxloom.get_players()

        assert result == []
        assert _ApiHandler.requests[0][0] == "/v1/players"
        assert "Authorization" not in _ApiHandler.requests[0][1]


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
        "type": kind,
        "id": event_id,
        "timestamp": "2026-11-06T00:00:00Z",
        "player": {"username": "Player_123", "uuid": "player-uuid"},
        "dimension": "minecraft:overworld",
        "x": 1.5,
        "y": 64,
        "z": -2.25,
        "hand": "main_hand",
        "item": "minecraft:diamond_sword",
    }
    if kind.startswith("block."):
        payload.update(
            block="minecraft:stone", block_x=-2, block_y=63, block_z=4, face="up"
        )
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
