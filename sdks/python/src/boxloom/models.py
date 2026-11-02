from dataclasses import dataclass, field
from math import floor
from typing import Literal, Tuple, Union


@dataclass(frozen=True)
class TimeResult:
    """A world clock's total elapsed ticks, read on the server thread."""

    clock: str
    ticks: int


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
    type: Literal["chat.message"] = field(default="chat.message", init=False)


@dataclass(frozen=True)
class PlayerDeathEvent:
    """A player's death, including the message and position at the time of death."""

    id: str
    timestamp: str
    message: str
    player: Player
    dimension: str
    x: float
    y: float
    z: float
    type: Literal["player.death"] = field(default="player.death", init=False)


Event = Union[ChatEvent, PlayerDeathEvent]
EventType = Literal["chat.message", "player.death"]


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
class GetBlockResult:
    """Result returned after reading a block in a loaded dimension."""

    dimension: str
    x: int
    y: int
    z: int
    block: str


@dataclass(frozen=True)
class SetBlockResult:
    """Result returned after setting a block in a loaded dimension."""

    changed: bool
    dimension: str
    x: int
    y: int
    z: int
    block: str


@dataclass(frozen=True)
class FillResult:
    """Result returned after filling a block region in a loaded dimension."""

    changed_blocks: int
    dimension: str
    x1: int
    y1: int
    z1: int
    x2: int
    y2: int
    z2: int
    block: str


@dataclass(frozen=True)
class SummonResult:
    """Result returned after summoning an entity."""

    uuid: str
    entity: str
    dimension: str
    x: float
    y: float
    z: float


@dataclass(frozen=True)
class KillResult:
    """Number of entities killed in a loaded dimension."""

    dimension: str
    killed_entities: int
