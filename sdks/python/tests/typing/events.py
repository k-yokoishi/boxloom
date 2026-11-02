"""Static regression checks; this module is checked but never executed."""

from typing import Literal, Optional

from typing_extensions import assert_type

from boxloom import (
    BoxloomClient,
    ChatEvent,
    Event,
    EventStream,
    EventType,
    PlayerDeathEvent,
    watch_chat,
    watch_event,
)


def check_top_level() -> None:
    assert_type(watch_event(), EventStream[Event])
    assert_type(watch_event(None), EventStream[Event])
    assert_type(watch_event("chat.message"), EventStream[ChatEvent])
    assert_type(watch_event("player.death"), EventStream[PlayerDeathEvent])
    with watch_event("player.death") as deaths:
        assert_type(next(deaths), PlayerDeathEvent)
        for death in deaths:
            assert_type(death.x, float)
            assert_type(death.type, Literal["player.death"])
    with watch_event(event_type="chat.message") as chats:
        assert_type(next(chats), ChatEvent)
    with watch_chat() as legacy_chats:
        assert_type(next(legacy_chats), ChatEvent)


def check_client(client: BoxloomClient, kind: Optional[EventType]) -> None:
    assert_type(client.watch_event(), EventStream[Event])
    assert_type(client.watch_event(kind), EventStream[Event])
    assert_type(watch_event(kind), EventStream[Event])
    assert_type(client.watch_event("chat.message"), EventStream[ChatEvent])
    assert_type(client.watch_event("player.death"), EventStream[PlayerDeathEvent])
    with client.watch_event(event_type="player.death") as deaths:
        assert_type(next(deaths), PlayerDeathEvent)
    with client.watch_chat() as chats:
        assert_type(next(chats), ChatEvent)


def check_type_discriminator(event: Event) -> None:
    if event.type == "player.death":
        assert_type(event, PlayerDeathEvent)
        assert_type(event.dimension, str)
        assert_type(event.x, float)
    else:
        assert_type(event, ChatEvent)
        assert_type(event.message, str)
        event.x  # type: ignore[attr-defined]


def check_invalid_usage(client: BoxloomClient) -> None:
    # Unused-ignore checks fail if these calls ever stop producing type errors.
    watch_event("player.deth")  # type: ignore[call-overload]
    client.watch_event("player.deth")  # type: ignore[call-overload]
    with watch_event("chat.message") as chats:
        next(chats).x  # type: ignore[attr-defined]
    with watch_event() as events:
        event = next(events)
        event.type = "player.death"  # type: ignore[misc]
