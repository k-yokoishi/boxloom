from dataclasses import dataclass
from math import floor
from typing import Tuple


@dataclass(frozen=True)
class SayResult:
    """Result returned after broadcasting a message."""

    message: str
    recipients: int


@dataclass(frozen=True)
class Player:
    """A player currently connected to the Minecraft server."""

    username: str
    uuid: str


@dataclass(frozen=True)
class ChatEvent:
    """A player chat message received from the live event stream."""

    id: str
    timestamp: str
    message: str
    player: Player


@dataclass(frozen=True)
class PlayerPosition:
    """A connected player's position and look direction."""

    username: str
    uuid: str
    dimension: str
    x: float
    y: float
    z: float
    yaw: float
    pitch: float

    def block_coordinates(self) -> Tuple[int, int, int]:
        """Return the block containing the player's feet."""

        return floor(self.x), floor(self.y), floor(self.z)


@dataclass(frozen=True)
class SetBlockResult:
    """Result returned after setting a block in a loaded dimension."""

    changed: bool
    dimension: str
    x: int
    y: int
    z: int
    block: str
