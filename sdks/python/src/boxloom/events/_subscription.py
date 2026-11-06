import inspect
import logging
import math
import threading
from typing import Any, Callable, Optional, TypeVar

from ..models import Event, _EVENT_CLASSES
from ._stream import EventStream


_LOGGER = logging.getLogger("boxloom.events")
_ArgT = TypeVar("_ArgT")


def _check_callback(callback: Any, name: str) -> None:
    if not callable(callback):
        raise TypeError(f"{name} must be callable")
    for candidate in (callback, getattr(callback, "__call__", None)):
        if inspect.iscoroutinefunction(candidate) or inspect.isasyncgenfunction(candidate):
            raise TypeError(f"{name} must be a synchronous callback")


def _invoke(callback: Callable[[_ArgT], object], argument: _ArgT) -> None:
    result = callback(argument)
    if inspect.isawaitable(result):
        if inspect.iscoroutine(result):
            result.close()
        raise TypeError("event callbacks must not return an awaitable")


class EventSubscription:
    """A ready, background event receiver with serial synchronous callbacks.

    Prefer a with block, or call stop() followed by join(). Stopping is
    cooperative: an active read can take up to the client timeout, and a running
    callback is allowed to finish. The worker does not keep Python alive on exit.
    """

    def __init__(
        self,
        stream: EventStream[Event],
        *,
        callback: Callable[[Event], None],
        on_error: Optional[Callable[[Exception], None]] = None,
    ) -> None:
        _check_callback(callback, "callback")
        if on_error is not None:
            _check_callback(on_error, "on_error")
        self._stream = stream
        self._callback = callback
        self._on_error = on_error
        self._ready = threading.Event()
        self._stopping = threading.Event()
        self._lock = threading.Lock()
        self._started = False
        self._error: Optional[Exception] = None
        self._last_event_id = stream.last_event_id
        self._thread = threading.Thread(
            target=self._run, name="boxloom-events", daemon=True,
        )
        self._thread.start()
        try:
            self._ready.wait()
        except BaseException:
            self.stop()
            self.join()
            raise
        if not self._started:
            self.join()
            self.raise_if_failed()

    @property
    def last_event_id(self) -> Optional[str]:
        """Last successfully processed cursor, including filtered-out events."""

        with self._lock:
            return self._last_event_id

    def stop(self) -> None:
        """Request a stop without waiting; safe to call inside a callback."""

        self._stopping.set()
        self._stream._request_stop()

    def join(self, timeout: Optional[float] = None) -> None:
        """Wait for completion, raising TimeoutError if the worker is still busy."""

        if threading.current_thread() is self._thread:
            raise RuntimeError("a subscription callback cannot join its own worker")
        if timeout is not None and (
            isinstance(timeout, bool)
            or not isinstance(timeout, (int, float))
            or not math.isfinite(timeout)
            or timeout < 0
        ):
            raise ValueError("timeout must be a finite non-negative number or None")
        self._thread.join(timeout)
        if self._thread.is_alive():
            raise TimeoutError("the event subscription has not finished")

    def raise_if_failed(self) -> None:
        """Re-raise a saved stream or callback failure on the calling thread."""

        with self._lock:
            error = self._error
        if error is not None:
            raise error

    def __enter__(self) -> "EventSubscription":
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.stop()
        self.join()
        if exc_type is None:
            self.raise_if_failed()

    def _acknowledge(self, cursor: Optional[str]) -> None:
        with self._lock:
            self._last_event_id = cursor

    def _run(self) -> None:
        try:
            self._acknowledge(self._stream._open())
            self._started = True
            self._ready.set()
            while not self._stopping.is_set():
                try:
                    event = self._stream._next_event()
                except StopIteration:
                    break
                if self._stopping.is_set():
                    break
                if isinstance(event, _EVENT_CLASSES) and self._stream._accepts(event):
                    _invoke(self._callback, event)
                self._acknowledge(self._stream.last_event_id)
        except Exception as error:
            with self._lock:
                self._error = error
        finally:
            try:
                self._stream.close()
            except Exception as error:
                with self._lock:
                    if self._error is None:
                        self._error = error
            finally:
                if self._started and self._error is not None:
                    if self._on_error is None:
                        _LOGGER.error("event subscription failed", exc_info=self._error)
                    else:
                        try:
                            _invoke(self._on_error, self._error)
                        except Exception:
                            _LOGGER.exception("event subscription on_error callback failed")
                self._ready.set()
