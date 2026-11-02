"""Static regression checks; this module is checked but never executed."""

from typing import Literal, Optional

from typing_extensions import assert_type

from boxloom import (
    BoxloomClient,
    ChatEvent,
    Event,
    EventStream,
    EventSubscription,
    EventType,
    PlayerDeathEvent,
    watch_chat,
    watch_event,
    subscribe_event,
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


def check_subscriptions(client: BoxloomClient, kind: Optional[EventType]) -> None:
    def on_death(event: PlayerDeathEvent) -> None:
        assert_type(event.x, float)

    def on_chat(event: ChatEvent) -> None:
        assert_type(event.message, str)

    def on_any(event: Event) -> None:
        check_type_discriminator(event)

    assert_type(subscribe_event("player.death", callback=on_death), EventSubscription)
    assert_type(subscribe_event("chat.message", callback=on_chat), EventSubscription)
    assert_type(subscribe_event(callback=on_any), EventSubscription)
    assert_type(client.subscribe_event("player.death", callback=on_death), EventSubscription)
    assert_type(client.subscribe_event("chat.message", callback=on_chat), EventSubscription)
    assert_type(client.subscribe_event(kind, callback=on_any), EventSubscription)
    subscribe_event("player.deth", callback=on_any)  # type: ignore[call-overload]
    subscribe_event("chat.message", callback=on_death)  # type: ignore[call-overload]
    client.subscribe_event(callback=on_death)  # type: ignore[arg-type]

    async def async_callback(event: PlayerDeathEvent) -> None:
        pass

    subscribe_event("player.death", callback=async_callback)  # type: ignore[call-overload]
