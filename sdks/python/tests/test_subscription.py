import json
import queue
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler
from unittest.mock import patch

import pytest

import boxloom


def _frame(kind, event_id, **fields):
    payload = {"type": kind, **fields}
    lines = [f"event: {kind}"]
    if event_id is not None:
        lines.append(f"id: {event_id}")
    return ("\n".join(lines) + "\ndata: " + json.dumps(payload) + "\n\n").encode()


def _ready(cursor="session:0"):
    return _frame("stream.ready", cursor, cursor=cursor)


def _chat(cursor="session:1"):
    return _frame(
        "chat.message",
        cursor,
        id=cursor,
        timestamp="2026-11-02T00:00:00Z",
        message="hello",
        player={"username": "Steve", "uuid": "player-uuid"},
    )


def _death(cursor="session:1"):
    return _frame(
        "player.death",
        cursor,
        id=cursor,
        timestamp="2026-11-02T00:00:00Z",
        message="Steve died",
        player={"username": "Steve", "uuid": "player-uuid"},
        dimension="minecraft:overworld",
        x=1.5,
        y=64.0,
        z=-2.25,
    )


class _Scenario:
    def __init__(self, ready=True, heartbeats=True, status=200):
        self.frames = queue.Queue()
        self.requested = threading.Event()
        self.finished = threading.Event()
        self.headers = {}
        self.heartbeats = heartbeats
        self.status = status
        if ready and status == 200:
            self.frames.put(_ready())


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        scenario = self.server.scenarios.get(timeout=2)
        scenario.headers = dict(self.headers)
        scenario.requested.set()
        self.send_response(scenario.status)
        if scenario.status != 200:
            body = json.dumps(
                {"error": {"code": "UNAUTHORIZED", "message": "No token"}}
            ).encode()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            scenario.finished.set()
            return
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        try:
            while True:
                try:
                    frame = scenario.frames.get(timeout=0.03)
                except queue.Empty:
                    if not scenario.heartbeats:
                        continue
                    frame = b": heartbeat\n\n"
                if frame is None:
                    return
                self.wfile.write(frame)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            scenario.finished.set()

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        body = json.dumps({"message": payload["message"], "recipients": 1}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def subscription_server(http_server):
    server = http_server(_Handler)
    server.scenarios = queue.Queue()
    return server


class TestSubscription:
    @pytest.fixture(autouse=True)
    def configure_subscriptions(self, subscription_server, isolated_client):
        self.server = subscription_server
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.scenarios = []
        self.subscriptions = []
        self.client = boxloom.BoxloomClient(
            base_url=self.url, timeout=2.0, auth_token="secret"
        )
        boxloom.init(base_url=self.url, timeout=0.3, auth_token="secret")

        yield

        for subscription in self.subscriptions:
            subscription.stop()
        for scenario in self.scenarios:
            scenario.frames.put(None)
        for subscription in self.subscriptions:
            subscription.join(2)

    def scenario(self, **kwargs):
        scenario = _Scenario(**kwargs)
        self.scenarios.append(scenario)
        self.server.scenarios.put(scenario)
        return scenario

    def subscribe(self, *args, **kwargs):
        kwargs.setdefault("reconnect", False)
        subscription = self.client.subscribe_event(*args, **kwargs)
        self.subscriptions.append(subscription)
        return subscription

    @pytest.mark.parametrize(
        "event_filter",
        (None, *("player.swing", "block.attack", "block.use", "item.use")),
    )
    def test_player_actions_reach_callbacks_with_optional_filter(self, event_filter):
        kinds = ("player.swing", "block.attack", "block.use", "item.use")
        scenario = self.scenario()
        calls = []
        received = threading.Event()
        expected = list(kinds) if event_filter is None else [event_filter]

        def callback(event):
            calls.append(event)
            if len(calls) == len(expected):
                received.set()

        subscription = self.subscribe(event_filter, callback=callback)
        for i, kind in enumerate(kinds, 1):
            fields = dict(
                id=f"session:{i}",
                timestamp="2026-11-06T00:00:00Z",
                player={"username": "Steve", "uuid": "player-uuid"},
                dimension="minecraft:overworld",
                x=1.0,
                y=64.0,
                z=2.0,
                hand="main_hand",
                item="minecraft:diamond_sword",
            )
            if kind.startswith("block."):
                fields.update(
                    block="minecraft:stone", block_x=1, block_y=63, block_z=2, face="up"
                )
            scenario.frames.put(_frame(kind, f"session:{i}", **fields))
        assert received.wait(2)
        subscription.stop()
        subscription.join(2)
        subscription.raise_if_failed()
        assert [event.type for event in calls] == expected
        assert all((event.item == "minecraft:diamond_sword" for event in calls))

    def test_callback_can_use_the_event_player_after_default_client_changes(self):
        scenario = self.scenario()
        received = threading.Event()
        positions = []
        errors = []
        position = boxloom.PlayerLocation(
            "Steve",
            "player-uuid",
            "minecraft:overworld",
            boxloom.Vec3(1.5, 64, -2.25),
            0,
            0,
        )

        def callback(event):
            positions.append(event.player.get_location())
            received.set()

        def on_error(error):
            errors.append(error)
            received.set()

        with patch.object(
            boxloom.BoxloomClient,
            "get_player_location",
            autospec=True,
            return_value=position,
        ) as get_location:
            subscription = self.subscribe(
                "chat.message", callback=callback, on_error=on_error
            )
            boxloom.init(base_url="http://127.0.0.1:1", auth_token="other-secret")
            scenario.frames.put(_chat())
            assert received.wait(2)
            subscription.stop()
            subscription.join(2)
            subscription.raise_if_failed()
            assert errors == []
            assert positions == [position]
            get_location.assert_called_once_with(self.client, "Steve")

    def test_registration_waits_for_ready_and_callback_runs_in_background(self):
        scenario = self.scenario(ready=False)
        received = threading.Event()
        calls = []

        def callback(event):
            calls.append((event, threading.get_ident(), self.client.say("game over")))
            received.set()

        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(self.subscribe, "player.death", callback=callback)
            assert scenario.requested.wait(2)
            assert not future.done()
            scenario.frames.put(_ready())
            subscription = future.result(2)
        assert subscription.last_event_id == "session:0"
        assert scenario.headers["Authorization"] == "Bearer secret"
        scenario.frames.put(_death())
        assert received.wait(2)
        subscription.stop()
        subscription.join(2)
        subscription.raise_if_failed()
        assert calls[0][0].type == "player.death"
        assert calls[0][1] != threading.get_ident()
        assert calls[0][2].message == "game over"
        assert subscription.last_event_id == "session:1"

    def test_callbacks_are_serial_and_main_thread_can_keep_using_api(self):
        scenario = self.scenario()
        entered = threading.Event()
        release = threading.Event()
        finished = threading.Event()
        calls = []

        def callback(event):
            calls.append(event.id)
            if len(calls) == 1:
                entered.set()
                if not release.wait(2):
                    raise RuntimeError("test did not release the callback")
            else:
                finished.set()

        subscription = self.subscribe(callback=callback)
        scenario.frames.put(_chat("session:1") + _death("session:2"))
        assert entered.wait(2)
        assert subscription.last_event_id == "session:0"
        try:
            assert self.client.say("still running").message == "still running"
            assert calls == ["session:1"]
        finally:
            release.set()
        assert finished.wait(2)
        subscription.stop()
        subscription.join(2)
        assert calls == ["session:1", "session:2"]
        assert subscription.last_event_id == "session:2"

    def test_callback_failure_preserves_last_completed_cursor_and_notifies_once(self):
        scenario = self.scenario()
        failure = ValueError("game failed")
        notified = threading.Event()
        errors = []
        calls = []

        def callback(event):
            calls.append(event.id)
            raise failure

        def on_error(error):
            errors.append(error)
            notified.set()

        subscription = self.subscribe(
            "player.death", callback=callback, on_error=on_error
        )
        scenario.frames.put(
            _chat("session:1")
            + _frame("future.event", "session:2")
            + _death("session:3")
            + _death("session:4")
        )
        assert notified.wait(2)
        subscription.join(2)
        assert errors == [failure]
        assert calls == ["session:3"]
        assert subscription.last_event_id == "session:2"
        with pytest.raises(ValueError) as caught:
            subscription.raise_if_failed()
        assert caught.value is failure
        with pytest.raises(ValueError):
            with subscription:
                pass

    def test_reconnect_resumes_after_successful_delivery(self):
        first = self.scenario()
        second = self.scenario(ready=False)
        delivered = threading.Event()
        finished = threading.Event()
        calls = []
        errors = []

        def callback(event):
            calls.append(event.id)
            (delivered if len(calls) == 1 else finished).set()

        subscription = self.subscribe(
            callback=callback, on_error=errors.append, reconnect=True
        )
        first.frames.put(b"retry: 1\n\n" + _chat("session:1"))
        assert delivered.wait(2)
        first.frames.put(None)
        assert second.requested.wait(2)
        headers = {name.lower(): value for name, value in second.headers.items()}
        assert headers["last-event-id"] == "session:1"
        second.frames.put(_ready("session:1") + _death("session:2"))
        assert finished.wait(2)
        subscription.stop()
        subscription.join(2)
        subscription.raise_if_failed()
        assert calls == ["session:1", "session:2"]
        assert errors == []

    def test_initial_api_and_readiness_errors_are_raised_synchronously(self):
        self.scenario(status=401)
        errors = []
        with pytest.raises(boxloom.ApiError) as caught:
            self.subscribe(callback=lambda event: None, on_error=errors.append)
        assert caught.value.status == 401
        assert errors == []

        scenario = self.scenario(ready=False)
        scenario.frames.put(_frame("stream.ready", "other:0", cursor="session:0"))
        with pytest.raises(boxloom.ProtocolError):
            self.subscribe(callback=lambda event: None)

        scenario = self.scenario(ready=False)
        scenario.frames.put(_death())
        with pytest.raises(boxloom.ProtocolError):
            self.subscribe(callback=lambda event: None)

    @pytest.mark.parametrize("reset", (True, False))
    def test_stream_reset_and_disconnect_stop_and_report_failures(self, reset):
        scenario = self.scenario()
        errors = []
        notified = threading.Event()

        def on_error(error):
            errors.append(error)
            notified.set()

        subscription = self.subscribe(callback=lambda event: None, on_error=on_error)
        scenario.frames.put(
            _frame(
                "stream.reset",
                None,
                code="EVENT_CURSOR_EXPIRED",
                message="Expired",
            )
            if reset
            else None
        )
        assert notified.wait(2)
        subscription.join(2)
        expected = boxloom.EventCursorExpiredError if reset else boxloom.ConnectionError
        assert isinstance(errors[0], expected)
        with pytest.raises(expected):
            subscription.raise_if_failed()

    def test_stop_is_idempotent_and_join_times_out_for_an_active_callback(self):
        scenario = self.scenario()
        entered = threading.Event()
        release = threading.Event()
        calls = []

        def callback(event):
            calls.append(event.id)
            entered.set()
            if not release.wait(2):
                raise RuntimeError("test did not release the callback")

        subscription = self.subscribe(callback=callback)
        scenario.frames.put(_death("session:1") + _death("session:2"))
        assert entered.wait(2)
        try:
            subscription.stop()
            subscription.stop()
            with pytest.raises(TimeoutError):
                subscription.join(0)
        finally:
            release.set()
        subscription.join(2)
        subscription.raise_if_failed()
        assert calls == ["session:1"]
        assert subscription.last_event_id == "session:1"

    def test_stop_from_callback_does_not_join_its_own_thread(self):
        scenario = self.scenario()
        finished = threading.Event()

        def callback(event):
            with pytest.raises(RuntimeError):
                subscription.join()
            subscription.stop()
            finished.set()

        subscription = self.subscribe(callback=callback)
        scenario.frames.put(_death())
        assert finished.wait(2)
        subscription.join(2)
        subscription.raise_if_failed()

    def test_error_handler_failure_keeps_original_error_and_outer_exception(
        self, caplog
    ):
        scenario = self.scenario()
        failure = ValueError("original failure")
        notified = threading.Event()

        def callback(event):
            raise failure

        def on_error(error):
            notified.set()
            raise RuntimeError("error handler failure")

        subscription = self.subscribe(callback=callback, on_error=on_error)
        with caplog.at_level("ERROR", logger="boxloom.events"):
            scenario.frames.put(_death())
            assert notified.wait(2)
            subscription.join(2)
        assert any(
            record.name == "boxloom.events" and record.levelname == "ERROR"
            for record in caplog.records
        )
        with pytest.raises(ValueError) as caught:
            subscription.raise_if_failed()
        assert caught.value is failure
        with pytest.raises(RuntimeError, match="outer failure"):
            with subscription:
                raise RuntimeError("outer failure")

    def test_top_level_api_and_default_error_logging(self, caplog):
        scenario = self.scenario()

        def callback(event):
            raise RuntimeError("callback failed")

        subscription = boxloom.subscribe_event(
            "player.death", callback=callback, reconnect=False
        )
        self.subscriptions.append(subscription)
        with caplog.at_level("ERROR", logger="boxloom.events"):
            scenario.frames.put(_death())
            subscription.join(2)
        assert any(
            record.name == "boxloom.events" and record.levelname == "ERROR"
            for record in caplog.records
        )
        with pytest.raises(RuntimeError, match="callback failed"):
            subscription.raise_if_failed()

    def test_stop_during_silent_read_is_bounded_by_client_timeout(self):
        self.client = boxloom.BoxloomClient(base_url=self.url, timeout=0.15)
        self.scenario(heartbeats=False)
        subscription = self.subscribe(callback=lambda event: None)
        subscription.stop()
        subscription.join(1)
        subscription.raise_if_failed()

    def test_callbacks_and_join_arguments_are_validated(self):
        async def async_callback(event):
            pass

        class AsyncCallback:
            async def __call__(self, event):
                pass

        for callback in (None, 1, async_callback, AsyncCallback()):
            with pytest.raises(TypeError):
                self.subscribe(callback=callback)
        with pytest.raises(TypeError):
            self.subscribe(callback=lambda event: None, on_error=async_callback)
        self.scenario()
        subscription = self.subscribe(callback=lambda event: None)
        for timeout in (-1, float("inf"), float("nan"), True, "1"):
            with pytest.raises(ValueError):
                subscription.join(timeout)

    def test_missing_readiness_times_out_even_if_heartbeats_continue(self):
        self.client = boxloom.BoxloomClient(base_url=self.url, timeout=0.15)
        self.scenario(ready=False)
        with pytest.raises(boxloom.ConnectionError):
            self.subscribe(callback=lambda event: None)

    def test_awaitable_callback_result_is_closed_and_reported(self):
        scenario = self.scenario()
        notified = threading.Event()

        async def coroutine():
            pass

        def callback(event):
            return coroutine()

        subscription = self.subscribe(
            callback=callback, on_error=lambda error: notified.set()
        )
        scenario.frames.put(_death())
        assert notified.wait(2)
        subscription.join(2)
        with pytest.raises(TypeError):
            subscription.raise_if_failed()
