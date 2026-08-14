from boxloom import EventCursorExpiredError, watch_chat


def main() -> None:
    try:
        with watch_chat() as events:
            for event in events:
                print(f"<{event.player.username}> {event.message}")
    except EventCursorExpiredError as error:
        print(f"The saved chat cursor can no longer be resumed: {error}")


if __name__ == "__main__":
    main()
