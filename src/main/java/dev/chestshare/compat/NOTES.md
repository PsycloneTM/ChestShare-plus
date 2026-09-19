# dev.chestshare.compat — package notes

## ContainerCompatibility.java

This class reflectively bridges storage mods whose block entities don't implement
vanilla `Container` (Sophisticated Storage, CobbleFurnies, etc.) into the same
`CompatInventory` shape ChestShare uses everywhere else. Most of the complexity
here is not incidental — it's the result of three earlier, failed attempts at the
Sophisticated Storage lookup specifically, each of which failed *silently*. This
file documents that history so nobody re-introduces the same mistakes.

### `register()` — success logging

Success previously had no log line at all, meaning even a fully working
reflective lookup gave no positive confirmation, only the absence of a warning.
The `LOGGER.info("[ChestShare] compat-registered ...")` line exists so a working
run is distinguishable from one that silently never reached this code path.

### `findHandler()` — Sophisticated Storage lookup

Path: `StorageBlockEntity -> getStorageWrapper() -> getInventoryHandler()`, where
the returned `net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler` extends
`io.github.fabricators_of_create.porting_lib.transfer.item.ItemStackHandler` —
confirmed by extracting the actual shaded class out of sophisticatedcore's own
jar-in-jar libs (`META-INF/jars/transfer-*.jar`) rather than assumed from Forge's
API shape.

**Important, and the reason this kept silently breaking:** this is NOT a 1:1 port
of Forge's `IItemHandler`. That interface's `getSlots()` returns `int`, but on
this class `getSlots()` is a *different* method that returns
`List<ItemStackHandlerSlot>`. The real int-returning slot-count method here is
`getSlotCount()`.

Three earlier fix attempts all picked a slot-count method purely by **name**, with
no check on the return type:
- **0.3.1** guessed `getNumberOfInventorySlots` (wrong name for this class).
- **0.3.2** guessed `getSlotCount`, but the superclass walk needed to reach it
  wasn't in place yet.
- **0.3.3** added that walk but put `"getSlots"` first in the candidate list —
  which now resolves (unlike the first two attempts) but hands back a `List`, so
  `v instanceof Number` was false and slots silently stayed `0` every time, with
  the only trace a `warnOnce` log line nobody was watching for.

**The fix:** invoke each candidate name in turn and only accept the first one
whose return value is actually a `Number`, so a name that resolves to the wrong
type is skipped instead of silently accepted. See the `slots <= 0` fallback loop
over `{"getSlotCount", "getSlots", "getNumberOfInventorySlots", "size"}`.

**Primary approach**, tried before that fallback loop: ask the storage wrapper
itself for its slot count via `getNumberOfInventorySlots()`. Confirmed (via a
working mod that reflects into the same Sophisticated Storage API) to be a plain
public no-arg `int` method directly on `StorageWrapper` — no need to dig into the
`InventoryHandler`/`ItemStackHandler` internals at all for this. Earlier
ChestShare versions only checked the `IStorageWrapper` *interface*, which indeed
has no slot-count method, and missed that the concrete `StorageWrapper` class
declares one directly.

**Every branch in this lookup now logs its own miss on the way to `null`.**
Previously all of these fell through completely silently: exceptions were
discarded with just a comment, and a `null` at any stage before the final `inv`
produced no `warnOnce` call at all — which is why nothing showed up in
`latest.log` even while the whole lookup was failing.

The outer `try/catch` around the whole Sophisticated Storage block also logs
(rather than silently swallowing): optional compatibility must never prevent
server startup, but a swallowed-and-uncommented exception here is exactly what
hid the original `setAccessible` bug for as long as it was hidden.

The isolated `try/catch` around each slot-count invoke attempt (inside the
fallback loop) is deliberate: a failure there must not propagate up into
`findHandler`'s top-level catch, which would silently drop the whole lookup
instead of trying the next candidate name.

### `findMethod(Class<?>, int paramCount, String... names)`

