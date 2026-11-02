# Changelog

## Unreleased

- Receive typed Python event callbacks in the background with `subscribe_event()`
  and explicit readiness, cooperative shutdown, and error propagation
- Stream confirmed player deaths with the death message and location, and consume
  chat and death events through the Python SDK's generic `watch_event()` API
- List loaded entities through REST and the Python SDK, with type and sphere
  filters, distance sorting, and a result limit; return position, rotation, name,
  and health

## 0.1.0-alpha.4

- REST と Python SDK から UUID 指定、または種類と任意の球形範囲指定でエンティティを kill できる。
- ワールド時刻の取得・設定・加算と、組み込み・カスタムの時刻マーカーへの移動に対応する。

## 0.1.0-alpha.3

- Support Minecraft Java Edition 26.3 with updated Fabric dependencies

## 0.1.0-alpha.2

- Fill an inclusive region containing up to 32,768 blocks
- Read the block ID at one position in a loaded dimension
- Stream player chat messages through a resumable Server-Sent Events API
- Teleport connected players to absolute coordinates with optional dimension and rotation

## 0.1.0-alpha.1

Initial public alpha release.

- Broadcast a system message to connected players
- List connected players
- Read a connected player's position and look direction
- Set one block in a loaded dimension
- Summon one entity with optional NBT in a loaded dimension
- Support Fabric dedicated servers and integrated singleplayer servers
- Configure the listener through `config/boxloom.json` or environment variables
- Allow unauthenticated loopback use and optional Bearer authentication
