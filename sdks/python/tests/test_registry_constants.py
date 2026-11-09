import json
from pathlib import Path
import runpy
import unittest

from boxloom import blocks, entities, items


SDK_ROOT = Path(__file__).resolve().parents[1]


class RegistryConstantsTest(unittest.TestCase):
    def test_modules_cover_the_versioned_registry_snapshot(self):
        snapshot = json.loads(
            (SDK_ROOT.parent.parent / "data/minecraft/registries.json").read_text(encoding="utf-8")
        )
        for registry, module in (("blocks", blocks), ("entities", entities), ("items", items)):
            with self.subTest(registry=registry):
                self.assertEqual(snapshot["minecraft_version"], module.MINECRAFT_VERSION)
                values = [
                    value for name, value in vars(module).items()
                    if name.isupper() and name != "MINECRAFT_VERSION"
                ]
                self.assertTrue(all(type(value) is str for value in values))
                self.assertEqual(len(snapshot[registry]), len(values))
                self.assertEqual(set(snapshot[registry]), set(values))


class RegistryGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generator = runpy.run_path(str(SDK_ROOT / "scripts/generate_registry_constants.py"))

    def render(self, snapshot):
        return self.generator["render_modules"](snapshot, "test-version")

    def test_generation_is_independent_of_registry_order(self):
        snapshot = {
            "minecraft_version": "test-version",
            "blocks": ["minecraft:stone", "minecraft:diamond_block"],
            "entities": ["minecraft:zombie", "minecraft:pig"],
            "items": ["minecraft:bone_meal", "minecraft:diamond_sword"],
        }
        first = self.render(snapshot)
        snapshot["blocks"].reverse()
        snapshot["entities"].reverse()
        snapshot["items"].reverse()
        self.assertEqual(first, self.render(snapshot))

    def test_invalid_inputs_fail_before_producing_modules(self):
        valid = {
            "minecraft_version": "test-version",
            "blocks": ["minecraft:stone"],
            "entities": ["minecraft:zombie"],
            "items": ["minecraft:bone_meal"],
        }
        for change in (
            {"minecraft_version": "other-version"},
            {"blocks": []},
            {"entities": "minecraft:zombie"},
            {"items": []},
            {"items": "minecraft:bone_meal"},
            {"items": [None]},
            {"items": ["example:custom_item"]},
            {"items": ["minecraft:Bone_Meal"]},
            {"items": ["minecraft:bone_meal", "minecraft:bone_meal"]},
            {"items": ["minecraft:a-b", "minecraft:a_b"]},
            {"items": ["minecraft:minecraft_version"]},
            {"blocks": [None]},
            {"blocks": ["example:stone"]},
            {"blocks": ["minecraft:Stone"]},
            {"blocks": ["minecraft:123"]},
            {"blocks": ["minecraft:stone", "minecraft:stone"]},
            {"blocks": ["minecraft:a-b", "minecraft:a_b"]},
            {"blocks": ["minecraft:minecraft_version"]},
        ):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.render({**valid, **change})
        without_items = {key: value for key, value in valid.items() if key != "items"}
        with self.assertRaises(ValueError):
            self.render(without_items)
