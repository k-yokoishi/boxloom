import operator
from dataclasses import FrozenInstanceError

import pytest

from boxloom import BlockPos, Vec3


def test_vec3_arithmetic_preserves_inputs():
    origin = Vec3(1.5, -2, 3)
    offset = Vec3(-0.5, 4, 2.25)
    assert origin + offset == Vec3(1, 2, 5.25)
    assert origin - offset == Vec3(2, -6, 0.75)
    assert -origin == Vec3(-1.5, 2, -3)
    assert origin * 2 == Vec3(3, -4, 6)
    assert 0.5 * origin == Vec3(0.75, -1, 1.5)
    assert origin == Vec3(1.5, -2, 3)
    assert type(origin.y) is float


def test_block_arithmetic_keeps_integer_components():
    origin = BlockPos(2, 64, -3)
    offset = BlockPos(-1, -1, 4)
    assert origin + offset == BlockPos(1, 63, 1)
    assert origin - offset == BlockPos(3, 65, -7)
    assert -origin == BlockPos(-2, -64, 3)
    assert origin * -2 == BlockPos(-4, -128, 6)
    assert 3 * origin == BlockPos(6, 192, -9)
    assert origin == BlockPos(2, 64, -3)
    assert type((origin * 2).x) is int


@pytest.mark.parametrize("value", [Vec3(1, 2, 3), BlockPos(1, 2, 3)])
def test_coordinate_values_are_immutable_and_hashable(value):
    with pytest.raises(FrozenInstanceError):
        value.x = 10
    assert {value: "stored"}[type(value)(1, 2, 3)] == "stored"
    assert len({value, type(value)(1, 2, 3)}) == 1
    assert Vec3(1, 2, 3) != BlockPos(1, 2, 3)


@pytest.mark.parametrize(
    "position, expected",
    [
        (Vec3(-0.2, 64.8, 3.9), BlockPos(-1, 64, 3)),
        (Vec3(-1, -0.0, -1.01), BlockPos(-1, 0, -2)),
        (Vec3(0, 0, 0), BlockPos(0, 0, 0)),
    ],
)
def test_conversions_floor_negatives_and_preserve_block_corners(position, expected):
    assert position.to_block_pos() == expected
    corner = BlockPos(-1, 64, 3).to_vec3()
    assert corner == Vec3(-1, 64, 3)
    assert corner + Vec3(0.5, 0.5, 0.5) == Vec3(-0.5, 64.5, 3.5)
    assert corner.to_block_pos() == BlockPos(-1, 64, 3)


@pytest.mark.parametrize("axis", range(3))
@pytest.mark.parametrize(
    "cls, value",
    [
        (cls, value)
        for cls, values in (
            (
                Vec3,
                (
                    True,
                    None,
                    "1",
                    [],
                    float("nan"),
                    float("inf"),
                    -float("inf"),
                    10**1000,
                ),
            ),
            (BlockPos, (True, None, "1", [], 1.0, 1.5, -(2**31) - 1, 2**31)),
        )
        for value in values
    ],
)
def test_invalid_components_are_rejected_on_every_axis(cls, value, axis):
    components = [0, 0, 0]
    components[axis] = value
    with pytest.raises((TypeError, ValueError)):
        cls(*components)


@pytest.mark.parametrize("operation", [operator.add, operator.sub])
@pytest.mark.parametrize(
    "left, right",
    [
        (Vec3(1, 2, 3), BlockPos(1, 2, 3)),
        (BlockPos(1, 2, 3), Vec3(1, 2, 3)),
        (Vec3(1, 2, 3), (1, 2, 3)),
        (BlockPos(1, 2, 3), 1),
    ],
)
def test_types_and_invalid_scales_cannot_be_mixed(operation, left, right):
    with pytest.raises(TypeError):
        operation(left, right)


@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize(
    "value, scale",
    [
        (value, scale)
        for value, scales in (
            (Vec3(1, 2, 3), (True, "2", BlockPos(1, 2, 3))),
            (BlockPos(1, 2, 3), (True, 1.0, 1.5, Vec3(1, 2, 3))),
        )
        for scale in scales
    ],
)
def test_invalid_scales_are_rejected(value, scale, reverse):
    with pytest.raises(TypeError):
        operator.mul(scale, value) if reverse else operator.mul(value, scale)


@pytest.mark.parametrize("scale", [float("inf"), float("nan"), 10**1000])
def test_non_finite_scales_are_rejected(scale):
    with pytest.raises(ValueError):
        Vec3(1, 2, 3) * scale


@pytest.mark.parametrize(
    "operation",
    [
        pytest.param(
            lambda: BlockPos(2**31 - 1, 0, 0) + BlockPos(1, 0, 0), id="block-add"
        ),
        pytest.param(
            lambda: BlockPos(-(2**31), 0, 0) - BlockPos(1, 0, 0), id="block-subtract"
        ),
        pytest.param(lambda: BlockPos(2**31 - 1, 0, 0) * 2, id="block-multiply"),
        pytest.param(lambda: -BlockPos(-(2**31), 0, 0), id="block-negate"),
        pytest.param(lambda: Vec3(2**31, 0, 0).to_block_pos(), id="floor"),
        pytest.param(lambda: Vec3(1e308, 0, 0) + Vec3(1e308, 0, 0), id="vector-add"),
        pytest.param(
            lambda: Vec3(1e308, 0, 0) - Vec3(-1e308, 0, 0), id="vector-subtract"
        ),
        pytest.param(lambda: Vec3(1e308, 0, 0) * 2, id="vector-multiply"),
    ],
)
def test_arithmetic_and_conversion_enforce_component_bounds(operation):
    assert BlockPos(-(2**31), 2**31 - 1, 0) == BlockPos(-(2**31), 2**31 - 1, 0)
    with pytest.raises(ValueError):
        operation()
