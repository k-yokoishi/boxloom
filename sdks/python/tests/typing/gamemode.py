"""Static checks for game mode inputs and results; never executed."""

from typing_extensions import assert_type

from boxloom import BoxloomClient, GameMode, PlayerGameMode, set_player_gamemode


def check_game_modes(client: BoxloomClient, mode: GameMode) -> None:
    assert_type(set_player_gamemode("Steve", "creative"), PlayerGameMode)
    assert_type(client.set_player_gamemode("Steve", "survival"), PlayerGameMode)
    assert_type(set_player_gamemode("Steve", mode).mode, GameMode)
    assert_type(client.set_player_gamemode("Steve", mode).mode, GameMode)
    set_player_gamemode("Steve", "spectator")  # type: ignore[arg-type]
    client.set_player_gamemode("Steve", "invalid")  # type: ignore[arg-type]
