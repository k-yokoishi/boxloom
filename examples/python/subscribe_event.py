"""Drop TNT until the selected player dies: python subscribe_event.py Steve."""

import argparse
import sys
from threading import Event as Signal

from boxloom import PlayerDeathEvent, get_player_position, subscribe_event, summon


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("username", help="Connected player taking part in the game")
    args = parser.parse_args()
    participant = get_player_position(args.username)
    game_over = Signal()

    def on_death(event: PlayerDeathEvent) -> None:
        if event.player.uuid == participant.uuid:
            print(event.message)
            game_over.set()

    def on_error(error: Exception) -> None:
        print(f"Event monitoring failed: {error}", file=sys.stderr)
        game_over.set()

    with subscribe_event(
        "player.death", callback=on_death, on_error=on_error, reconnect=False,
    ) as subscription:
        print("Run! TNT will fall every two seconds.")
        while not game_over.wait(2.0):
            subscription.raise_if_failed()
            position = get_player_position(participant.username)
            if game_over.is_set():
                break
            summon(
                "minecraft:tnt", position.x, position.y + 12, position.z,
                dimension=position.dimension,
            )
        subscription.raise_if_failed()

    print("Game over. TNT that was already spawned will finish its fuse.")


if __name__ == "__main__":
    main()
