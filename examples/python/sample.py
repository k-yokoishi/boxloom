from boxloom import (
    BlockPos,
    blocks,
    fill,
    get_block,
    get_players,
    say,
    set_block,
)


def main() -> None:
    players = get_players()
    if not players:
        raise RuntimeError("No players are connected to the Minecraft server")

    player = players[0]
    position = player.get_position()
    origin = position.position.to_block_pos()
    target = origin + BlockPos(1, -1, 0)

    say_result = say(f"Hello, {player.username}! This is boxloom.")
    block_result = set_block(
        target,
        blocks.DIAMOND_BLOCK,
        dimension=position.dimension,
    )
    get_block_result = get_block(target, dimension=position.dimension)
    fill_result = fill(
        origin + BlockPos(2, -1, 0),
        origin + BlockPos(4, -1, 0),
        blocks.EMERALD_BLOCK,
        dimension=position.dimension,
    )

    print(f"player: {player.username} ({player.uuid})")
    print(f"position: {position}")
    print(f"say: {say_result}")
    print(f"set_block: {block_result}")
    print(f"get_block: {get_block_result}")
    print(f"fill: {fill_result}")


if __name__ == "__main__":
    main()
