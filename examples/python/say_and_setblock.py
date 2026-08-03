import argparse
from dataclasses import asdict
import json

from boxloom import say, set_block


def main() -> None:
    parser = argparse.ArgumentParser(description="Try boxloom say and set_block APIs")
    parser.add_argument("--message", default="Hello from the boxloom Python SDK!")
    parser.add_argument("--x", type=int, default=0)
    parser.add_argument("--y", type=int, default=100)
    parser.add_argument("--z", type=int, default=0)
    parser.add_argument("--block", default="minecraft:diamond_block")
    parser.add_argument("--dimension", default="minecraft:overworld")
    args = parser.parse_args()

    say_result = say(args.message)
    block_result = set_block(
        args.x,
        args.y,
        args.z,
        args.block,
        dimension=args.dimension,
    )
    print(json.dumps({"say": asdict(say_result)}, ensure_ascii=False))
    print(json.dumps({"set_block": asdict(block_result)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
