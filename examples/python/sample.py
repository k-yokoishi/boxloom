from boxloom import get_player_position, get_players, say, set_block


def main() -> None:
    players = get_players()
    if not players:
        raise RuntimeError("No players are connected to the Minecraft server")

    player = players[0]
    position = get_player_position(player.username)
    x, y, z = position.block_coordinates()

    say_result = say(f"Hello, {player.username}! This is boxloom.")
    block_result = set_block(
        x + 1,
        y - 1,
        z,
        "minecraft:diamond_block",
        dimension=position.dimension,
    )

    print(f"player: {player.username} ({player.uuid})")
    print(f"position: {position}")
    print(f"say: {say_result}")
    print(f"set_block: {block_result}")


if __name__ == "__main__":
    main()
