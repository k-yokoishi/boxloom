# Event stream protocol

`GET /v1/events` is one long-lived Server-Sent Events (SSE) response carrying player chat, death, and action events. Clients distinguish events by their SSE event name and matching JSON `type`; WebSocket negotiation and client-to-server messages are not part of this endpoint.

## Opening and resuming

Clients send `Accept: text/event-stream` and the same optional Bearer authorization used by the JSON API. With no `Last-Event-ID`, the stream starts at the current live position and does not replay events that predate the connection.

Every cursor is an opaque string. After completely processing an event, a client may persist its `id` and later reconnect with:

```http
Last-Event-ID: <opaque event id>
```

The server replays retained events strictly after that ID. The current implementation stores the most recent 1,024 events across all types in memory, with one shared sequence and retention limit. A cursor from another Minecraft server session or one whose following history has been evicted receives an HTTP `410` JSON error with code `EVENT_CURSOR_EXPIRED`. A malformed or future cursor receives HTTP `400` with code `INVALID_EVENT_CURSOR`. Cursors do not survive a server session and must not be parsed by clients.

If a session changes while a response is open, the server sends `stream.reset` with code `EVENT_CURSOR_EXPIRED` and closes the stream. The client must explicitly choose whether to start again at the new live position.

## Events

The response starts with `retry: 1000`, sends comment heartbeats during idle periods, and emits these named events:

| SSE event | `data` JSON | Meaning |
| --- | --- | --- |
| `stream.ready` | `type`, `cursor` | The stream is open at the given cursor. Its SSE `id` is also that cursor. |
| `chat.message` | `type`, `id`, `timestamp`, `message`, `player.username`, `player.uuid` | A player chat message captured by the Fabric server callback. |
| `player.death` | `type`, `id`, `timestamp`, `message`, `player.username`, `player.uuid`, `dimension`, `x`, `y`, `z` | A confirmed player death. `message` is Minecraft's plain-text death message; dimension and coordinates are captured at death. |
| `player.swing` | `type`, `id`, `timestamp`, `player`, action fields below | An attack arm swing, including misses. In Minecraft 26.3 this always uses the main hand. |
| `block.attack` | `type`, `id`, `timestamp`, `player`, action and block fields below | A request to start attacking a block, before destruction succeeds. |
| `block.use` | `type`, `id`, `timestamp`, `player`, action and block fields below | A request to use a block, before the interaction succeeds. |
| `item.use` | `type`, `id`, `timestamp`, `player`, action fields below | A request to use a non-empty held item, before consumption or other changes. |
| `stream.reset` | `type`, `code`, `message` | The current cursor became unusable; the response ends after this event. |
| `error` | `type`, `code`, `message` | An unrecoverable server-side stream failure; the response ends. |

Every application event's JSON `id` must equal its SSE `id`, and JSON `type` must match the SSE event name. Timestamps are UTC ISO-8601 instants. Only server players produce `player.death`; mob deaths and prevented deaths (such as a successful totem save) do not. Events do not depend on death messages being shown in chat. Consumers should use event IDs for deduplication if their processing and cursor persistence are not atomic.

The Python SDK's `watch_event()` yields supported application events with an `event.type` discriminator. Passing an `event_type` such as `"player.swing"` or `"block.use"` filters in the SDK; the REST stream always carries all event types. Filtered-out events still advance the stream cursor. Unknown event names are skipped for forward compatibility; stream control events are handled internally.

## Player action snapshots

All four action events carry `player.username` and `player.uuid`, plus:

| Field | Type | Meaning |
| --- | --- | --- |
| `dimension` | string | Namespaced dimension ID. |
| `x`, `y`, `z` | number | Player position, distinct from the targeted block coordinates. |
| `hand` | string | `main_hand` or `off_hand`. |
| `item` | string or null | Namespaced held item ID, or `null` for an empty hand. This field is always present. |

`block.attack` and `block.use` additionally carry `block` (namespaced block ID),
`block_x`, `block_y`, `block_z` (integer target coordinates), and `face` (`down`,
`up`, `north`, `south`, `west`, or `east`). All values are immutable snapshots
captured on the server thread when the action reaches its server callback.
Action events do not have a `message` field.

These are notifications of server-observed attempts, not confirmations of a
successful hit, destruction, placement, or item use. They do not cancel gameplay.
Spectator actions are excluded. Requests prevented on the client or by earlier
callbacks do not reach these hooks. `item.use` observes the item-use path, while
using an item on a block (such as placement) is covered by `block.use`.

`player.swing` observes the vanilla `ServerboundPunchPacket` handler, including
attacks that hit nothing. It does not identify the target or whether an attack
missed. One action may produce both `player.swing` and `block.attack`; count only
`player.swing` when counting attack swings. Holding a button can produce repeated
actions, so these events do not represent raw mouse button presses or releases.
No client-side mod is required for any of the four events. All action events
share the same cursor and 1,024-event retention budget as chat and deaths.

## Framing

SSE blank lines delimit events, consecutive `data:` fields are joined with newlines, and lines beginning with `:` are comments. Clients must parse those rules incrementally. HTTP/1.1 commonly transports the response with chunked transfer encoding, but a transport chunk can contain part of an SSE field or several events and is never an application-level boundary.
