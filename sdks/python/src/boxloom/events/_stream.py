import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Iterator, List, Literal, Mapping, Optional, TypeVar, Union, cast
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .._transport import (
    _api_error,
    _connection_error,
    _decode_object,
    _require_integer,
    _require_number,
    _require_string,
)
from ..errors import (
    ApiError,
    ConfigurationError,
    ConnectionError,
    EventCursorExpiredError,
    EventStreamError,
    ProtocolError,
)
from ..models import (
    BlockAttackEvent,
    BlockUseEvent,
    ChatEvent,
    Event,
    EventType,
    ItemUseEvent,
    Player,
    PlayerDeathEvent,
    PlayerSwingEvent,
    _EVENT_CLASSES,
    _EVENT_TYPES,
)


@dataclass(frozen=True)
class _SseFrame:
    event_name: str
    event_id: Optional[str]
    data: Optional[str]
    retry_milliseconds: Optional[int]


@dataclass(frozen=True)
class _StreamReadyEvent:
    cursor: str
    event_name: Literal["stream.ready"] = field(
        default="stream.ready",
        init=False,
    )


@dataclass(frozen=True)
class _StreamResetEvent:
    code: str
    message: str
    event_name: Literal["stream.reset"] = field(
        default="stream.reset",
        init=False,
    )


@dataclass(frozen=True)
class _ErrorEvent:
    code: str
    message: str
    event_name: Literal["error"] = field(
        default="error",
        init=False,
    )


_BoxloomEvent = Union[
    _StreamReadyEvent,
    Event,
    _StreamResetEvent,
    _ErrorEvent,
]


_EventT = TypeVar("_EventT", bound=Event, covariant=True)


