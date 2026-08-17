import json
import re
import socket
from typing import Any, Dict, List, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
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
from .events import ChatEventStream
from .models import Player, PlayerPosition, SayResult, SetBlockResult


_USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")


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

    def setblock(
        self,
        x: int,
        y: int,
        z: int,
        block: str,
        *,
        dimension: str = "minecraft:overworld",
    ) -> SetBlockResult:
        """Alias for :meth:`set_block`."""

        return self.set_block(x, y, z, block, dimension=dimension)

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
            "User-Agent": "boxloom-python/0.1.0",
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
