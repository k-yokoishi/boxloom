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
    PlayerSwingEvent,
    BlockAttackEvent,
    BlockUseEvent,
    ItemUseEvent,
    watch_chat,
    watch_event,
    subscribe_event,
)


def check_top_level() -> None:
    assert_type(watch_event(), EventStream[Event])
    assert_type(watch_event(None), EventStream[Event])
    assert_type(watch_event("chat.message"), EventStream[ChatEvent])
    assert_type(watch_event("player.death"), EventStream[PlayerDeathEvent])
    assert_type(watch_event("player.swing"), EventStream[PlayerSwingEvent])
    assert_type(watch_event("block.attack"), EventStream[BlockAttackEvent])
    assert_type(watch_event("block.use"), EventStream[BlockUseEvent])
    assert_type(watch_event("item.use"), EventStream[ItemUseEvent])
    with watch_event("player.death") as deaths:
        assert_type(next(deaths), PlayerDeathEvent)
        for death in deaths:
            assert_type(death.position.x, float)
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
    assert_type(client.watch_event("player.swing"), EventStream[PlayerSwingEvent])
    assert_type(client.watch_event("block.attack"), EventStream[BlockAttackEvent])
    assert_type(client.watch_event("block.use"), EventStream[BlockUseEvent])
    assert_type(client.watch_event("item.use"), EventStream[ItemUseEvent])
    with client.watch_event(event_type="player.death") as deaths:
        assert_type(next(deaths), PlayerDeathEvent)
    with client.watch_chat() as chats:
        assert_type(next(chats), ChatEvent)


def check_type_discriminator(event: Event) -> None:
    if event.type == "player.death":
        assert_type(event, PlayerDeathEvent)
        assert_type(event.dimension, str)
        assert_type(event.position.x, float)
    elif event.type == "chat.message":
        assert_type(event, ChatEvent)
        assert_type(event.message, str)
        event.position.x  # type: ignore[attr-defined]
    elif event.type == "player.swing":
        assert_type(event, PlayerSwingEvent)
        assert_type(event.item, Optional[str])
        assert_type(event.hand, Literal["main_hand", "off_hand"])
    elif event.type == "block.attack":
        assert_type(event, BlockAttackEvent)
        assert_type(event.block_position.x, int)
    elif event.type == "block.use":
        assert_type(event, BlockUseEvent)
        assert_type(event.block, str)
    else:
        assert_type(event, ItemUseEvent)
        assert_type(event.item, Optional[str])


def check_invalid_usage(client: BoxloomClient) -> None:
    # Unused-ignore checks fail if these calls ever stop producing type errors.
    watch_event("player.deth")  # type: ignore[call-overload]
    client.watch_event("player.deth")  # type: ignore[call-overload]
    with watch_event("chat.message") as chats:
        next(chats).position.x  # type: ignore[attr-defined]
    with watch_event() as events:
        event = next(events)
        event.type = "player.death"  # type: ignore[misc]


def check_subscriptions(client: BoxloomClient, kind: Optional[EventType]) -> None:
    def on_death(event: PlayerDeathEvent) -> None:
        assert_type(event.position.x, float)

    def on_chat(event: ChatEvent) -> None:
        assert_type(event.message, str)

    def on_player_swing(event: PlayerSwingEvent) -> None:
        assert_type(event.item, Optional[str])

    def on_block_attack(event: BlockAttackEvent) -> None:
        assert_type(event.item, Optional[str])

    def on_block_use(event: BlockUseEvent) -> None:
        assert_type(event.item, Optional[str])

    def on_item_use(event: ItemUseEvent) -> None:
        assert_type(event.item, Optional[str])

    def on_any(event: Event) -> None:
        check_type_discriminator(event)

    assert_type(subscribe_event("player.death", callback=on_death), EventSubscription)
    assert_type(subscribe_event("chat.message", callback=on_chat), EventSubscription)
    assert_type(subscribe_event(callback=on_any), EventSubscription)
    assert_type(client.subscribe_event("player.death", callback=on_death), EventSubscription)
    assert_type(client.subscribe_event("chat.message", callback=on_chat), EventSubscription)
    assert_type(client.subscribe_event(kind, callback=on_any), EventSubscription)
    assert_type(subscribe_event("player.swing", callback=on_player_swing), EventSubscription)
    assert_type(client.subscribe_event("player.swing", callback=on_player_swing), EventSubscription)
    assert_type(subscribe_event("block.attack", callback=on_block_attack), EventSubscription)
    assert_type(client.subscribe_event("block.attack", callback=on_block_attack), EventSubscription)
    assert_type(subscribe_event("block.use", callback=on_block_use), EventSubscription)
    assert_type(client.subscribe_event("block.use", callback=on_block_use), EventSubscription)
    assert_type(subscribe_event("item.use", callback=on_item_use), EventSubscription)
    assert_type(client.subscribe_event("item.use", callback=on_item_use), EventSubscription)
    subscribe_event("player.deth", callback=on_any)  # type: ignore[call-overload]
    subscribe_event("player.swing", callback=on_death)  # type: ignore[call-overload]
    subscribe_event("chat.message", callback=on_death)  # type: ignore[call-overload]
    client.subscribe_event(callback=on_death)  # type: ignore[arg-type]

    async def async_callback(event: PlayerDeathEvent) -> None:
        pass

    subscribe_event("player.death", callback=async_callback)  # type: ignore[call-overload]
