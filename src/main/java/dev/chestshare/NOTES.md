# dev.chestshare — package notes

Reference doc for the non-obvious "why" behind this package's code. Comments were
stripped from the source to keep it close to the original 0.2.3 style; this file
is where that context lives instead.

## ChestShare.java

**`FRESHLY_GENERATED_CHUNKS`** — positions of chunks Fabric has told us are brand
new (never existed before this moment), via `CHUNK_GENERATE`. Only membership in
this set can make a chunk eligible for the compat/generic-modded auto-registration
branches in `ContainerScanner`. A chunk that already existed (and so could already
contain a player's own storage) never enters this set no matter how many times it's
loaded afterward. This is what guarantees that adding the mod to an existing server
never sweeps up a player's already-placed modded chests just because their chunk
happens to load for the first time under the new mod version.

**`CHUNK_GENERATE` registration** — only ever records the position here; no block
entity or NBT access happens in this callback. The actual scan still happens
exclusively via the deferred `CHUNK_LOAD` queue, on the normal server tick, for
C2ME/async-generation-safety reasons. This callback just lets that deferred scan
know whether the chunk it's about to look at could possibly contain a player build
(already existed) or definitely can't (just generated).

**`END_SERVER_TICK` scan-draining loop** — the budget (`Math.max(4, Math.min(backlog, 200))`)
is a small fixed size to keep normal play responsive without a hitch, but scales up
when a backlog builds up (chunk pregenerators, elytra/rail travel, teleporting into
unexplored terrain) so it actually drains instead of trickling in at a fixed rate
regardless of queue size.

Before scanning a dequeued chunk, it re-checks that the exact same chunk object is
still the currently-loaded one for that position — if it's been unloaded/reloaded
since being queued, skip it rather than scanning stale/wrong state.

**Reflective compat branch in `UseBlockCallback`** — compat containers (Sophisticated
Storage, etc.) don't implement vanilla `Container`, so registering here and then
just returning `PASS` would empty the block's real inventory and leave it to chance
whether the block's own click-handling later calls `ServerPlayer#openMenu(...)` in
a way `ServerPlayerEntityMixin` can catch. Instead, register AND open ChestShare's
shared menu directly, in the same step, then cancel the event so the block's own
(now-empty) menu never opens underneath it.

## ContainerScanner.java

`scanChunk`'s `freshlyGenerated` parameter is `true` only when this exact chunk was
just created by world-gen (reported via `ServerChunkEvents.CHUNK_GENERATE`), as
opposed to an ordinary load of a chunk that already existed.

Modded/generic containers have no equivalent of vanilla's "untouched loot table"
signal, so passive auto-registration for them is only safe to run on a chunk that
could not possibly contain a player build yet — hence every modded/generic branch
in this file is gated on `freshlyGenerated`.

- **Compat branch** (Sophisticated Storage etc., gated `freshlyGenerated`): explicit
  compatibility gets first chance, ahead of the vanilla check below. This matters
  for storage mods whose block entities also inherit `RandomizableContainerBlockEntity`
  (e.g. CobbleFurnies) — without checking compat first, they'd fall into the vanilla
  branch and be judged by the wrong signal.
- **Vanilla branch** (not gated on `freshlyGenerated`): only ever auto-registers a
  vanilla container that still carries its original, un-popped loot table reference
  — the same real signal `resolveBlockEntry()` uses on right-click. Without this
  check, the non-loot-table fallback would steal the current contents of ANY
  unshared vanilla chest/barrel/shulker box the moment its chunk loads, including a
  player's own already-used storage. That fallback exists for the explicit
  `/chestshare convert` command and mirror-world capture, not passive scanning.
- **Generic vanilla-style modded inventory branch** (gated `freshlyGenerated`): same
  reasoning as the compat branch — no untouched-loot-table signal exists, so this
  only ever runs on a chunk that just came out of world-gen.

## ContainerRegistrar.java

`register()` checks the loot table **before** touching individual slots. Reading
slots on some `LootableContainerBlockEntity` implementations can unpack the loot
table, which would destroy the very template this method is trying to save.

`applyTemplate()` clears the loot-table reference **first**, before calling
`setItem()`. Otherwise `setItem()` may cause vanilla to unpack the original loot
table while the container is mid-conversion.
