import operator
import unittest
from dataclasses import FrozenInstanceError

from boxloom import BlockPos, Vec3


class CoordinatesTest(unittest.TestCase):
    def test_vec3_arithmetic_preserves_inputs(self):
        origin = Vec3(1.5, -2, 3)
        offset = Vec3(-0.5, 4, 2.25)
        self.assertEqual(Vec3(1, 2, 5.25), origin + offset)
        self.assertEqual(Vec3(2, -6, 0.75), origin - offset)
        self.assertEqual(Vec3(-1.5, 2, -3), -origin)
        self.assertEqual(Vec3(3, -4, 6), origin * 2)
        self.assertEqual(Vec3(0.75, -1, 1.5), 0.5 * origin)
        self.assertEqual(Vec3(1.5, -2, 3), origin)
        self.assertIs(type(origin.y), float)

    def test_block_arithmetic_keeps_integer_components(self):
        origin = BlockPos(2, 64, -3)
        offset = BlockPos(-1, -1, 4)
        self.assertEqual(BlockPos(1, 63, 1), origin + offset)
        self.assertEqual(BlockPos(3, 65, -7), origin - offset)
        self.assertEqual(BlockPos(-2, -64, 3), -origin)
        self.assertEqual(BlockPos(-4, -128, 6), origin * -2)
        self.assertEqual(BlockPos(6, 192, -9), 3 * origin)
        self.assertEqual(BlockPos(2, 64, -3), origin)
        self.assertIs(type((origin * 2).x), int)

    def test_coordinate_values_are_immutable_and_hashable(self):
        for value in (Vec3(1, 2, 3), BlockPos(1, 2, 3)):
            with self.subTest(value=value):
                with self.assertRaises(FrozenInstanceError):
                    value.x = 10
                self.assertEqual("stored", {value: "stored"}[type(value)(1, 2, 3)])
                self.assertEqual(1, len({value, type(value)(1, 2, 3)}))
        self.assertNotEqual(Vec3(1, 2, 3), BlockPos(1, 2, 3))

    def test_conversions_floor_negatives_and_preserve_block_corners(self):
        for position, expected in (
            (Vec3(-0.2, 64.8, 3.9), BlockPos(-1, 64, 3)),
            (Vec3(-1, -0.0, -1.01), BlockPos(-1, 0, -2)),
            (Vec3(0, 0, 0), BlockPos(0, 0, 0)),
        ):
            with self.subTest(position=position):
                self.assertEqual(expected, position.to_block_pos())
        corner = BlockPos(-1, 64, 3).to_vec3()
        self.assertEqual(Vec3(-1, 64, 3), corner)
        self.assertEqual(Vec3(-0.5, 64.5, 3.5), corner + Vec3(0.5, 0.5, 0.5))
        self.assertEqual(BlockPos(-1, 64, 3), corner.to_block_pos())

    def test_invalid_components_are_rejected_on_every_axis(self):
        for cls, values in (
            (Vec3, (True, None, "1", [], float("nan"), float("inf"), -float("inf"), 10**1000)),
            (BlockPos, (True, None, "1", [], 1.0, 1.5, -(2**31) - 1, 2**31)),
        ):
            for axis in range(3):
                for value in values:
                    components = [0, 0, 0]
                    components[axis] = value
                    with self.subTest(cls=cls, axis=axis, value=value):
                        with self.assertRaises((TypeError, ValueError)):
                            cls(*components)

    def test_types_and_invalid_scales_cannot_be_mixed(self):
        vector = Vec3(1, 2, 3)
        block = BlockPos(1, 2, 3)
        for left, right in ((vector, block), (block, vector), (vector, (1, 2, 3)), (block, 1)):
            for operation in (operator.add, operator.sub):
                with self.subTest(left=left, right=right, operation=operation):
                    with self.assertRaises(TypeError):
                        operation(left, right)
        for value, scales in ((vector, (True, "2", block)), (block, (True, 1.0, 1.5, vector))):
            for scale in scales:
                for left, right in ((value, scale), (scale, value)):
                    with self.subTest(left=left, right=right):
                        with self.assertRaises(TypeError):
                            operator.mul(left, right)
        for scale in (float("inf"), float("nan"), 10**1000):
            with self.subTest(scale=scale), self.assertRaises(ValueError):
                vector * scale

    def test_arithmetic_and_conversion_enforce_component_bounds(self):
        minimum, maximum = -(2**31), 2**31 - 1
        self.assertEqual(BlockPos(minimum, maximum, 0), BlockPos(minimum, maximum, 0))
        for operation in (
            lambda: BlockPos(maximum, 0, 0) + BlockPos(1, 0, 0),
            lambda: BlockPos(minimum, 0, 0) - BlockPos(1, 0, 0),
            lambda: BlockPos(maximum, 0, 0) * 2,
            lambda: -BlockPos(minimum, 0, 0),
            lambda: Vec3(2**31, 0, 0).to_block_pos(),
            lambda: Vec3(1e308, 0, 0) + Vec3(1e308, 0, 0),
            lambda: Vec3(1e308, 0, 0) - Vec3(-1e308, 0, 0),
            lambda: Vec3(1e308, 0, 0) * 2,
        ):
            with self.subTest(operation=operation), self.assertRaises(ValueError):
                operation()
