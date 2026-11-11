"""Public event names and types for boxloom live event streams."""

from typing import Final as _Final, Literal as _Literal

from ..errors import EventCursorExpiredError, EventStreamError
from ..models import (
    BlockAttackEvent,
    BlockUseEvent,
    ChatEvent,
    Event,
    EventType,
    ItemUseEvent,
    PlayerDeathEvent,
    PlayerSwingEvent,
)
from ._stream import ChatEventStream, EventStream
from ._subscription import EventSubscription

CHAT_MESSAGE: _Final[_Literal["chat.message"]] = ChatEvent.type
PLAYER_DEATH: _Final[_Literal["player.death"]] = PlayerDeathEvent.type
PLAYER_SWING: _Final[_Literal["player.swing"]] = PlayerSwingEvent.type
BLOCK_ATTACK: _Final[_Literal["block.attack"]] = BlockAttackEvent.type
BLOCK_USE: _Final[_Literal["block.use"]] = BlockUseEvent.type
ITEM_USE: _Final[_Literal["item.use"]] = ItemUseEvent.type

__all__ = [
    "CHAT_MESSAGE",
    "PLAYER_DEATH",
    "PLAYER_SWING",
    "BLOCK_ATTACK",
    "BLOCK_USE",
    "ITEM_USE",
    "ChatEvent",
    "ChatEventStream",
    "Event",
    "EventCursorExpiredError",
    "EventStream",
    "EventStreamError",
    "EventSubscription",
    "EventType",
    "PlayerDeathEvent",
    "PlayerSwingEvent",
    "BlockAttackEvent",
    "BlockUseEvent",
    "ItemUseEvent",
]
