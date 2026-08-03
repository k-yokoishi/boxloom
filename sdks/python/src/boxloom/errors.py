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
