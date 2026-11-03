"""Public API for the boxloom Python SDK."""

import os
import threading
from typing import Any, Dict, List, Literal, Optional, Union, overload

from . import blocks, entities
from ._client import BoxloomClient
from .errors import (
    ApiError,
    BoxloomError,
    ConfigurationError,
    ConnectionError,
    ProtocolError,
)
from .events import (
    ChatEvent,
    ChatEventStream,
    Event,
    EventCursorExpiredError,
    EventStream,
    EventStreamError,
    EventType,
    PlayerDeathEvent,
)
from .models import (
    Entity,
    FillResult,
    GetBlockResult,
    KillResult,
    Player,
    PlayerPosition,
    SayResult,
    SetBlockResult,
    SummonResult,
    TimeResult,
)

__all__ = [
    "ApiError",
    "BoxloomClient",
    "BoxloomError",
    "ChatEvent",
    "ChatEventStream",
    "ConfigurationError",
    "ConnectionError",
    "Entity",
    "Event",
    "EventCursorExpiredError",
    "EventStream",
    "EventStreamError",
    "EventType",
    "FillResult",
    "GetBlockResult",
    "KillResult",
    "Player",
    "PlayerDeathEvent",
    "PlayerPosition",
    "ProtocolError",
    "SayResult",
    "SetBlockResult",
    "SummonResult",
    "TimeResult",
    "add_time",
    "blocks",
    "entities",
    "fill",
    "get_block",
    "get_entities",
    "get_player_position",
    "get_players",
    "get_time",
    "init",
    "kill_entities",
    "kill_entity",
    "say",
    "set_block",
    "set_time",
    "summon",
    "teleport_player",
    "watch_chat",
    "watch_event",
]

__version__ = "0.1.0a6"

_DEFAULT_BASE_URL = "http://127.0.0.1:28886"
_DEFAULT_TIMEOUT = 10.0
_client: Optional[BoxloomClient] = None
_client_lock = threading.RLock()


def init(
    *,
    auth_token: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout: Optional[float] = None,
) -> None:
    """Configure the process-wide client used by top-level API functions."""

    resolved_token = (
        auth_token
        if auth_token is not None
        else (os.getenv("BOXLOOM_AUTH_TOKEN") or None)
    )
    resolved_url = base_url if base_url is not None else os.getenv(
        "BOXLOOM_BASE_URL", _DEFAULT_BASE_URL
    )
    resolved_timeout = timeout if timeout is not None else _timeout_from_environment()

    client = BoxloomClient(
        base_url=resolved_url,
        auth_token=resolved_token,
        timeout=resolved_timeout,
    )
    global _client
    with _client_lock:
        _client = client


def say(message: str) -> SayResult:
    """Broadcast a system message to all connected players."""

    return _default_client().say(message)


def get_player_position(username: str) -> PlayerPosition:
    """Read a connected player's position and look direction."""

    return _default_client().get_player_position(username)


def get_players() -> List[Player]:
    """List players currently connected to the Minecraft server."""

    return _default_client().get_players()


def teleport_player(
    username: str,
    x: float,
    y: float,
    z: float,
    *,
    dimension: Optional[str] = None,
    yaw: Optional[float] = None,
    pitch: Optional[float] = None,
) -> PlayerPosition:
    """Teleport a connected player to absolute coordinates."""

    return _default_client().teleport_player(
        username,
        x,
        y,
        z,
        dimension=dimension,
        yaw=yaw,
        pitch=pitch,
    )


def watch_chat(
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> ChatEventStream:
    """Open a resumable stream of player chat messages."""

    return _default_client().watch_chat(
        last_event_id=last_event_id,
        reconnect=reconnect,
    )


@overload
def watch_event(
    event_type: Literal["chat.message"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[ChatEvent]: ...


@overload
def watch_event(
    event_type: Literal["player.death"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[PlayerDeathEvent]: ...


@overload
def watch_event(
    event_type: Optional[EventType] = None,
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[Event]: ...


def watch_event(
    event_type: Optional[EventType] = None,
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[Event]:
    """Open a resumable event stream, optionally filtered by event type."""

    return _default_client().watch_event(
        event_type,
        last_event_id=last_event_id,
        reconnect=reconnect,
    )


def get_time(*, clock: str = "minecraft:overworld") -> TimeResult:
    """Read a world clock's total elapsed ticks."""

    return _default_client().get_time(clock=clock)


def set_time(
    time: Union[int, str],
    *,
    clock: str = "minecraft:overworld",
) -> TimeResult:
    """Set total ticks, or advance to a time marker such as 'day'."""

    return _default_client().set_time(time, clock=clock)


def add_time(ticks: int, *, clock: str = "minecraft:overworld") -> TimeResult:
    """Add signed ticks to a world clock; Minecraft clamps the result at zero."""

    return _default_client().add_time(ticks, clock=clock)


def set_block(
    x: int,
    y: int,
    z: int,
    block: str,
    *,
    dimension: str = "minecraft:overworld",
) -> SetBlockResult:
    """Set a block in a loaded Minecraft dimension."""

    return _default_client().set_block(x, y, z, block, dimension=dimension)


def get_block(
    x: int,
    y: int,
    z: int,
    *,
    dimension: str = "minecraft:overworld",
) -> GetBlockResult:
    """Return information about a block in a loaded Minecraft dimension."""

    return _default_client().get_block(x, y, z, dimension=dimension)


def fill(
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
    """Fill an inclusive block region in a loaded Minecraft dimension."""

    return _default_client().fill(
        x1,
        y1,
        z1,
        x2,
        y2,
        z2,
        block,
        dimension=dimension,
    )


def summon(
    entity: str,
    x: float,
    y: float,
    z: float,
    *,
    nbt: Optional[Dict[str, Any]] = None,
    dimension: str = "minecraft:overworld",
) -> SummonResult:
    """Summon an entity in a loaded Minecraft dimension."""

    return _default_client().summon(
        entity,
        x,
        y,
        z,
        nbt=nbt,
        dimension=dimension,
    )


def get_entities(
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
    """List loaded, alive entities, optionally filtered, sorted, and limited.

    Supply x, y, and z together for radius filtering or distance sorting.
    """

    return _default_client().get_entities(
        entity, x=x, y=y, z=z, radius=radius, dimension=dimension,
        sort=sort, limit=limit,
    )


def kill_entity(
    uuid: str,
    *,
    dimension: str = "minecraft:overworld",
) -> KillResult:
    """Kill one loaded entity by UUID; absent or dead entities report zero."""

    return _default_client().kill_entity(uuid, dimension=dimension)


def kill_entities(
    entity: str,
    *,
    x: Optional[float] = None,
    y: Optional[float] = None,
    z: Optional[float] = None,
    radius: Optional[float] = None,
    dimension: str = "minecraft:overworld",
) -> KillResult:
    """Kill loaded entities of a type, optionally within an inclusive sphere.

    Supply x, y, z, and radius together, or omit all four for the whole dimension.
    """

    return _default_client().kill_entities(
        entity, x=x, y=y, z=z, radius=radius, dimension=dimension,
    )


def _default_client() -> BoxloomClient:
    global _client
    with _client_lock:
        if _client is None:
            init()
        assert _client is not None
        return _client


def _timeout_from_environment() -> float:
    value = os.getenv("BOXLOOM_TIMEOUT_SECONDS")
    if value is None:
        return _DEFAULT_TIMEOUT
    try:
        return float(value)
    except ValueError:
        raise ConfigurationError("BOXLOOM_TIMEOUT_SECONDS must be a number") from None


def _reset_default_client_for_testing() -> None:
    global _client
    with _client_lock:
        _client = None
