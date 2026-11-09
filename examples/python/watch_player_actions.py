"""Watch player action attempts, including attack swings that miss."""

from boxloom import EventCursorExpiredError, watch_event


def main() -> None:
    try:
        with watch_event() as events:
            for event in events:
                if event.type == "player.swing":
                    print(event.player.username, "swung", event.item or "an empty hand")
                elif event.type == "block.attack" or event.type == "block.use":
                    print(
                        event.player.username, event.type, event.block,
                        (event.block_position.x, event.block_position.y, event.block_position.z),
                        event.face, event.hand, event.item,
                    )
                elif event.type == "item.use":
                    print(event.player.username, "tried to use", event.item, event.hand)
    except EventCursorExpiredError as error:
        print(f"The saved event cursor can no longer be resumed: {error}")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
