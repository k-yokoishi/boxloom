import json
import re
import socket
import threading
from typing import Any, Dict, Iterator, List, Mapping, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

from .errors import (
    ApiError,
    ConfigurationError,
    ConnectionError,
    EventCursorExpiredError,
    EventStreamError,
    ProtocolError,
)
from .models import ChatEvent, Player, PlayerPosition, SayResult, SetBlockResult


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


class ChatEventStream(Iterator[ChatEvent]):
    """Context-managed iterator over a resumable server-sent event stream."""

    def __init__(
        self,
        *,
        base_url: str,
        auth_token: Optional[str],
        timeout: float,
        last_event_id: Optional[str],
        reconnect: bool,
    ) -> None:
        if last_event_id is not None and (
            not isinstance(last_event_id, str)
            or not last_event_id.strip()
            or "\r" in last_event_id
            or "\n" in last_event_id
        ):
            raise ConfigurationError("last_event_id must be a non-empty single-line string or None")
        if not isinstance(reconnect, bool):
            raise ConfigurationError("reconnect must be a boolean")

        self._base_url = base_url
        self._auth_token = auth_token
        self._timeout = timeout
        self._last_event_id = last_event_id
        self._reconnect = reconnect
        self._retry_seconds = 1.0
        self._response: Any = None
        self._closed = threading.Event()

    @property
    def last_event_id(self) -> Optional[str]:
        """Return the most recently received stream cursor."""

        return self._last_event_id

    def __enter__(self) -> "ChatEventStream":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.close()

    def __iter__(self) -> "ChatEventStream":
        return self

    def __next__(self) -> ChatEvent:
        while not self._closed.is_set():
            try:
                if self._response is None:
                    self._connect()
                frame = self._read_frame()
                if frame is None:
                    raise ConnectionError("boxloom event stream disconnected")
            except ApiError as error:
                self._disconnect()
                if not self._reconnect or error.status < 500:
                    raise
                self._wait_to_reconnect()
                continue
            except ConnectionError:
                self._disconnect()
                if not self._reconnect:
                    raise
                self._wait_to_reconnect()
                continue
            except ProtocolError:
                self._disconnect()
                raise
            except (URLError, socket.timeout, TimeoutError, OSError) as error:
                self._disconnect()
                if not self._reconnect:
                    raise _connection_error(error) from None
                self._wait_to_reconnect()
                continue

            event_name, event_id, data, retry_milliseconds = frame
            if retry_milliseconds is not None:
                self._retry_seconds = max(0.001, min(retry_milliseconds / 1000.0, 30.0))
            if event_id is not None:
                self._last_event_id = event_id
            if data is None:
                continue

            try:
                payload = _decode_object(data.encode("utf-8"))
                if event_name == "chat.message":
                    return _decode_chat_event(payload, event_id)
                if event_name == "stream.reset":
                    code = _require_string(payload, "code")
                    message = _require_string(payload, "message")
                    if code == "EVENT_CURSOR_EXPIRED":
                        raise EventCursorExpiredError(message)
                    raise EventStreamError(code, message)
                if event_name == "error":
                    raise EventStreamError(
                        _require_string(payload, "code"),
                        _require_string(payload, "message"),
                    )
            except (ProtocolError, EventCursorExpiredError, EventStreamError):
                self._disconnect()
                raise

        raise StopIteration

    def close(self) -> None:
        """Close the active HTTP response and stop reconnecting."""

        self._closed.set()
        self._disconnect()

    def _connect(self) -> None:
        headers = {
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
            "User-Agent": "boxloom-python/0.1.0",
        }
        if self._auth_token is not None:
            headers["Authorization"] = f"Bearer {self._auth_token}"
        if self._last_event_id is not None:
            headers["Last-Event-ID"] = self._last_event_id

        request = Request(self._base_url + "/v1/events", headers=headers, method="GET")
        try:
            response = urlopen(request, timeout=self._timeout)
        except HTTPError as error:
            raise _api_error(error) from None
        except (URLError, socket.timeout, TimeoutError, OSError) as error:
            raise _connection_error(error) from None

        content_type = response.headers.get_content_type()
        if content_type != "text/event-stream":
            response.close()
            raise ProtocolError("event stream Content-Type must be text/event-stream")
        self._response = response

    def _disconnect(self) -> None:
        response = self._response
        self._response = None
        if response is not None:
            response.close()

    def _wait_to_reconnect(self) -> None:
        if self._closed.wait(self._retry_seconds):
            raise StopIteration

    def _read_frame(self) -> Optional[Tuple[str, Optional[str], Optional[str], Optional[int]]]:
        event_name = "message"
        event_id = None
        data_lines: List[str] = []
        retry_milliseconds = None

        while True:
            source = self._response.readline()
            if source == b"":
                return None
            try:
                line = source.decode("utf-8")
            except UnicodeDecodeError:
                raise ProtocolError("the event stream must contain valid UTF-8") from None
            line = line.rstrip("\r\n")

            if not line:
                return (
                    event_name,
                    event_id,
                    "\n".join(data_lines) if data_lines else None,
                    retry_milliseconds,
                )
            if line.startswith(":"):
                continue

            field, separator, value = line.partition(":")
            if separator and value.startswith(" "):
                value = value[1:]
            if field == "event":
                event_name = value
            elif field == "data":
                data_lines.append(value)
            elif field == "id" and "\0" not in value:
                event_id = value
            elif field == "retry" and value.isdigit():
                retry_milliseconds = int(value)


