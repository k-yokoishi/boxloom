"""Public API for the boxloom Python SDK."""

import os
import threading
from typing import Any, Callable, Dict, List, Literal, Mapping, Optional, Union, overload

from . import blocks, entities, items
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
    EventSubscription,
    EventType,
    PlayerDeathEvent,
    PlayerSwingEvent,
    BlockAttackEvent,
    BlockUseEvent,
    ItemUseEvent,
)
from .models import (
    Block,
    Entity,
    FillResult,
    GetBlockResult,
    GameMode,
    GiveItemResult,
    KillResult,
    Player,
    PlayerGameMode,
    PlayerPosition,
    PlayerAttackResult,
    PlayerUseResult,
    PlayerItemUseResult,
    PlayerMiningResult,
    TargetedBlock,
    SayResult,
    SetBlockResult,
    SummonResult,
    TimeResult,
)

__all__ = [
    "ApiError",
    "Block",
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
    "EventSubscription",
    "EventType",
    "FillResult",
    "GetBlockResult",
    "GameMode",
    "GiveItemResult",
    "KillResult",
    "Player",
    "PlayerGameMode",
    "PlayerDeathEvent",
    "PlayerSwingEvent",
    "BlockAttackEvent",
    "BlockUseEvent",
    "ItemUseEvent",
    "PlayerPosition",
    "PlayerAttackResult",
    "PlayerUseResult",
    "PlayerItemUseResult",
    "PlayerMiningResult",
    "TargetedBlock",
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
    "get_player",
    "get_players",
    "get_time",
    "give_item",
    "init",
    "items",
    "kill_entities",
    "kill_entity",
    "say",
    "set_block",
    "set_player_gamemode",
    "set_time",
    "summon",
    "subscribe_event",
    "teleport_player",
    "watch_chat",
    "watch_event",
]

__version__ = "0.1.0a9"

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


def get_player(username: str) -> Optional[Player]:
    """Resolve a connected player bound to the current client, or None if not connected."""

    return _default_client().get_player(username)


def give_item(username: str, item: str, count: int = 1) -> GiveItemResult:
    """Give an item using vanilla /give stacking and overflow rules."""

    return _default_client().give_item(username, item, count)


def set_player_gamemode(username: str, mode: GameMode) -> PlayerGameMode:
    """Set a connected player's mode to creative or survival."""

    return _default_client().set_player_gamemode(username, mode)


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
    event_type: Literal["player.swing"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[PlayerSwingEvent]: ...


@overload
def watch_event(
    event_type: Literal["block.attack"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[BlockAttackEvent]: ...


@overload
def watch_event(
    event_type: Literal["block.use"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[BlockUseEvent]: ...


@overload
def watch_event(
    event_type: Literal["item.use"],
    *,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventStream[ItemUseEvent]: ...


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


@overload
def subscribe_event(
    event_type: Literal["chat.message"],
    *,
    callback: Callable[[ChatEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: Literal["player.death"],
    *,
    callback: Callable[[PlayerDeathEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: Literal["player.swing"],
    *,
    callback: Callable[[PlayerSwingEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: Literal["block.attack"],
    *,
    callback: Callable[[BlockAttackEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: Literal["block.use"],
    *,
    callback: Callable[[BlockUseEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: Literal["item.use"],
    *,
    callback: Callable[[ItemUseEvent], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


@overload
def subscribe_event(
    event_type: None = None,
    *,
    callback: Callable[[Event], None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription: ...


def subscribe_event(
    event_type: Optional[EventType] = None,
    *,
    callback: Callable[..., None],
    on_error: Optional[Callable[[Exception], None]] = None,
    last_event_id: Optional[str] = None,
    reconnect: bool = True,
) -> EventSubscription:
    """Start a background callback receiver after confirming stream.ready."""

    return _default_client().subscribe_event(
        event_type,
        callback=callback,
        on_error=on_error,
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
    properties: Optional[Mapping[str, str]] = None,
) -> SetBlockResult:
    """Set a block from its default state plus optional string properties."""

    return _default_client().set_block(x, y, z, block, dimension=dimension, properties=properties)


def get_block(
    x: int,
    y: int,
    z: int,
    *,
    dimension: str = "minecraft:overworld",
) -> Block:
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
