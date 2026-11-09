"""Aim at a growing plant in Minecraft, then give, select, and use bone meal."""

from boxloom import get_players, items


def main() -> None:
    players = get_players()
    if not players:
        raise RuntimeError("No players are connected to the Minecraft server")

    player = players[0]
    result = player.give_item(items.BONE_MEAL, 16)
    print(f"Bone meal added to: {result.slots}")
    if result.slot is None:
        print("No bone meal was added to the hotbar. Move some into the hotbar in Minecraft.")
        return

    player.select_slot(result.slot)
    print(f"Used bone meal: {player.use().used}")


if __name__ == "__main__":
    main()
