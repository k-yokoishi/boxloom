import argparse
from dataclasses import asdict
import json

from boxloom import get_player_position, say, set_block


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Set a block at an offset from a connected player",
    )
    parser.add_argument("username", help="Connected Minecraft username used as the origin")
    parser.add_argument("--message", default="Hello from the boxloom Python SDK!")
    parser.add_argument("--dx", type=int, default=1)
    parser.add_argument("--dy", type=int, default=-1)
    parser.add_argument("--dz", type=int, default=0)
    parser.add_argument("--block", default="minecraft:diamond_block")
    args = parser.parse_args()

    player_position = get_player_position(args.username)
    origin_x, origin_y, origin_z = player_position.block_coordinates()
    target = (
        origin_x + args.dx,
        origin_y + args.dy,
        origin_z + args.dz,
    )
    say_result = say(args.message)
    block_result = set_block(
        *target,
        args.block,
        dimension=player_position.dimension,
    )
    print(json.dumps({"player_position": asdict(player_position)}, ensure_ascii=False))
    print(json.dumps({"offset": {"dx": args.dx, "dy": args.dy, "dz": args.dz}}))
    print(json.dumps({"say": asdict(say_result)}, ensure_ascii=False))
    print(json.dumps({"set_block": asdict(block_result)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
