import threading
from http.server import ThreadingHTTPServer

import pytest

import boxloom


@pytest.fixture(autouse=True)
def isolated_client(monkeypatch):
    monkeypatch.delenv("BOXLOOM_BASE_URL", raising=False)
    monkeypatch.delenv("BOXLOOM_AUTH_TOKEN", raising=False)
    boxloom._reset_default_client_for_testing()
    yield
    boxloom._reset_default_client_for_testing()


@pytest.fixture(scope="module")
def http_server():
    servers = []

    def start(handler):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        servers.append((server, thread))
        return server

    yield start

    for server, thread in reversed(servers):
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
