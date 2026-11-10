"""Watch player action attempts, including attack swings that miss."""

from boxloom import EventCursorExpiredError, events, watch_event


def main() -> None:
    try:
        with watch_event() as stream:
            for event in stream:
                if event.type == events.PLAYER_SWING:
                    print(event.player.username, "swung", event.item or "an empty hand")
                elif event.type == events.BLOCK_ATTACK or event.type == events.BLOCK_USE:
                    print(
                        event.player.username, event.type, event.block,
                        (event.block_position.x, event.block_position.y, event.block_position.z),
                        event.face, event.hand, event.item,
                    )
                elif event.type == events.ITEM_USE:
                    print(event.player.username, "tried to use", event.item, event.hand)
    except EventCursorExpiredError as error:
        print(f"The saved event cursor can no longer be resumed: {error}")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
