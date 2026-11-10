import json
import runpy
from pathlib import Path

import pytest

from boxloom import blocks, entities, items

SDK_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def generator():
    return runpy.run_path(str(SDK_ROOT / "scripts/generate_registry_constants.py"))


@pytest.fixture
def snapshot():
    return {
        "minecraft_version": "test-version",
        "blocks": ["minecraft:stone"],
        "entities": ["minecraft:zombie"],
        "items": ["minecraft:bone_meal"],
    }


@pytest.mark.parametrize(
    "registry, module", [("blocks", blocks), ("entities", entities), ("items", items)]
)
def test_modules_cover_the_versioned_registry_snapshot(registry, module):
    snapshot = json.loads(
        (SDK_ROOT.parent.parent / "data/minecraft/registries.json").read_text(
            encoding="utf-8"
        )
    )
    assert module.MINECRAFT_VERSION == snapshot["minecraft_version"]
    values = [
        value
        for name, value in vars(module).items()
        if name.isupper() and name != "MINECRAFT_VERSION"
    ]
    assert all(type(value) is str for value in values)
    assert len(values) == len(snapshot[registry])
    assert set(values) == set(snapshot[registry])


def test_generation_is_independent_of_registry_order(generator):
    snapshot = {
        "minecraft_version": "test-version",
        "blocks": ["minecraft:stone", "minecraft:diamond_block"],
        "entities": ["minecraft:zombie", "minecraft:pig"],
        "items": ["minecraft:bone_meal", "minecraft:diamond_sword"],
    }
    render = generator["render_modules"]
    first = render(snapshot, "test-version")
    for registry in ("blocks", "entities", "items"):
        snapshot[registry].reverse()
    assert render(snapshot, "test-version") == first


@pytest.mark.parametrize(
    "change",
    (
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
    ),
)
def test_invalid_inputs_fail_before_producing_modules(generator, snapshot, change):
    with pytest.raises(ValueError):
        generator["render_modules"]({**snapshot, **change}, "test-version")


def test_missing_item_registry_is_rejected(generator, snapshot):
    del snapshot["items"]
    with pytest.raises(ValueError):
        generator["render_modules"](snapshot, "test-version")
