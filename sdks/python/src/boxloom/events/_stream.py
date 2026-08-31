import socket
import threading
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Iterator, List, Literal, Mapping, Optional, Union
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .._transport import (
    _api_error,
    _connection_error,
    _decode_object,
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
from ..models import ChatEvent, Player

if TYPE_CHECKING:
    from .._client import BoxloomClient


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
class _ChatMessageEvent:
    chat_event: ChatEvent
    event_name: Literal["chat.message"] = field(
        default="chat.message",
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
    _ChatMessageEvent,
    _StreamResetEvent,
    _ErrorEvent,
]


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
        player_client: Optional["BoxloomClient"] = None,
    ) -> None:
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
        self._player_client = player_client
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

            if frame.retry_milliseconds is not None:
                self._retry_seconds = max(
                    0.001,
                    min(frame.retry_milliseconds / 1000.0, 30.0),
                )
            if frame.event_id is not None:
                self._last_event_id = frame.event_id
            try:
                event = _decode_boxloom_event(frame, self._player_client)
                if event is None or event.event_name == "stream.ready":
                    continue
                if event.event_name == "chat.message":
                    return event.chat_event
                if event.event_name == "stream.reset":
                    if event.code == "EVENT_CURSOR_EXPIRED":
                        raise EventCursorExpiredError(event.message)
                    raise EventStreamError(event.code, event.message)
                if event.event_name == "error":
                    raise EventStreamError(event.code, event.message)
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

    def _read_frame(self) -> Optional[_SseFrame]:
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


def _decode_boxloom_event(
    frame: _SseFrame,
    player_client: Optional["BoxloomClient"],
) -> Optional[_BoxloomEvent]:
    if frame.data is None:
        return None
    if frame.event_name not in {
        "stream.ready",
        "chat.message",
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
        return _ChatMessageEvent(
            chat_event=_decode_chat_event(payload, frame.event_id, player_client),
        )

    code = _require_string(payload, "code")
    message = _require_string(payload, "message")
    if frame.event_name == "stream.reset":
        return _StreamResetEvent(code=code, message=message)
    return _ErrorEvent(code=code, message=message)


def _decode_chat_event(
    payload: Mapping[str, Any],
    event_id: Optional[str],
    player_client: Optional["BoxloomClient"],
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
            _client=player_client,
        ),
    )
