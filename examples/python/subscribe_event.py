"""Print events received by a background subscription."""

from time import sleep

from boxloom import Event, events, subscribe_event


def on_event(event: Event) -> None:
    if event.type == events.CHAT_MESSAGE:
        print(f"<{event.player.username}> {event.message}")
    elif event.type == events.PLAYER_DEATH:
        print(
            f"{event.player.username} died in {event.dimension} "
            f"at ({event.position.x}, {event.position.y}, {event.position.z}): {event.message}"
        )


def main() -> None:
    try:
        with subscribe_event(callback=on_event) as subscription:
            print("Listening for events. Press Ctrl+C to stop.")
            while True:
                subscription.raise_if_failed()
                sleep(1.0)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
