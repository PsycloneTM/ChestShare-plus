# ChestShare+

Per-player instanced loot for world-generated containers — 100% server-side.

**This is a fork of [ChestShare](https://modrinth.com/mod/chestshare) by
[Calamech](https://modrinth.com/user/Calamech)**, redistributed under the
terms of Calamech's original [MIT license](LICENSE). All credit for the
original concept, design, and implementation goes to Calamech — this fork
builds on that foundation with additional container compatibility and a
rewritten async import system, described below.

If you don't need any of the changes below, use the original
[ChestShare](https://modrinth.com/mod/chestshare) instead.

## What's different from ChestShare 0.2.3

### New

- **Modded/reflective container support** — containers from mods that don't
  implement vanilla `Container` (e.g. Sophisticated Storage barrels/chests)
  are now detected and shared via reflection, alongside vanilla chests,
  barrels, shulker boxes, and chest minecarts. CobbleFurnies storage is also
  supported directly.
- **Asynchronous, cancellable import** — `/chestshare import <file>` no
  longer blocks the server thread. Chunks are requested and processed a few
  at a time across ticks (bounded by resident chunk count and free memory),
  with an optional `parallel <N>` throttle and a `force` flag to convert
  occupied containers. `/chestshare import status` and
  `/chestshare import cancel` are available while a job is running.
- **Passive world-gen safety gate** — auto-registration of modded/generic
  containers (which have no "untouched loot table" signal to check) only
  ever runs on chunks confirmed to be freshly world-generated, never on a
  chunk that merely loads for the first time under this mod. This means
  installing the mod on an existing world will never sweep up a player's
  own already-placed modded storage.

## Commands (op level 2)

| Command                                        | Effect                                                                      |
| ----------------------------------------------- | ---------------------------------------------------------------------------- |
| `/chestshare convert <pos> <loot_table>`        | Convert one container into a shared container with the given loot table    |
| `/chestshare reset <pos>`                       | Clear cached player rolls — loot regenerates for everyone next open        |
| `/chestshare export <file>`                     | Export all captured containers to a file                                   |
| `/chestshare import <file> [force] [parallel N]`| Apply an export to this world, in the background (recovery)                |
| `/chestshare import status`                     | Show progress of a running import (only visible while one is active)       |
| `/chestshare import cancel`                     | Cancel a running import (only visible while one is active)                 |

## Good to know

- Fabric, Minecraft 1.21.1, Java 21. Requires Fabric API. Server-side only
  (also works in singleplayer/LAN).
- Built against official Mojang mappings.
- Chunks generated before install: unopened vanilla containers still
  convert automatically on first open (their loot table is intact);
  already-looted ones need the export/import recovery workflow, which
  requires a second copy of the world (same seed, same mods) to
  pre-generate and capture the original loot from.
- `export` only ever writes containers this mod has already registered —
  a chunk merely being *loaded* doesn't register anything by itself. A
  fresh chunk-generation event, or a player opening the container, does.
- Container support covers: vanilla chests, barrels, shulker boxes, and chest
  minecarts; any modded block entity that implements vanilla's `Container`
  interface — this covers a lot of storage mods for free, since many
  decorative/simple storage mods (e.g. Carved Wood's chests and barrels)
  build directly on vanilla's own container implementation; and, by name,
  Sophisticated Storage and CobbleFurnies specifically, which don't
  implement `Container` and are supported via reflection instead. A mod
  whose storage is fully custom — neither `Container`-based nor one of
  those two named integrations — isn't covered, and would need its own
  dedicated support the way Sophisticated Storage and CobbleFurnies got.
- Verified against the official [COBBLEVERSE](https://modrinth.com/modpack/cobbleverse)
  modpack's world-generated storage: Sophisticated Storage, Sophisticated
  Core, and CobbleFurnies are supported by name, and Carved Wood is covered
  generically via `Container`.

## License
 
MIT — see [LICENSE](LICENSE). Original work Copyright (c) 2026 Calamech.
 
