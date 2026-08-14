"""Public API for the boxloom Python SDK."""

import os
import threading
from typing import Any, Dict, List, Optional

from ._client import BoxloomClient
from .errors import (
    ApiError,
    BoxloomError,
    ConfigurationError,
    ConnectionError,
    ProtocolError,
)
from .models import Player, PlayerPosition, SayResult, SetBlockResult, SummonResult

__all__ = [
    "ApiError",
    "BoxloomClient",
    "BoxloomError",
    "ConfigurationError",
    "ConnectionError",
    "Player",
    "PlayerPosition",
    "ProtocolError",
    "SayResult",
    "SetBlockResult",
    "SummonResult",
    "get_player_position",
    "get_players",
    "init",
    "say",
    "set_block",
    "setblock",
    "summon",
]

__version__ = "0.1.0a1"

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


def setblock(
    x: int,
    y: int,
    z: int,
    block: str,
    *,
    dimension: str = "minecraft:overworld",
) -> SetBlockResult:
    """Alias for :func:`set_block`."""

    return set_block(x, y, z, block, dimension=dimension)


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
