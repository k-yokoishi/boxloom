"""Receive attack swings while the main thread continues running."""

from time import sleep

from boxloom import PlayerSwingEvent, subscribe_event


def on_swing(event: PlayerSwingEvent) -> None:
    print(event.player.username, "swung", event.item or "an empty hand")


def main() -> None:
    try:
        with subscribe_event("player.swing", callback=on_swing) as subscription:
            print("Listening for attack swings, including misses. Press Ctrl+C to stop.")
            while True:
                subscription.raise_if_failed()
                sleep(1.0)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
