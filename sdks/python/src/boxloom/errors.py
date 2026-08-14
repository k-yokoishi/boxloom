class BoxloomError(Exception):
    """Base class for all SDK errors."""


class ConfigurationError(BoxloomError):
    """The SDK connection configuration is missing or invalid."""


class ConnectionError(BoxloomError):
    """The boxloom server could not be reached."""


class ProtocolError(BoxloomError):
    """The server returned a response that does not match the API contract."""


class ApiError(BoxloomError):
    """The boxloom server rejected or failed an API request."""

    def __init__(self, status: int, code: str, message: str) -> None:
        self.status = status
        self.code = code
        self.message = message
        super().__init__(f"boxloom API error {status} ({code}): {message}")


class EventCursorExpiredError(ApiError):
    """The event stream can no longer resume from the requested cursor."""

    def __init__(self, message: str) -> None:
        super().__init__(410, "EVENT_CURSOR_EXPIRED", message)


class EventStreamError(BoxloomError):
    """The server reported an error after opening the event stream."""

    def __init__(self, code: str, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(f"boxloom event stream error ({code}): {message}")
