from boxloom import EventCursorExpiredError, events, watch_event


def main() -> None:
    try:
        with watch_event() as stream:
            for event in stream:
                if event.type == events.CHAT_MESSAGE:
                    print(f"<{event.player.username}> {event.message}")
                elif event.type == events.PLAYER_DEATH:
                    print(
                        f"{event.player.username} died in {event.dimension} "
                        f"at ({event.position.x}, {event.position.y}, {event.position.z}): {event.message}"
                    )
    except EventCursorExpiredError as error:
        print(f"The saved event cursor can no longer be resumed: {error}")


if __name__ == "__main__":
    main()
