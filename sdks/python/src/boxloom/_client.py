import json
import re
import socket
from math import isfinite
from typing import Any, Callable, Dict, List, Literal, Mapping, Optional, Union, cast, overload
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

from .errors import (
    ConfigurationError,
    ProtocolError,
)
from ._transport import (
    _api_error,
    _connection_error,
    _decode_object,
    _require_integer,
    _require_number,
    _require_string,
)
from .events import ChatEventStream, EventStream, EventSubscription, EventType
from .models import (
    ChatEvent,
    Entity,
    Event,
    FillResult,
    Block,
    GameMode,
    KillResult,
    Player,
    PlayerGameMode,
    PlayerDeathEvent,
    PlayerSwingEvent,
    BlockAttackEvent,
    BlockUseEvent,
    ItemUseEvent,
    PlayerPosition,
    PlayerAttackResult,
    PlayerMiningResult,
    TargetedBlock,
    BlockFace,
    SayResult,
    SetBlockResult,
    SummonResult,
    TimeResult,
)


_USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
_UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
_MAX_FILL_BLOCKS = 32_768


class BoxloomClient:
    """HTTP client for a boxloom Fabric server."""

    def __init__(
        self,
        *,
        base_url: str,
        auth_token: Optional[str] = None,
        timeout: float = 10.0,
    ) -> None:
        self._base_url = _validate_base_url(base_url)
        if auth_token is not None and (
            not isinstance(auth_token, str) or not auth_token.strip()
        ):
            raise ConfigurationError("auth_token must be a non-empty string or None")
        if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0:
            raise ConfigurationError("timeout must be a positive number of seconds")

        self._auth_token = auth_token
        self._timeout = float(timeout)

    def say(self, message: str) -> SayResult:
        if not isinstance(message, str) or not message.strip():
            raise ValueError("message must be a non-empty string")
        if len(message) > 256:
            raise ValueError("message must contain at most 256 characters")

        payload = self._post("/v1/chat/messages", {"message": message})
        returned_message = _require_string(payload, "message")
        recipients = _require_integer(payload, "recipients")
        if recipients < 0:
            raise ProtocolError("response field 'recipients' must not be negative")
        return SayResult(message=returned_message, recipients=recipients)

    def get_player_position(self, username: str) -> PlayerPosition:
        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")

        payload = self._get(
            f"/v1/players/{quote(username, safe='')}/position",
        )
        return PlayerPosition(
            username=_require_string(payload, "username"),
            uuid=_require_string(payload, "uuid"),
            dimension=_require_string(payload, "dimension"),
            x=_require_number(payload, "x"),
            y=_require_number(payload, "y"),
            z=_require_number(payload, "z"),
            yaw=_require_number(payload, "yaw"),
            pitch=_require_number(payload, "pitch"),
        )

    def get_player(self, username: str) -> Player:
        """Resolve a connected player and bind its operations to this client."""

        position = self.get_player_position(username)
        return Player(position.username, position.uuid, self)

    def get_player_targeted_block(self, username: str) -> Optional[TargetedBlock]:
        """Read a block within interaction reach using the server's latest view."""

        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")
        payload = self._get(f"/v1/players/{quote(username, safe='')}/targeted-block")
        return _targeted_block(payload)

    def attack_player(self, username: str) -> PlayerAttackResult:
        """Perform one block/entity attack or a miss; cancel API mining first."""

        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")
        payload = self._post(f"/v1/players/{quote(username, safe='')}/attack", {})
        swung, broken = payload.get("swung"), payload.get("broken")
        if not isinstance(swung, bool) or not isinstance(broken, bool):
            raise ProtocolError("response fields 'swung' and 'broken' must be booleans")
        target = _targeted_block(payload)
        if broken and target is None:
            raise ProtocolError("a broken block must have a target")
        entity_value = payload.get("entity")
        entity = None if entity_value is None else _entity_snapshot(entity_value)
        if target is not None and entity is not None:
            raise ProtocolError("an attack cannot target both a block and an entity")
        return PlayerAttackResult(swung, target, broken, entity)

    def start_player_mining(self, username: str) -> PlayerMiningResult:
        """Enable held mining, including when no block is currently targeted."""

        return self._player_mining(username, "start")

    def stop_player_mining(self, username: str) -> PlayerMiningResult:
        """Stop API mining, including an already inactive session."""

        return self._player_mining(username, "stop")

    def _player_mining(self, username: str, action: str) -> PlayerMiningResult:
        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")
        payload = self._post(f"/v1/players/{quote(username, safe='')}/mining/{action}", {})
        active, broken = payload.get("active"), payload.get("broken")
        if not isinstance(active, bool) or not isinstance(broken, bool):
            raise ProtocolError("response fields 'active' and 'broken' must be booleans")
        target = _targeted_block(payload)
        if broken and target is None:
            raise ProtocolError("a broken block must have a target")
        if action == "stop" and (active or broken):
            raise ProtocolError("stopping mining must return active=false and broken=false")
        return PlayerMiningResult(active, target, broken)

    def set_player_gamemode(self, username: str, mode: GameMode) -> PlayerGameMode:
        """Set a connected player's mode to creative or survival, including a no-op."""

        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")
        if mode not in ("creative", "survival"):
            raise ValueError("mode must be 'creative' or 'survival'")

        payload = self._post(
            f"/v1/players/{quote(username, safe='')}/gamemode",
            {"mode": mode},
        )
        returned_mode = _require_string(payload, "mode")
        if returned_mode not in ("creative", "survival"):
            raise ProtocolError("response field 'mode' must be 'creative' or 'survival'")
        if returned_mode != mode:
            raise ProtocolError("response field 'mode' does not match the requested mode")
        returned_username = _require_string(payload, "username")
        returned_uuid = _require_string(payload, "uuid")
        if not returned_username.strip() or not returned_uuid.strip():
            raise ProtocolError("response fields 'username' and 'uuid' must be non-empty")
        return PlayerGameMode(
            username=returned_username,
            uuid=returned_uuid,
            mode=mode,
        )

    def teleport_player(
        self,
        username: str,
        x: float,
        y: float,
        z: float,
        *,
        dimension: Optional[str] = None,
        yaw: Optional[float] = None,
        pitch: Optional[float] = None,
    ) -> PlayerPosition:
        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise ValueError("username must contain 3 to 16 letters, digits, or underscores")
        for field_name, value in (("x", x), ("y", y), ("z", z)):
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be a number")
            if not isfinite(value):
                raise ValueError(f"{field_name} must be finite")
        if dimension is not None and (
            not isinstance(dimension, str) or not dimension.strip()
        ):
            raise ValueError("dimension must be a non-empty namespaced ID or None")
        for field_name, angle in (("yaw", yaw), ("pitch", pitch)):
            if angle is None:
                continue
            if not isinstance(angle, (int, float)) or isinstance(angle, bool):
                raise TypeError(f"{field_name} must be a number or None")
            if not isfinite(angle):
                raise ValueError(f"{field_name} must be finite")

        body: Dict[str, Any] = {"x": x, "y": y, "z": z}
        if dimension is not None:
            body["dimension"] = dimension
        if yaw is not None:
            body["yaw"] = yaw
        if pitch is not None:
            body["pitch"] = pitch

        payload = self._post(
            f"/v1/players/{quote(username, safe='')}/teleport",
            body,
        )
        return PlayerPosition(
            username=_require_string(payload, "username"),
            uuid=_require_string(payload, "uuid"),
            dimension=_require_string(payload, "dimension"),
            x=_require_number(payload, "x"),
            y=_require_number(payload, "y"),
            z=_require_number(payload, "z"),
            yaw=_require_number(payload, "yaw"),
            pitch=_require_number(payload, "pitch"),
        )

    def get_players(self) -> List[Player]:
        payload = self._get("/v1/players")
        players_value = payload.get("players")
        if not isinstance(players_value, list):
            raise ProtocolError("response field 'players' must be an array")

        players = []
        for index, player_value in enumerate(players_value):
            if not isinstance(player_value, dict):
                raise ProtocolError(f"response field 'players[{index}]' must be an object")
            players.append(
                Player(
                    username=_require_string(player_value, "username"),
                    uuid=_require_string(player_value, "uuid"),
                    _client=self,
                )
            )
        return players

    def watch_chat(
        self,
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> "ChatEventStream":
        """Open a resumable stream of player chat messages."""

        return ChatEventStream(
            base_url=self._base_url,
            auth_token=self._auth_token,
            timeout=self._timeout,
            last_event_id=last_event_id,
            reconnect=reconnect,
        )

    @overload
    def watch_event(
        self,
        event_type: Literal["chat.message"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[ChatEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Literal["player.death"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[PlayerDeathEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Literal["player.swing"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[PlayerSwingEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Literal["block.attack"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[BlockAttackEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Literal["block.use"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[BlockUseEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Literal["item.use"],
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[ItemUseEvent]: ...

    @overload
    def watch_event(
        self,
        event_type: Optional[EventType] = None,
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[Event]: ...

    def watch_event(
        self,
        event_type: Optional[EventType] = None,
        *,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventStream[Event]:
        """Open a resumable event stream, optionally filtered by event type."""

        return EventStream(
            base_url=self._base_url,
            auth_token=self._auth_token,
            timeout=self._timeout,
            last_event_id=last_event_id,
            reconnect=reconnect,
            event_type=event_type,
        )

    @overload
    def subscribe_event(
        self,
        event_type: Literal["chat.message"],
        *,
        callback: Callable[[ChatEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: Literal["player.death"],
        *,
        callback: Callable[[PlayerDeathEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: Literal["player.swing"],
        *,
        callback: Callable[[PlayerSwingEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: Literal["block.attack"],
        *,
        callback: Callable[[BlockAttackEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: Literal["block.use"],
        *,
        callback: Callable[[BlockUseEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: Literal["item.use"],
        *,
        callback: Callable[[ItemUseEvent], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    @overload
    def subscribe_event(
        self,
        event_type: None = None,
        *,
        callback: Callable[[Event], None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription: ...

    def subscribe_event(
        self,
        event_type: Optional[EventType] = None,
        *,
        callback: Callable[..., None],
        on_error: Optional[Callable[[Exception], None]] = None,
        last_event_id: Optional[str] = None,
        reconnect: bool = True,
    ) -> EventSubscription:
        """Start a background callback receiver after confirming stream.ready."""

        return EventSubscription(
            self.watch_event(event_type, last_event_id=last_event_id, reconnect=reconnect),
            callback=callback,
            on_error=on_error,
        )

    def get_time(self, *, clock: str = "minecraft:overworld") -> TimeResult:
        """Read a world clock's total elapsed ticks."""

        _validate_clock(clock)
        return _time_result(self._get("/v1/world/time?" + urlencode({"clock": clock})))

    def set_time(
        self,
        time: Union[int, str],
        *,
        clock: str = "minecraft:overworld",
    ) -> TimeResult:
        """Set total ticks, or advance to a time marker such as 'day'."""

        _validate_clock(clock)
        if isinstance(time, str):
            if not time.strip():
                raise ValueError("time must be a non-empty time marker ID")
        else:
            _validate_time_ticks(time, "time")
            if time < 0:
                raise ValueError("time must not be negative")
        return _time_result(self._post("/v1/world/time", {"clock": clock, "time": time}))

    def add_time(self, ticks: int, *, clock: str = "minecraft:overworld") -> TimeResult:
        """Add signed ticks to a world clock; Minecraft clamps the result at zero."""

        _validate_clock(clock)
        _validate_time_ticks(ticks, "ticks")
        return _time_result(
            self._post("/v1/world/time/add", {"clock": clock, "ticks": ticks})
        )

    def set_block(
        self,
        x: int,
        y: int,
        z: int,
        block: str,
        *,
        dimension: str = "minecraft:overworld",
    ) -> SetBlockResult:
        for field_name, value in (("x", x), ("y", y), ("z", z)):
            if not isinstance(value, int) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be an integer")
            if not -(2**31) <= value < 2**31:
                raise ValueError(f"{field_name} must fit in a signed 32-bit integer")
        if not isinstance(block, str) or not block.strip():
            raise ValueError("block must be a non-empty namespaced ID")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")

        payload = self._post(
            "/v1/world/blocks",
            {
                "dimension": dimension,
                "x": x,
                "y": y,
                "z": z,
                "block": block,
            },
        )
        changed = payload.get("changed")
        if not isinstance(changed, bool):
            raise ProtocolError("response field 'changed' must be a boolean")

        return SetBlockResult(
            changed=changed,
            dimension=_require_string(payload, "dimension"),
            x=_require_integer(payload, "x"),
            y=_require_integer(payload, "y"),
            z=_require_integer(payload, "z"),
            block=_require_string(payload, "block"),
        )

    def get_block(
        self,
        x: int,
        y: int,
        z: int,
        *,
        dimension: str = "minecraft:overworld",
    ) -> Block:
        for field_name, value in (("x", x), ("y", y), ("z", z)):
            if not isinstance(value, int) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be an integer")
            if not -(2**31) <= value < 2**31:
                raise ValueError(f"{field_name} must fit in a signed 32-bit integer")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")

        query = urlencode(
            {
                "dimension": dimension,
                "x": x,
                "y": y,
                "z": z,
            }
        )
        payload = self._get(f"/v1/world/blocks?{query}")
        return Block(
            dimension=_require_string(payload, "dimension"),
            x=_require_integer(payload, "x"),
            y=_require_integer(payload, "y"),
            z=_require_integer(payload, "z"),
            block=_require_string(payload, "block"),
        )

    def fill(
        self,
        x1: int,
        y1: int,
        z1: int,
        x2: int,
        y2: int,
        z2: int,
        block: str,
        *,
        dimension: str = "minecraft:overworld",
    ) -> FillResult:
        coordinates = (
            ("x1", x1),
            ("y1", y1),
            ("z1", z1),
            ("x2", x2),
            ("y2", y2),
            ("z2", z2),
        )
        for field_name, value in coordinates:
            if not isinstance(value, int) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be an integer")
            if not -(2**31) <= value < 2**31:
                raise ValueError(
                    f"{field_name} must fit in a signed 32-bit integer"
                )
        if not isinstance(block, str) or not block.strip():
            raise ValueError("block must be a non-empty namespaced ID")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")

        volume = (
            (abs(x2 - x1) + 1)
            * (abs(y2 - y1) + 1)
            * (abs(z2 - z1) + 1)
        )
        if volume > _MAX_FILL_BLOCKS:
            raise ValueError(
                f"fill region must contain at most {_MAX_FILL_BLOCKS} blocks"
            )

        payload = self._post(
            "/v1/world/blocks/fill",
            {
                "dimension": dimension,
                "x1": x1,
                "y1": y1,
                "z1": z1,
                "x2": x2,
                "y2": y2,
                "z2": z2,
                "block": block,
            },
        )
        changed_blocks = _require_integer(payload, "changedBlocks")
        if not 0 <= changed_blocks <= volume:
            raise ProtocolError(
                "response field 'changedBlocks' must be between zero and the fill volume"
            )

        return FillResult(
            changed_blocks=changed_blocks,
            dimension=_require_string(payload, "dimension"),
            x1=_require_integer(payload, "x1"),
            y1=_require_integer(payload, "y1"),
            z1=_require_integer(payload, "z1"),
            x2=_require_integer(payload, "x2"),
            y2=_require_integer(payload, "y2"),
            z2=_require_integer(payload, "z2"),
            block=_require_string(payload, "block"),
        )

    def summon(
        self,
        entity: str,
        x: float,
        y: float,
        z: float,
        *,
        nbt: Optional[Dict[str, Any]] = None,
        dimension: str = "minecraft:overworld",
    ) -> SummonResult:
        if not isinstance(entity, str) or not entity.strip():
            raise ValueError("entity must be a non-empty namespaced ID")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")
        for field_name, value in (("x", x), ("y", y), ("z", z)):
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be a number")
            if not isfinite(value):
                raise ValueError(f"{field_name} must be finite")
        if nbt is not None:
            if not isinstance(nbt, dict):
                raise TypeError("nbt must be a dict or None")
            _validate_nbt_value(nbt, "nbt", depth=0)

        body: Dict[str, Any] = {
            "dimension": dimension,
            "entity": entity,
            "x": x,
            "y": y,
            "z": z,
        }
        if nbt is not None:
            body["nbt"] = nbt

        payload = self._post("/v1/world/entities", body)
        return SummonResult(
            uuid=_require_string(payload, "uuid"),
            entity=_require_string(payload, "entity"),
            dimension=_require_string(payload, "dimension"),
            x=_require_number(payload, "x"),
            y=_require_number(payload, "y"),
            z=_require_number(payload, "z"),
        )

    def get_entities(
        self,
        entity: Optional[str] = None,
        *,
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
        radius: Optional[float] = None,
        dimension: str = "minecraft:overworld",
        sort: Literal["arbitrary", "nearest", "furthest"] = "arbitrary",
        limit: Optional[int] = None,
    ) -> List[Entity]:
        """List loaded, alive entities after filtering, sorting, and limiting.

        Coordinates must be supplied together, and are required for a radius
        or distance sorting. No chunks are loaded. Health is None for entities
        without health, such as dropped items.
        """

        if entity is not None and (
            not isinstance(entity, str) or not entity.strip()
        ):
            raise ValueError("entity must be a non-empty namespaced ID or None")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")
        if not isinstance(sort, str) or sort not in ("arbitrary", "nearest", "furthest"):
            raise ValueError("sort must be arbitrary, nearest, or furthest")
        if limit is not None:
            if not isinstance(limit, int) or isinstance(limit, bool):
                raise TypeError("limit must be an integer or None")
            if not 1 <= limit <= 2**31 - 1:
                raise ValueError("limit must be between 1 and 2147483647")
        coordinates = {"x": x, "y": y, "z": z}
        has_center = any(value is not None for value in coordinates.values())
        if has_center and any(value is None for value in coordinates.values()):
            raise ValueError("x, y, and z must be supplied together")
        if not has_center and (radius is not None or sort != "arbitrary"):
            raise ValueError("x, y, and z are required for radius or distance sorting")
        for field_name, value in {**coordinates, "radius": radius}.items():
            if value is None:
                continue
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise TypeError(f"{field_name} must be a number or None")
            try:
                finite = isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise ValueError(f"{field_name} must be finite")
        if radius is not None and radius < 0:
            raise ValueError("radius must not be negative")

        parameters: Dict[str, Any] = {"dimension": dimension}
        if entity is not None:
            parameters["entity"] = entity
        if has_center:
            parameters.update(coordinates)
        if radius is not None:
            parameters["radius"] = radius
        parameters["sort"] = sort
        if limit is not None:
            parameters["limit"] = limit
        payload = self._get("/v1/world/entities?" + urlencode(parameters))
        values = payload.get("entities")
        if not isinstance(values, list):
            raise ProtocolError("response field 'entities' must be an array")
        entities = []
        for index, value in enumerate(values):
            if not isinstance(value, dict):
                raise ProtocolError(f"response field 'entities[{index}]' must be an object")
            entities.append(_entity_snapshot(value))
        return entities

    def kill_entity(
        self,
        uuid: str,
        *,
        dimension: str = "minecraft:overworld",
    ) -> KillResult:
        """Kill one loaded entity by UUID; absent or dead entities report zero."""

        if not isinstance(uuid, str) or not _UUID.fullmatch(uuid):
            raise ValueError("uuid must be a UUID in hyphenated form")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")
        return _kill_result(
            self._post(
                f"/v1/world/entities/{quote(uuid, safe='')}/kill",
                {"dimension": dimension},
            )
        )

    def kill_entities(
        self,
        entity: str,
        *,
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
        radius: Optional[float] = None,
        dimension: str = "minecraft:overworld",
    ) -> KillResult:
        """Kill loaded entities of a type, optionally within an inclusive sphere.

        Supply x, y, z, and a non-negative radius together, or omit all four to
        select every loaded entity of the given type in the dimension.
        """

        if not isinstance(entity, str) or not entity.strip():
            raise ValueError("entity must be a non-empty namespaced ID")
        if not isinstance(dimension, str) or not dimension.strip():
            raise ValueError("dimension must be a non-empty namespaced ID")
        body: Dict[str, Any] = {"dimension": dimension, "entity": entity}
        region = {"x": x, "y": y, "z": z, "radius": radius}
        if any(value is not None for value in region.values()):
            if any(value is None for value in region.values()):
                raise ValueError("x, y, z, and radius must be supplied together")
            for field_name, value in region.items():
                if not isinstance(value, (int, float)) or isinstance(value, bool):
                    raise TypeError(f"{field_name} must be a number")
                try:
                    finite = isfinite(value)
                except OverflowError:
                    finite = False
                if not finite:
                    raise ValueError(f"{field_name} must be finite")
            if radius is not None and radius < 0:
                raise ValueError("radius must not be negative")
            body.update(region)
        return _kill_result(self._post("/v1/world/entities/kill", body))

    def _get(self, path: str) -> Dict[str, Any]:
        return self._request("GET", path)

    def _post(self, path: str, body: Mapping[str, Any]) -> Dict[str, Any]:
        return self._request("POST", path, body)

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[Mapping[str, Any]] = None,
    ) -> Dict[str, Any]:
        data = None
        headers = {
            "Accept": "application/json",
            "User-Agent": "boxloom-python/0.1.0a8",
        }
        if self._auth_token is not None:
            headers["Authorization"] = f"Bearer {self._auth_token}"
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = Request(
            self._base_url + path,
            data=data,
            headers=headers,
            method=method,
        )

        try:
            with urlopen(request, timeout=self._timeout) as response:
                return _decode_object(response.read())
        except HTTPError as error:
            raise _api_error(error) from None
        except (URLError, socket.timeout, TimeoutError, OSError) as error:
            raise _connection_error(error) from None


def _kill_result(payload: Mapping[str, Any]) -> KillResult:
    killed_entities = _require_integer(payload, "killedEntities")
    if killed_entities < 0:
        raise ProtocolError("response field 'killedEntities' must not be negative")
    return KillResult(
        dimension=_require_string(payload, "dimension"),
        killed_entities=killed_entities,
    )


def _validate_clock(clock: str) -> None:
    if not isinstance(clock, str) or not clock.strip():
        raise ValueError("clock must be a non-empty world clock ID")


def _validate_time_ticks(ticks: int, field: str) -> None:
    if not isinstance(ticks, int) or isinstance(ticks, bool):
        raise TypeError(f"{field} must be an integer number of ticks")
    if not -(2**31) <= ticks < 2**31:
        raise ValueError(f"{field} must fit in a signed 32-bit integer")


def _time_result(payload: Mapping[str, Any]) -> TimeResult:
    ticks = _require_integer(payload, "ticks")
    if not 0 <= ticks < 2**63:
        raise ProtocolError("response field 'ticks' must be a non-negative 64-bit integer")
    return TimeResult(clock=_require_string(payload, "clock"), ticks=ticks)


def _validate_base_url(base_url: str) -> str:
    if not isinstance(base_url, str) or not base_url.strip():
        raise ConfigurationError("base_url must be a non-empty HTTP URL")
    value = base_url.strip().rstrip("/")
    parsed = urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ConfigurationError("base_url must be an absolute HTTP or HTTPS URL")
    if parsed.username is not None or parsed.password is not None:
        raise ConfigurationError("base_url must not contain credentials")
    if parsed.query or parsed.fragment:
        raise ConfigurationError("base_url must not contain a query or fragment")
    return value


def _validate_nbt_value(value: Any, path: str, depth: int) -> None:
    if depth > 128:
        raise ValueError("nbt exceeds the maximum nesting depth of 128")
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                raise TypeError(f"{path} keys must be strings")
            _validate_nbt_value(child, f"{path}.{key}", depth + 1)
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            _validate_nbt_value(child, f"{path}[{index}]", depth + 1)
        return
    if isinstance(value, (str, bool)):
        return
    if isinstance(value, int):
        if not -(2**63) <= value < 2**63:
            raise ValueError(f"{path} must fit in a signed 64-bit integer")
        return
    if isinstance(value, float):
        if not isfinite(value):
            raise ValueError(f"{path} must be finite")
        return
    raise TypeError(
        f"{path} must contain only dict, list, string, boolean, integer, or float values"
    )


def _targeted_block(payload: Mapping[str, Any]) -> Optional[TargetedBlock]:
    if "target" not in payload:
        raise ProtocolError("response field 'target' must be an object or null")
    value = payload["target"]
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ProtocolError("response field 'target' must be an object or null")
    face = _require_string(value, "face")
    if face not in ("down", "up", "north", "south", "west", "east"):
        raise ProtocolError("response field 'face' is not a block face")
    distance = _require_number(value, "distance")
    if not isfinite(distance) or distance < 0:
        raise ProtocolError("response field 'distance' must be finite and non-negative")
    return TargetedBlock(
        dimension=_require_string(value, "dimension"),
        x=_require_integer(value, "x"), y=_require_integer(value, "y"), z=_require_integer(value, "z"),
        block=_require_string(value, "block"),
        face=cast(BlockFace, face),
        distance=distance,
    )


def _entity_snapshot(value: Any) -> Entity:
    if not isinstance(value, dict):
        raise ProtocolError("response entity must be an object")
    if "health" not in value:
        raise ProtocolError("response field 'health' must be a number or null")
    return Entity(
        uuid=_require_string(value, "uuid"), entity=_require_string(value, "entity"),
        dimension=_require_string(value, "dimension"),
        x=_require_number(value, "x"), y=_require_number(value, "y"), z=_require_number(value, "z"),
        yaw=_require_number(value, "yaw"), pitch=_require_number(value, "pitch"),
        name=_require_string(value, "name"),
        health=None if value["health"] is None else _require_number(value, "health"),
    )
