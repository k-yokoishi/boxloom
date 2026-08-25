# Event stream protocol

`GET /v1/events` is one long-lived Server-Sent Events (SSE) response. It replaces repeated requests for new player chat messages; WebSocket negotiation and client-to-server messages are not part of this endpoint.

## Opening and resuming

Clients send `Accept: text/event-stream` and the same optional Bearer authorization used by the JSON API. With no `Last-Event-ID`, the stream starts at the current live position and does not replay chat that predates the connection.

Every cursor is an opaque string. After completely processing an event, a client may persist its `id` and later reconnect with:

```http
Last-Event-ID: <opaque event id>
```

The server replays retained events strictly after that ID. The current implementation stores the most recent 1,024 chat events in memory. A cursor from another Minecraft server session or one whose following history has been evicted receives an HTTP `410` JSON error with code `EVENT_CURSOR_EXPIRED`. A malformed or future cursor receives HTTP `400` with code `INVALID_EVENT_CURSOR`. Cursors do not survive a server session and must not be parsed by clients.

If a session changes while a response is open, the server sends `stream.reset` with code `EVENT_CURSOR_EXPIRED` and closes the stream. The client must explicitly choose whether to start again at the new live position.

## Events

The response starts with `retry: 1000`, sends comment heartbeats during idle periods, and emits these named events:

| SSE event | `data` JSON | Meaning |
| --- | --- | --- |
| `stream.ready` | `type`, `cursor` | The stream is open at the given cursor. Its SSE `id` is also that cursor. |
| `chat.message` | `type`, `id`, `timestamp`, `message`, `player.username`, `player.uuid` | A player chat message captured by the Fabric server callback. |
| `stream.reset` | `type`, `code`, `message` | The current cursor became unusable; the response ends after this event. |
| `error` | `type`, `code`, `message` | An unrecoverable server-side stream failure; the response ends. |

The `chat.message` JSON `id` must equal its SSE `id`. Timestamps are UTC ISO-8601 instants. Consumers should use event IDs for deduplication if their processing and cursor persistence are not atomic.

## Framing

SSE blank lines delimit events, consecutive `data:` fields are joined with newlines, and lines beginning with `:` are comments. Clients must parse those rules incrementally. HTTP/1.1 commonly transports the response with chunked transfer encoding, but a transport chunk can contain part of an SSE field or several events and is never an application-level boundary.
