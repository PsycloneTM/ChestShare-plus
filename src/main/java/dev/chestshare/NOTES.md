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

- **Compat branch** (Sophisticated Storage etc.): two sub-paths, gated differently:
  - **`freshlyGenerated` sub-path** (existing): if the chunk was just world-generated,
    any recognized compat container is registered directly. Same reasoning as before:
    no per-container loot-table signal exists for these, so chunk freshness is the
    only available origin signal.
  - **Fingerprint sub-path** (new): if the chunk already existed (old chunk, or any
    chunk loaded after a pre-Chunky scan, i.e. the real scenario for your trainer-camp
    structures) AND `FingerprintRegistry` has been built (`SERVER_STARTED` has fired),
    check whether this specific container's current contents *exactly* match a known,
    structure-baked fingerprint from any registered structure template. If they match
    AND `StructureManager.getStructureWithPieceAt(pos, h -> true)` confirms the
    position is inside some registered structure's bounding box, register it. Both
    conditions must pass simultaneously; either alone isn't enough. See
    `compat/NOTES.md` for the known limitation (a player building inside a structure's
    bounding box with contents that happen to exactly match a known fingerprint) and why
    it's accepted as an acceptable residual risk vs. the alternative (no support at all).
- **Vanilla branch** (not gated on `freshlyGenerated`)...
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

## StructureContainerRestorer.java

The passive counterpart of `/chestshare adopt-structure`, for exactly one case: an **empty**
container standing at the exact position, and being the exact block, that a registered structure
template places a storage block. `ContainerScanner` calls it for chunks that already existed (a
freshly generated chunk registers everything through its own branches), from two places - the
compat branch, after the fingerprint attempt, and the randomizable branch when the container has
no loot table left - with a `restoreTried` flag so a modded loot container reachable from both
only pays for the structure lookup once.

**Why "empty" is only safe here because of the position check.** An empty container proves
nothing about where it came from: it could be looted world-gen loot (what this restores), a
player's own storage, or something an admin cleared on purpose. The fingerprint path is safe
because exact original contents mean nobody has touched the container; this path has no such
signal, so every bit of the safety comes from the template lookup, and the lookup is therefore
deliberately narrower than adopt-structure's:

- Only a jigsaw piece whose template id the game reports (`StructurePlacement.resolveTemplateId`).
  The layout-matching and content-alignment fallbacks are not used - adopt-structure can be told to
  look at an ambiguous structure, a chunk-load scan cannot.
- The template block, moved through the piece's rotation and origin, must land on this exact
  position, and exactly one piece/block may claim it. Two claims means the placement isn't
  understood well enough to act on.
- The template block's id must equal the block standing here (`StructureStorageBlock.blockId`), and
  it must carry baked items or a loot table (a decorative container the template places empty is
  never filled).
- Templates with more than one palette are skipped (`StructureStorageBlock.multiPalette`): the
  registry records only the first palette, and another palette can place a different block or
  different contents at the same position.
- Only chests, barrels, shulker boxes and supported modded containers - never hoppers, dispensers,
  droppers or furnaces, even though several of those are `RandomizableContainerBlockEntity` too.

**It never reads another chunk's block entities.** adopt-structure validates a resolved placement
by looking at every storage position of the template in the world (`hits > 0`); `Level#getBlockEntity`
on a neighbouring chunk that isn't loaded would load it, synchronously, from inside the scan-draining
tick. The passive path instead treats "the block entity in front of me, at exactly the position the
template says, with the block id the template says" as its validation. The structure lookup itself
(`getStructureWithPieceAt`) can still make the game read structure starts, exactly as the fingerprint
path already does.

**`isEmpty()` order matters.** `RandomizableContainerBlockEntity#isEmpty()` unpacks a pending loot
table, which would roll and destroy the very template the container is about to be registered from.
`isEmptyWithoutLootTable` checks `getLootTable() == null` first and relies on it short-circuiting.

**Known limitation.** A player who uses a structure's own storage block as personal storage will see
it refilled and made per-player if it happens to be empty when its chunk loads. Nothing is lost - it
is empty at that moment - but what the chest *is* changes. Position matching cannot rule this out,
which is why the feature is off by default.

## ChestShareConfig.java

One setting, `restoreEmptyStructureContainers`, default **false**: the passive restore refills and
converts containers in chunks that existed before the mod was installed on nothing more than "empty,
at the right position", and 0.3.1's critical fix was the mod touching storage it had no business
touching. To change the default, flip `DEFAULT_RESTORE_EMPTY`.

Set via `/chestshare toggle restore-empty-structures <true|false>`, not a file to hand-edit - a
runtime toggle is more discoverable than a properties file an admin has to know exists, and applies
immediately with no restart. The chosen value is still persisted, to a tiny one-line state file
(`config/chestshare-plus.state`) written only when the command changes it - never on every boot, and
never meant to be hand-edited. `load()` reads that file once in `onInitialize`; a missing, unreadable
or malformed file falls back to the default and logs why, the same as the old properties-file version
did. This preserves the original design's "an admin has to deliberately choose to enable this" safety
posture while removing the friction of finding and editing a file, and (per the choice this was built
around) letting a chosen value survive a restart instead of resetting to off every session.