def _decode_chat_event(payload: Mapping[str, Any], event_id: Optional[str]) -> ChatEvent:
    payload_id = _require_string(payload, "id")
    if event_id is None or event_id != payload_id:
        raise ProtocolError("chat event payload id must match the SSE event id")
    player_value = payload.get("player")
    if not isinstance(player_value, dict):
        raise ProtocolError("response field 'player' must be an object")

    return ChatEvent(
        id=payload_id,
        timestamp=_require_string(payload, "timestamp"),
        message=_require_string(payload, "message"),
        player=Player(
            username=_require_string(player_value, "username"),
            uuid=_require_string(player_value, "uuid"),
        ),
    )


def _api_error(error: HTTPError) -> ApiError:
    try:
        try:
            payload = _decode_object(error.read())
            error_value = payload.get("error")
            if not isinstance(error_value, dict):
                raise ProtocolError("error response field 'error' must be an object")
            code = _require_string(error_value, "code")
            message = _require_string(error_value, "message")
        except (ProtocolError, OSError):
            code = "HTTP_ERROR"
            message = "The server returned an invalid error response"
        if error.code == 410 and code == "EVENT_CURSOR_EXPIRED":
            return EventCursorExpiredError(message)
        return ApiError(error.code, code, message)
    finally:
        error.close()


def _connection_error(error: BaseException) -> ConnectionError:
    reason = getattr(error, "reason", error)
    if isinstance(reason, (socket.timeout, TimeoutError)):
        detail = "the request timed out"
    else:
        detail = "the server could not be reached"
    return ConnectionError(f"boxloom connection failed: {detail}")


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


def _decode_object(source: bytes) -> Dict[str, Any]:
    try:
        value = json.loads(source.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ProtocolError("the server response must be valid UTF-8 JSON") from None
    if not isinstance(value, dict):
        raise ProtocolError("the server response must be a JSON object")
    return value


def _require_string(value: Mapping[str, Any], field: str) -> str:
    result = value.get(field)
    if not isinstance(result, str):
        raise ProtocolError(f"response field '{field}' must be a string")
    return result


def _require_integer(value: Mapping[str, Any], field: str) -> int:
    result = value.get(field)
    if not isinstance(result, int) or isinstance(result, bool):
        raise ProtocolError(f"response field '{field}' must be an integer")
    return result


def _require_number(value: Mapping[str, Any], field: str) -> float:
    result = value.get(field)
    if not isinstance(result, (int, float)) or isinstance(result, bool):
        raise ProtocolError(f"response field '{field}' must be a number")
    return float(result)
