from dataclasses import dataclass, field
from math import floor
from typing import TYPE_CHECKING, Literal, Optional, Tuple, Union

if TYPE_CHECKING:
    from ._client import BoxloomClient


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
    """A player identity, bound to its originating client when fetched by that client."""

    username: str
    uuid: str
    _client: Optional["BoxloomClient"] = field(default=None, repr=False, compare=False)

    def get_targeted_block(self) -> Optional["TargetedBlock"]:
        """Read the block under the server-side crosshair, within interaction reach."""

        return self._require_client().get_player_targeted_block(self.username)

    def attack(self) -> "PlayerAttackResult":
        """Attack the targeted block or entity once, or swing at empty space."""

        return self._require_client().attack_player(self.username)

    def start_mining(self) -> "PlayerMiningResult":
        """Hold mining until stopped; wait without a target and continue after each break."""

        return self._require_client().start_player_mining(self.username)

    def use(self) -> "PlayerUseResult":
        """Use the main hand once on the aimed block/entity, or in the air."""

        return self._require_client().use_player_item(self.username)

    def stop_mining(self) -> "PlayerMiningResult":
        """Cancel this player's API mining session; safe to call when inactive."""

        return self._require_client().stop_player_mining(self.username)

    def _require_client(self) -> "BoxloomClient":
        if self._client is None:
            from .errors import ConfigurationError

            raise ConfigurationError("Use get_player() or get_players() to obtain an actionable Player")
        return self._client


BlockFace = Literal["down", "up", "north", "south", "west", "east"]


@dataclass(frozen=True)
class Block:
    """An immutable snapshot of a block's dimension, coordinates and namespaced ID."""

    dimension: str
    x: int
    y: int
    z: int
    block: str


@dataclass(frozen=True)
class TargetedBlock(Block):
    """A block hit by a ray from the player's eyes, with the hit face and distance."""

    face: BlockFace
    distance: float


@dataclass(frozen=True)
class PlayerAttackResult:
    """A single attack; targeting does not guarantee block destruction or entity damage."""

    swung: bool
    target: Optional[TargetedBlock]
    broken: bool
    entity: Optional["Entity"] = None


@dataclass(frozen=True)
class PlayerMiningResult:
    """Mining state immediately after a start or stop request."""

    active: bool
    target: Optional[TargetedBlock]
    broken: bool


GameMode = Literal["creative", "survival"]


@dataclass(frozen=True)
class PlayerUseResult:
    """Native use acceptance; used does not guarantee growth or an item change."""

    used: bool
    swung: bool
    target: Optional[TargetedBlock]
    entity: Optional["Entity"] = None


@dataclass(frozen=True)
class PlayerGameMode:
    """A connected player's game mode immediately after setting it."""

    username: str
    uuid: str
    mode: GameMode


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


@dataclass(frozen=True)
class _PlayerActionEvent:
    """Server-side snapshot captured when a player action is received."""

    id: str
    timestamp: str
    player: Player
    dimension: str
    x: float
    y: float
    z: float
    hand: Literal["main_hand", "off_hand"]
    item: Optional[str]


@dataclass(frozen=True)
class PlayerSwingEvent(_PlayerActionEvent):
    """An attack arm swing, including misses; not a confirmation of a hit."""

    type: Literal["player.swing"] = field(default="player.swing", init=False)


@dataclass(frozen=True)
class _BlockActionEvent(_PlayerActionEvent):
    block: str
    block_x: int
    block_y: int
    block_z: int
    face: Literal["down", "up", "north", "south", "west", "east"]


@dataclass(frozen=True)
class BlockAttackEvent(_BlockActionEvent):
    """A request to start attacking a block, before destruction succeeds."""

    type: Literal["block.attack"] = field(default="block.attack", init=False)


@dataclass(frozen=True)
class BlockUseEvent(_BlockActionEvent):
    """A request to use a block, before the interaction succeeds."""

    type: Literal["block.use"] = field(default="block.use", init=False)


@dataclass(frozen=True)
class ItemUseEvent(_PlayerActionEvent):
    """A request to use a held item, before it is consumed or changed."""

    type: Literal["item.use"] = field(default="item.use", init=False)


Event = Union[
    ChatEvent, PlayerDeathEvent, PlayerSwingEvent, BlockAttackEvent, BlockUseEvent, ItemUseEvent,
]
EventType = Literal[
    "chat.message", "player.death", "player.swing", "block.attack", "block.use", "item.use",
]

_EVENT_CLASSES = (
    ChatEvent, PlayerDeathEvent, PlayerSwingEvent, BlockAttackEvent, BlockUseEvent, ItemUseEvent,
)
_EVENT_TYPES = tuple(event.type for event in _EVENT_CLASSES)


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


# Compatibility name for callers of earlier SDK releases; block reads return Block.
GetBlockResult = Block


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
class Entity:
    """A snapshot of a loaded, alive entity, including players and dropped items."""

    uuid: str
    entity: str
    dimension: str
    x: float
    y: float
    z: float
    yaw: float
    pitch: float
    name: str
    health: Optional[float] = None


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
