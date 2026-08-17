import json
import socket
from typing import Any, Dict, Mapping
from urllib.error import HTTPError

from .errors import (
    ApiError,
    ConnectionError,
    EventCursorExpiredError,
    ProtocolError,
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
