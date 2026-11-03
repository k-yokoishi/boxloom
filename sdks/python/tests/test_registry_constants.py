import json
from pathlib import Path
import runpy
import unittest

from boxloom import blocks, entities


SDK_ROOT = Path(__file__).resolve().parents[1]


class RegistryConstantsTest(unittest.TestCase):
    def test_modules_cover_the_versioned_registry_snapshot(self):
        snapshot = json.loads(
            (SDK_ROOT.parent.parent / "data/minecraft/registries.json").read_text(encoding="utf-8")
        )
        for registry, module in (("blocks", blocks), ("entities", entities)):
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
        }
        first = self.render(snapshot)
        snapshot["blocks"].reverse()
        snapshot["entities"].reverse()
        self.assertEqual(first, self.render(snapshot))

    def test_invalid_inputs_fail_before_producing_modules(self):
        valid = {
            "minecraft_version": "test-version",
            "blocks": ["minecraft:stone"],
            "entities": ["minecraft:zombie"],
        }
        for change in (
            {"minecraft_version": "other-version"},
            {"blocks": []},
            {"entities": "minecraft:zombie"},
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