Like the simpler `findMethod` overload, but only matches methods with the given
parameter count, to avoid grabbing the wrong overload when a class reuses a name
(e.g. a 0-arg `size()` vs a 1-arg `size(int)`). Tries the public API first
(`Class.getMethods()` already walks the full inheritance chain including
interfaces — the common case for methods like `getSlots()` declared on a
library superclass), then falls back to walking declared methods for
non-public/package-private members.

**`setAccessible` is required even for methods found via `getMethods()`:**
`Class.getMethods()` walks inherited *public* methods even when the *declaring*
class is package-private (e.g. a library's internal `ItemStackHandler` base class
sitting under the public `InventoryHandler` subclass). Method objects obtained
this way still fail `invoke()` with `IllegalAccessException` unless
`setAccessible` is called explicitly — the JVM checks the declaring class's
accessibility, not just the method's own public modifier. Without this,
`getSlots()` (inherited, not declared on `InventoryHandler` itself) was found but
every `invoke()` threw, and that exception propagated up into `findHandler`'s
top-level catch, silently discarding the whole Sophisticated Storage lookup with
nothing logged.

### Other notes

- `WARNED_CLASSES` / `warnOnce`: avoids log spam by only warning once per
  block-entity class per session.
- Get/set method lookups (`getStackInSlot`/`getSlotStack`/`getStack`/`getItem`
  and setters) are arity-checked so the code never accidentally binds to an
  unrelated overload of the same name.
- CobbleFurnies branch: keeps its inventory in the inherited protected method
  `method_11282()` / `getItems()` (intermediary vs. named mapping).
- Generic modded `Container` branch: last-resort fallback for other inventory
  block entities that do implement vanilla `Container` but weren't caught by
  `getContainerInventory`'s direct check (defensive, kept simple on purpose).

## FingerprintRegistry.java

Scans every `.nbt` structure template registered on this server at `SERVER_STARTED`
time (via `ResourceManager#listResources("structure", ...)` + `NbtIo#readCompressed`),
and builds a `Set<Set<FingerprintItem>>` of every distinct non-empty item-set found
baked into a Sophisticated Storage or CobbleFurnies block entity's NBT.

**`CompoundTag#getList(key, type)` returns an EMPTY list on an element-type mismatch — this once
made the whole registry silently empty.** Structure NBT's `blocks` and `palette` are lists of
compounds (type 10); only `palettes` (a list of palettes) is a list of lists (type 9). The scanner
originally asked for type 9 on `blocks` and `palette`, so `getList` returned nothing, every structure
recorded zero storage blocks, and every downstream feature that depends on the registry (fingerprint
auto-share, `adopt-structure` by id / layout / content alignment) had nothing to work with. The scan
log line still looked healthy ("N structures scanned"), which is why it went unnoticed; it now also
warns if structures were read but no storage blocks were recorded. Always match `getList`'s type
argument to the ELEMENT type, not "list".

**The scan records three kinds of storage block, not one.** Originally it recorded only blocks whose
id matched `sophisticatedstorage:` / `cobblefurnies:`, which silently excluded every vanilla-style
loot container - vanilla chests and barrels, and Cobblemon's gilded chest among modded ones. Those
bake no items into the template at all, only a `LootTable` string, so they are invisible both to the
id filter and to any items-based check. A block is now recorded when it carries a baked `LootTable`
(any namespace - that tag alone is a definitive world-gen-loot signal), when its id is SS/CF as
before, or when its id looks like a container in any namespace. `StructureStorageBlock` carries the
loot table and seed alongside the items, and only a block with neither is "empty in the template".
See `command/NOTES.md` for what `adopt-structure` then does with each kind, and keep this filter in
agreement with `ChestShareCommands#isAdoptableContainer` - a recorded template position that no live
container can ever satisfy weakens both the layout matcher and the content aligner.

**`FingerprintItem` carries `slot`, not just `id`/`count` — and the fingerprint set is
a `Set<Set<...>>`, not `Set<List<...>>`.** Both mods save `Items` as a sparse,
non-sequential list: real examples pulled from COBBLEVERSE's `team_rocket_tower.nbt`
had slots `0, 3, 5, 7` in one barrel and `0, 22, 8, 10` — not even ascending — in
another. An earlier version of this class dropped `Slot` entirely and matched/restored
items by list position, which (a) let `registerWithItems` scramble every baked item
into the wrong physical slot, and (b) made fingerprint matching order-sensitive to an
NBT list order that doesn't reliably track slot order in the first place. Comparing as
unordered `Set<FingerprintItem>` (slot+id+count) fixes both: restoration places each
item at its actual recorded slot, and two structurally identical containers match
regardless of what order their NBT happened to serialize in.