class EventStream(Iterator[_EventT]):
    """Context-managed iterator over a resumable server-sent event stream."""

    def __init__(
        self,
        *,
        base_url: str,
        auth_token: Optional[str],
        timeout: float,
        last_event_id: Optional[str],
        reconnect: bool,
        event_type: Optional[EventType] = None,
    ) -> None:
        if event_type is not None and event_type not in _EVENT_TYPES:
            raise ValueError(f"event_type must be one of {_EVENT_TYPES!r}, or None")
        if last_event_id is not None and (
            not isinstance(last_event_id, str)
            or not last_event_id.strip()
            or "\r" in last_event_id
            or "\n" in last_event_id
        ):
            raise ConfigurationError(
                "last_event_id must be a non-empty single-line string or None"
            )
        if not isinstance(reconnect, bool):
            raise ConfigurationError("reconnect must be a boolean")

        self._base_url = base_url
        self._auth_token = auth_token
        self._timeout = timeout
        self._last_event_id = last_event_id
        self._reconnect = reconnect
        self._event_name = event_type
        self._retry_seconds = 1.0
        self._response: Any = None
        self._closed = threading.Event()

    @property
    def last_event_id(self) -> Optional[str]:
        """Return the most recently received stream cursor."""

        return self._last_event_id

    def __enter__(self) -> "EventStream[_EventT]":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.close()

    def __iter__(self) -> "EventStream[_EventT]":
        return self

    def __next__(self) -> _EventT:
        while True:
            event = self._next_event()
            if isinstance(event, _EVENT_CLASSES) and self._accepts(event):
                # watch_event overloads tie the filter to the yielded type.
                return cast(_EventT, event)

    def _accepts(self, event: Event) -> bool:
        return self._event_name is None or self._event_name == event.type

    def _open(self) -> str:
        """Open once and consume stream.ready before a subscription is returned."""

        try:
            self._connect()
            deadline = time.monotonic() + self._timeout
            while not self._closed.is_set():
                if time.monotonic() >= deadline:
                    raise ConnectionError("boxloom event stream readiness timed out")
                event = self._receive()
                if isinstance(event, _StreamReadyEvent):
                    return event.cursor
                if isinstance(event, _EVENT_CLASSES):
                    raise ProtocolError("event stream must send stream.ready before events")
            raise ConnectionError("boxloom event stream closed before it was ready")
        except (URLError, socket.timeout, TimeoutError, OSError) as error:
            self._disconnect()
            raise _connection_error(error) from None
        except BaseException:
            self._disconnect()
            raise

    def _next_event(self) -> Optional[_BoxloomEvent]:
        while not self._closed.is_set():
            try:
                if self._response is None:
                    self._connect()
                return self._receive()
            except ApiError as error:
                self._disconnect()
                if self._closed.is_set():
                    raise StopIteration
                if not self._reconnect or error.status < 500:
                    raise
                self._wait_to_reconnect()
                continue
            except ConnectionError:
                self._disconnect()
                if self._closed.is_set():
                    raise StopIteration
                if not self._reconnect:
                    raise
                self._wait_to_reconnect()
                continue
            except (ProtocolError, EventStreamError):
                self._disconnect()
                raise
            except (URLError, socket.timeout, TimeoutError, OSError) as error:
                self._disconnect()
                if self._closed.is_set():
                    raise StopIteration
                if not self._reconnect:
                    raise _connection_error(error) from None
                self._wait_to_reconnect()
                continue

        raise StopIteration

    def _receive(self) -> Optional[_BoxloomEvent]:
        frame = self._read_frame()
        if self._closed.is_set():
            raise StopIteration
        if frame is None:
            raise ConnectionError("boxloom event stream disconnected")
        if frame.retry_milliseconds is not None:
            self._retry_seconds = max(
                0.001,
                min(frame.retry_milliseconds / 1000.0, 30.0),
            )
        event = _decode_boxloom_event(frame)
        if isinstance(event, _StreamResetEvent):
            if event.code == "EVENT_CURSOR_EXPIRED":
                raise EventCursorExpiredError(event.message)
            raise EventStreamError(event.code, event.message)
        if isinstance(event, _ErrorEvent):
            raise EventStreamError(event.code, event.message)
        if frame.event_id is not None:
            self._last_event_id = frame.event_id
        return event

    def _request_stop(self) -> None:
        # Only the receiving thread closes the response, avoiding cross-thread
        # close/read deadlocks. Reads remain bounded by the configured timeout.
        self._closed.set()

    def close(self) -> None:
        """Close the active HTTP response and stop reconnecting."""

        self._request_stop()
        self._disconnect()

    def _connect(self) -> None:
        headers = {
            "Accept": "text/event-stream",
            "Cache-Control": "no-cache",
            "User-Agent": "boxloom-python/0.1.0a8",
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

    def _read_frame(self) -> Optional[_SseFrame]:
        event_name = "message"
        event_id = None
        data_lines: List[str] = []
        retry_milliseconds = None

        while not self._closed.is_set():
            source = self._response.readline()
            if source == b"":
                return None
            try:
                line = source.decode("utf-8")
            except UnicodeDecodeError:
                raise ProtocolError("the event stream must contain valid UTF-8") from None
            line = line.rstrip("\r\n")

            if not line:
                return _SseFrame(
                    event_name=event_name,
                    event_id=event_id,
                    data="\n".join(data_lines) if data_lines else None,
                    retry_milliseconds=retry_milliseconds,
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

        return None


class ChatEventStream(EventStream[ChatEvent]):
    """Context-managed iterator of player chat messages."""

    def __init__(
        self,
        *,
        base_url: str,
        auth_token: Optional[str],
        timeout: float,
        last_event_id: Optional[str],
        reconnect: bool,
    ) -> None:
        super().__init__(
            base_url=base_url,
            auth_token=auth_token,
            timeout=timeout,
            last_event_id=last_event_id,
            reconnect=reconnect,
            event_type="chat.message",
        )


def _decode_boxloom_event(frame: _SseFrame) -> Optional[_BoxloomEvent]:
    if frame.data is None:
        return None
    if frame.event_name not in {
        "stream.ready",
        "chat.message",
        "player.death",
        "player.swing",
        "block.attack",
        "block.use",
        "item.use",
        "stream.reset",
        "error",
    }:
        return None

    payload = _decode_object(frame.data.encode("utf-8"))
    payload_type = _require_string(payload, "type")
    if payload_type != frame.event_name:
        raise ProtocolError("event payload type must match the SSE event name")

    if frame.event_name == "stream.ready":
        cursor = _require_string(payload, "cursor")
        if frame.event_id is None or frame.event_id != cursor:
            raise ProtocolError("stream ready cursor must match the SSE event id")
        return _StreamReadyEvent(cursor=cursor)
    if frame.event_name == "chat.message":
        return _decode_chat_event(payload, frame.event_id)
    if frame.event_name == "player.death":
        return _decode_player_death_event(payload, frame.event_id)

    if frame.event_name in {"player.swing", "block.attack", "block.use", "item.use"}:
        return _decode_player_action_event(payload, frame.event_id, frame.event_name)

    code = _require_string(payload, "code")
    message = _require_string(payload, "message")
    if frame.event_name == "stream.reset":
        return _StreamResetEvent(code=code, message=message)
    return _ErrorEvent(code=code, message=message)


def _decode_chat_event(
    payload: Mapping[str, Any],
    event_id: Optional[str],
) -> ChatEvent:
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


def _decode_player_death_event(
    payload: Mapping[str, Any],
    event_id: Optional[str],
) -> PlayerDeathEvent:
    payload_id = _require_string(payload, "id")
    if event_id is None or event_id != payload_id:
        raise ProtocolError("player death event payload id must match the SSE event id")
    player_value = payload.get("player")
    if not isinstance(player_value, dict):
        raise ProtocolError("response field 'player' must be an object")

    return PlayerDeathEvent(
        id=payload_id,
        timestamp=_require_string(payload, "timestamp"),
        message=_require_string(payload, "message"),
        player=Player(
            username=_require_string(player_value, "username"),
            uuid=_require_string(player_value, "uuid"),
        ),
        dimension=_require_string(payload, "dimension"),
        x=_require_number(payload, "x"),
        y=_require_number(payload, "y"),
        z=_require_number(payload, "z"),
    )


_ActionEvent = Union[PlayerSwingEvent, BlockAttackEvent, BlockUseEvent, ItemUseEvent]


def _decode_player_action_event(
    payload: Mapping[str, Any],
    event_id: Optional[str],
    event_name: str,
) -> _ActionEvent:
    payload_id = _require_string(payload, "id")
    if event_id is None or event_id != payload_id:
        raise ProtocolError("player action payload id must match the SSE event id")
    player_value = payload.get("player")
    if not isinstance(player_value, dict):
        raise ProtocolError("response field 'player' must be an object")
    hand = _require_string(payload, "hand")
    if hand not in ("main_hand", "off_hand"):
        raise ProtocolError("response field 'hand' must be 'main_hand' or 'off_hand'")
    if "item" not in payload:
        raise ProtocolError("response field 'item' is required")
    item = None if payload["item"] is None else _require_string(payload, "item")
    common = (
        payload_id,
        _require_string(payload, "timestamp"),
        Player(
            username=_require_string(player_value, "username"),
            uuid=_require_string(player_value, "uuid"),
        ),
        _require_string(payload, "dimension"),
        _require_number(payload, "x"),
        _require_number(payload, "y"),
        _require_number(payload, "z"),
        cast(Literal["main_hand", "off_hand"], hand),
        item,
    )
    if event_name == "player.swing":
        return PlayerSwingEvent(*common)
    if event_name == "item.use":
        return ItemUseEvent(*common)

    face = _require_string(payload, "face")
    if face not in ("down", "up", "north", "south", "west", "east"):
        raise ProtocolError("response field 'face' must be a block face direction")
    block = (
        _require_string(payload, "block"),
        _require_integer(payload, "block_x"),
        _require_integer(payload, "block_y"),
        _require_integer(payload, "block_z"),
        cast(Literal["down", "up", "north", "south", "west", "east"], face),
    )
    if event_name == "block.attack":
        return BlockAttackEvent(*common, *block)
    return BlockUseEvent(*common, *block)
