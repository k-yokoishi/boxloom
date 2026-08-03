from dataclasses import dataclass


@dataclass(frozen=True)
class SayResult:
    """Result returned after broadcasting a message."""

    message: str
    recipients: int


@dataclass(frozen=True)
class SetBlockResult:
    """Result returned after setting a block in a loaded dimension."""

    changed: bool
    dimension: str
    x: int
    y: int
    z: int
    block: str