**Why raw NBT instead of `StructureTemplate`/`StructureTemplateManager` objects?**
`StructureTemplate`'s internal palette list has no confirmed public Mojmap accessor
in 1.21.1 (the field `palettes` is private, and the method summary shows no public
getter for it — vanilla only ever accesses it internally via
`StructurePlaceSettings#getRandomPalette`). Using `NbtIo#readCompressed(InputStream,
NbtAccounter)` directly reads the same data from the resource stream without needing
any internal API, and the parsing logic was validated against 3,686 real structure
files from COBBLEVERSE's actual datapacks, matching a ground-truth fingerprint
database at 68/68 structures.

**Why content-only matching in `matchesKnownFingerprint` (no position)?** This part is
still true for that specific method: it exists as a cheap, structure-independent
signal (e.g. for `ContainerScanner`'s passive discovery path), and reconstructing a
full placement transform there would require knowing which structure a given world
position belongs to in the first place, not just whether its contents look
structure-baked. **This is no longer the only tool available, though** — see
`command/NOTES.md`'s `adopt-structure` entry: `PoolElementStructurePiece` does expose
enough (`getPosition()`, `getRotation()`) to do exact position + rotation transforms
for single-piece jigsaw structures, which is what `adoptStructure` uses instead of
content-only matching. Content-only matching remains the right tool specifically when
you don't already know which structure (if any) you're standing in.

**`storageWrapper.renderInfo` is deliberately never read.** That's Sophisticated
Storage's external display-item metadata (what floats on the outside of the block),
not real inventory contents. An earlier offline extraction pass for the ground-truth
JSON mistakenly included it, inflating a small number of "limited barrel"
fingerprints by one phantom item — verified by comparing the extraction against the
actual live container API. The runtime match against `CompatInventory.get(slot)` is
also clean (it only reads real inventory slots), so both sides of the comparison are
correct.

**Known limitation.** Content-only matching (`matchesKnownFingerprint`) cannot rule
out a player who: (a) builds their own Sophisticated Storage inside a generated
structure's bounding box AND (b) happens to store the exact same items, in the exact
same slots, as a known fingerprint. Accepted as residual risk — far narrower than the
pre-0.3.1 bug, and `adopt-structure`'s position-based path (see `command/NOTES.md`)
doesn't share this weakness at all, since it never relies on contents to decide
*where* to look.

## ContainerFingerprintFilter.java

Simple block-id filter used only at fingerprint-scan time to identify which palette
entries in a structure template are Sophisticated Storage or CobbleFurnies storage
blocks (as opposed to CobbleFurnies decorative furniture with no inventory). The
live-world path in `ContainerCompatibility` identifies real containers by their
actual block entity class/API shape instead, which is a stronger signal at runtime.

## StructurePlacement.java

`resolveTemplateId(PoolElementStructurePiece)` / `parseTemplateId(String)`, moved out of
`ChestShareCommands` so `adopt-structure` and `StructureContainerRestorer` agree on which template a
piece came from. Behavior is unchanged (Either-typed field by reflection, then the first
`namespace:path` token of `toString()`); the only addition is a per-class cache of the reflected
fields, because the passive scan asks for the same few element classes over and over.

## StructureStorageBlock: `blockId` and `multiPalette`

Added for the passive restore. `blockId` is the block the template places at that position (first
palette), so the restore can require the live block to be the same block. `multiPalette` is true when
the template has more than one palette: only the first is recorded, and the others may place a
different block or different baked contents at the same position, so a multi-palette block is left to
`adopt-structure`. Neither field takes part in `adopt-structure`'s "are these two layouts
interchangeable" test (`sameContents` compares positions, items and loot tables only), so its
ambiguity behavior is exactly what it was.
