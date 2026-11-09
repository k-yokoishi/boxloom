"""Immutable coordinates and vectors used by the Python API."""

from dataclasses import dataclass
from math import floor, isfinite


@dataclass(frozen=True)
class Vec3:
    """A finite 3D position or offset. Components are stored as floats."""

    x: float
    y: float
    z: float

    def __post_init__(self) -> None:
        for name in ("x", "y", "z"):
            value = getattr(self, name)
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise TypeError(f"{name} must be a number")
            try:
                component = float(value)
            except OverflowError:
                raise ValueError(f"{name} must be finite") from None
            if not isfinite(component):
                raise ValueError(f"{name} must be finite")
            object.__setattr__(self, name, component)

    def __add__(self, other: "Vec3") -> "Vec3":
        if not isinstance(other, Vec3):
            return NotImplemented
        return Vec3(self.x + other.x, self.y + other.y, self.z + other.z)

    def __sub__(self, other: "Vec3") -> "Vec3":
        if not isinstance(other, Vec3):
            return NotImplemented
        return Vec3(self.x - other.x, self.y - other.y, self.z - other.z)

    def __mul__(self, scalar: float) -> "Vec3":
        if not isinstance(scalar, (int, float)) or isinstance(scalar, bool):
            return NotImplemented
        try:
            value = float(scalar)
        except OverflowError:
            raise ValueError("scalar must be finite") from None
        if not isfinite(value):
            raise ValueError("scalar must be finite")
        return Vec3(self.x * value, self.y * value, self.z * value)

    def __rmul__(self, scalar: float) -> "Vec3":
        return self.__mul__(scalar)

    def __neg__(self) -> "Vec3":
        return Vec3(-self.x, -self.y, -self.z)

    def to_block_pos(self) -> "BlockPos":
        """Return the containing block using floor, so -0.2 becomes -1."""

        return BlockPos(floor(self.x), floor(self.y), floor(self.z))


@dataclass(frozen=True)
class BlockPos:
    """An integer block position or offset, with signed 32-bit components."""

    x: int
    y: int
    z: int

    def __post_init__(self) -> None:
        for name in ("x", "y", "z"):
            value = getattr(self, name)
            if not isinstance(value, int) or isinstance(value, bool):
                raise TypeError(f"{name} must be an integer")
            if not -(2**31) <= value < 2**31:
                raise ValueError(f"{name} must fit in a signed 32-bit integer")

    def __add__(self, other: "BlockPos") -> "BlockPos":
        if not isinstance(other, BlockPos):
            return NotImplemented
        return BlockPos(self.x + other.x, self.y + other.y, self.z + other.z)

    def __sub__(self, other: "BlockPos") -> "BlockPos":
        if not isinstance(other, BlockPos):
            return NotImplemented
        return BlockPos(self.x - other.x, self.y - other.y, self.z - other.z)

    def __mul__(self, scalar: int) -> "BlockPos":
        if not isinstance(scalar, int) or isinstance(scalar, bool):
            return NotImplemented
        return BlockPos(self.x * scalar, self.y * scalar, self.z * scalar)

    def __rmul__(self, scalar: int) -> "BlockPos":
        return self.__mul__(scalar)

    def __neg__(self) -> "BlockPos":
        return BlockPos(-self.x, -self.y, -self.z)

    def to_vec3(self) -> Vec3:
        """Return this block's minimum corner, without adding a center offset."""

        return Vec3(self.x, self.y, self.z)
