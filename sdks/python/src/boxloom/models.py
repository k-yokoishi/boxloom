from dataclasses import dataclass, field
from math import floor
from typing import TYPE_CHECKING, Optional, Tuple

from .errors import ConfigurationError

if TYPE_CHECKING:
    from ._client import BoxloomClient


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
    _client: Optional["BoxloomClient"] = field(
        default=None,
        repr=False,
        compare=False,
    )

    def teleport(
        self,
        x: float,
        y: float,
        z: float,
        *,
        dimension: Optional[str] = None,
        yaw: Optional[float] = None,
        pitch: Optional[float] = None,
    ) -> "PlayerPosition":
        """Teleport this player to absolute coordinates."""

        if self._client is None:
            raise ConfigurationError(
                "player is not attached to a BoxloomClient; obtain it with get_players()"
            )
        return self._client.teleport_player(
            self.username,
            x,
            y,
            z,
            dimension=dimension,
            yaw=yaw,
            pitch=pitch,
        )


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


@dataclass(frozen=True)
class SummonResult:
    """Result returned after summoning an entity."""

    uuid: str
    entity: str
    dimension: str
    x: float
    y: float
    z: float
