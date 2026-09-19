# dev.chestshare.command — package notes

## ChestShareCommands.java

**`coordString()` exists instead of `BlockPos.toString()`** because in a
production Fabric jar the vanilla classes are loaded under intermediary names, so
`BlockPos`'s own `toString()` (which uses `getClass().getSimpleName()`) prints
`"class_2338{...}"` instead of anything readable. Coordinates are formatted
manually instead.

**`convert()` — seed must be `0L`, never a fresh random value.** Seed `0` tells
`LootTableTemplate.createStacks()` to fall back to the position-derived seed
(`world seed ^ block pos`) instead of a stored one, so converting the same spot
always regenerates the same loot. A random seed here would make every
re-convert of the same block roll different loot — this was a real bug, fixed
once already; don't reintroduce it. Applies to both the vanilla branch
(`c.setLootTableSeed(0L)`) and the modded/compat branch (`LootTableTemplate(id,
0L, ...)`) for the same reason.

**`convert()` — modded/compat branch** has no native loot-table slot to set on
the block entity itself; the template lives entirely in `SharedContainersState`,
and `SharedContainerOpener` serves it on open regardless of block entity type.

**`reset()` means "back to what convert set up."** It clears every player's
cached instance so each of them gets a fresh roll from the same loot
table/seed next time they open it. The container stays shared throughout —
this never touches the block entity or the `SharedMarker` flag, and never
un-converts it back into a player-placed container (that would be a different,
separate operation). This is a deliberate restoration of the original 0.2.3
`reset()` semantics after a later rewrite briefly changed `reset` into an
"un-share" operation, which broke on modded containers with no clean "give it
back" state to restore to.

**`importContainers()` — unresolvable dimension handling.** If an export file
references a dimension that no longer exists on this server, those positions
are folded into `preSkippedMissing`/`skippedMissing` rather than being silently
dropped from every count (as the original 0.2.3 did). This makes the final
summary always account for every position that was in the file. This is a
deliberate, small improvement over the original — not a wording or behavior
regression.

**`importContainers()` — lambda capture of loop counters.** `positionCount` and
`preSkippedShared` are mutated in a loop (`positionCount++`, `preSkippedShared++`)
and then read inside the final `sendSuccess(() -> ...)` lambda. Java requires
lambda-captured locals to be effectively final, so `finalPositionCount`/
`finalPreSkippedShared` copies are taken right after the loop, before the lambda
references them. The original 0.2.3 source has this exact same "mutate in a loop,
then capture" pattern and looks fine there, but that's decompiled bytecode — a
lambda's capture is already resolved at the bytecode level by the time a
decompiler reconstructs it, so it never has to satisfy `javac`'s source-level
effectively-final check. This only became a real compile error once the logic
existed as actual `.java` source. Worth remembering if more original code gets
ported over: a mutated-then-later-captured local compiles fine in the decompiled
reference but needs an explicit final copy in real source.

**Command tree gating on `ImportJob.ACTIVE`.** `import cancel` and `import status`
only appear in tab-complete/are dispatchable while a job is running
(`requires(s -> ImportJob.ACTIVE != null)`); the whole `import <file>...` subtree
(including `parallel` and `force`) only appears while no job is running
(`requires(s -> ImportJob.ACTIVE == null)`). This is a deliberate choice over
letting all of them always show and rely on the handler's own null-check: typing
`/chestshare import <file>` while a job is already active now shows as an
unrecognized/incomplete command rather than the friendlier "An import is already
running" failure message — consistent tab-complete (nothing invalid is ever
offered) was preferred over the friendlier per-command error message.
`requires()` on the `argument("file", ...)` node cascades to everything nested
under it, so `parallel`/`force`/`force`+`parallel` don't need the check repeated.

`importContainers()` still keeps its own internal `ImportJob.ACTIVE != null`
check even though `requires()` makes it unreachable in the normal case — this is
deliberate defense-in-depth against a race between the tree's `requires()`
evaluation and the handler actually running (e.g. two admins executing at
overlapping ticks), not dead code to be cleaned up.

## ImportJob.java

Ported from 0.2.3's async/ticket-based importer, extended to cover modded/compat
containers (Sophisticated Storage etc.) the same way the synchronous `convert`
command already did — 0.2.3 predates that support entirely and only ever
handled vanilla `RandomizableContainerBlockEntity`.

Runs entirely off the server tick (see `ChestShare#onInitialize -> tickActive`),
never blocking the server thread on a synchronous chunk load: chunks are
requested via a region ticket, then polled each tick via their loading future
until ready, processed, and released. `maxInFlightChunks` bounds how many
chunks are requested/pending at once; `admissionPaused` backs off further if
resident chunk count or free heap gets tight, so a huge import can't balloon
memory or stall unrelated chunk generation/unloading.

**`PENDING` sentinel** distinguishes "future not resolved yet" from "resolved to
null" when polling via `CompletableFuture#getNow` — `ChunkResult` has no other
clean way to tell those apart, since a real result is never itself `null`.

**`processChunk` reads `chunk.getBlockEntity(pos)` directly, with no
`getChunkAt`/re-read fallback.** By the time this runs, `pollChunk()` has
already confirmed the chunk's ticking-chunk future resolved, so there's no
partially-initialized state left to force past — unlike the synchronous
convert/click path, where a chunk can still be mid-load and that fallback
actually matters. (This fallback used to be copy-pasted in here too; it was
removed as dead weight once traced through.)

**`pollChunk` must poll `getFullChunkFuture()`, not `getTickingChunkFuture()`.**
The original 0.2.3 source called `method_20725()` on the `ChunkHolder`, which
under Mojmap is `getFullChunkFuture()` — the future for `ChunkStatus.FULL`,
which only requires the target chunk itself to finish generating. A later
version of this port instead called `getTickingChunkFuture()` (`method_16145`),
which is a *stricter* condition: it only resolves once the chunk reaches the
ticking level, which additionally requires a ring of neighboring chunks to also
be sufficiently loaded. For a large import touching many chunks — especially in
not-yet-generated or sparse terrain — waiting on the ticking future instead of
the full-chunk future meant every single chunk effectively waited on its
neighbors too, causing a real, noticeable throughput regression compared to the
original synchronous behavior. `chunk.getBlockEntity(pos)` only needs the chunk
itself to be FULL status; there is no reason to wait for ticking here.

**`skippedMissing` folding** — see the `importContainers()` note above; the
same reasoning applies here for positions whose chunk never loads in time
(`CHUNK_LOAD_TIMEOUT_TICKS`).

**`skippedMissing` now logs why, per position.** There are two distinct ways
a position can end up counted as missing, and both now log at the point they
happen instead of only contributing to a bare final count:
- The whole chunk never finished loading within `CHUNK_LOAD_TIMEOUT_TICKS`
  (30s) — logged once per chunk, at `WARN`, listing how many positions in
  that chunk were affected.
- The chunk loaded fine, but `chunk.getBlockEntity(pos)` at that specific
  position wasn't a recognized container — logged once per position, at
  `INFO`, either "no block entity at all" or the actual class name found
  there. This is the common real-world case: the export file's position no
  longer matches what's actually built there (block broken/replaced since
  export, or the export came from a different world/seed than the one being
  imported into).

Before this logging existed, a nonzero `missing` count in the final summary
was undiagnosable — there was no way to tell "which positions" or "why"
without editing the code.

## adoptStructure() / /chestshare adopt-structure

Explicit, admin-run command to fix up compat containers (Sophisticated Storage /
CobbleFurnies) belonging to a specific already-generated structure — both ones that
were never auto-registered, and ones already registered with the *wrong* contents
(e.g. a player looted the container before anyone ran an import/scan over it). Per an
explicit product decision: this command **replaces** existing shared-container state
whenever it has trustworthy original data to replace it with, rather than skipping
anything already marked shared.

**Two distinct position sources, with different trust levels and different behavior:**
- **Precise path** (`PoolElementStructurePiece` + `FingerprintRegistry.getStructureStorageBlocks`):
  for single-piece jigsaw structures, the piece's `getPosition()` (origin) and
  `getRotation()` let us transform every fingerprinted relative position into an exact
  world position via `StructureTemplate.transform(...)`. Because we *know* the correct
  baked contents for these positions, we call `ContainerCompatibility.registerWithItems`
  unconditionally — including on containers already marked shared — to actually fix
  wrong data, not just fill gaps.
- **Fallback path** (any other piece type, or a piece whose template id we couldn't
  parse): scans the piece's bounding box for any recognized compat container. We have
  no known-correct contents here, so this keeps the old, more conservative behavior —
  steal whatever's currently in the container, and skip anything already shared, since
  overwriting with unknown data could just as easily make things worse.

**`resolveTemplateId` (was `parseTemplateId`) — why the precise path never engaged.** The
old parser assumed `SinglePoolElement.toString()` is `Single[<ns>:<path>][...]` and took the text
up to the first `]`. In 1.21.1 the element's template is an `Either<ResourceLocation,
StructureTemplate>`, so the string is actually `Single[Left[<ns>:<path>]]` — the old parser
returned `Left[<ns>:<path>`, which `ResourceLocation.tryParse` rejects. Result: every piece got
`templateId=null` and silently used the bounding-box fallback, which can only steal *current*
contents and therefore can never restore an empty (already-looted) container. That is the real
cause of the "2 empty containers not restored" reports; the earlier diagnostic logging showed the
symptom but not this cause. Resolution order now:
1. Reflection on the element's `Either` field, located by field TYPE (not name) so it survives
   Mojmap→intermediary remapping in production jars. Takes `either.left()` if it is a
   `ResourceLocation`.
2. First `namespace:path` regex token in `toString()` (handles both `Single[Left[..]]` and the
   older `Single[..]` shape).
3. `inferTemplateByLayout`: if the id is still unknown (or the template has no recorded storage),
   find the single registered template whose storage positions, transformed by the piece's
   rotation/origin, exactly equal the set of supported containers actually present in the piece's
   bounding box. Ambiguous (two different layouts fit) or no match → returns null and the piece
   uses the bbox fallback exactly as before; it never guesses.

**Content alignment (`alignByContents` + `compat/StructureAligner`) — locating a template with no id,
piece type, origin or rotation.** Added because id resolution alone still left emptied containers
skipped in the field, and blind guessing about *why* had already failed twice. It works from the
one thing that's always available: containers that still hold their original baked items.
Each such container (an "anchor") proposes every (template, rotation, origin) that would put a
template storage block exactly on it. A proposal is only ACCEPTED if the template's whole storage
layout fits the world: every storage block lands on a container that really exists (or ≥ 75% do and
≥ 2 anchors agree). That layout-fit test is what lets a single intact container per piece pin a
structure built from many small pieces — wrong rotations/offsets don't line up with the other
containers. Ties are only resolved when harmless (identical contents on identical positions, or
disjoint anchors = two instances of one template); otherwise it refuses to guess. Once pinned, every
storage block of that template — including ones a player emptied — has a known world position and
known original items, and joins the precise path. Notes:
- Rotation is solved for (all four 90° transforms), and the origin is derived with the same
  function used to place blocks, so it doesn't depend on vanilla's `position`/pivot semantics.
  Mirroring is not tried (jigsaw never mirrors).
- Already-shared containers are anchored by the items in their shared `ItemListTemplate`, since
  their live inventory is intentionally empty — so re-running the command after an earlier
  (fallback) adopt still works.
- Runs only on positions still unresolved after id/layout resolution; `putIfAbsent` means it never
  overrides a position the id path already resolved.
- The command prints a second "adopt-structure resolution: ..." line ONLY when something was left
  unhandled (a position with no container, an empty container with no known contents, a failed
  restore, or containers still on the contents-only fallback). On a clean run it would only restate
  the first line, and this command is run by an admin standing in a structure, not read from a log.
  Reading it: "0 readable ids" = piece/id problem; "0 match a known template block" = the registry's
  baked contents don't explain what's in the world (wrong/absent template, or processors changed
  contents); matches but 0 located = layout doesn't fit.
- **Logging is deliberately quiet.** Every per-piece and per-container line in this command is at
  DEBUG; the only INFO it emits is one copy of the result line the player already saw. An earlier
  version logged each piece's element/template id, each registry hit, and each container it touched
  at INFO, which buried the interesting lines in a wall of routine ones. Real problems (an
  unparseable loot table id, a container with no readable inventory, an ambiguous layout match, a
  resolved template whose positions hold nothing) are still WARN.
- Containers the *template itself* places empty (decorative cabinets etc.) are deliberately not
  shared by the precise path — reported as "empty in the template itself (left alone)".
- Precise-path `registerWithItems` returning null (no readable inventory) used to vanish from every
  count; it's now counted as "could not be restored" and logged at WARN.

**Loot-table containers (Cobblemon's gilded chest, vanilla chests) - why they were invisible.**
The precise path used to treat a template block's `items` list as the whole truth: non-empty meant
"restore these items", empty meant "the structure placed this container empty, leave it alone".
That is only true for Sophisticated Storage / CobbleFurnies, which bake real items into the
template. Every vanilla-style loot container - anything built on `RandomizableContainerBlockEntity`,
including Cobblemon's gilded chest - bakes **no items at all**, just a `LootTable` string. Worse,
`FingerprintRegistry` never recorded those positions in the first place, because
`ContainerFingerprintFilter` only matched `sophisticatedstorage:` / `cobblefurnies:` block ids. So a
looted gilded chest standing right beside a Sophisticated Storage chest in the same piece produced
exactly the reported symptom: the SS one restored, the gilded one not even counted as skipped.
Three changes, all of which are needed together:
1. The template scan now records a block when it carries a baked `LootTable` (definitive world-gen
   loot signal, any namespace), when its id is SS/CF as before, or when its id looks like a
   container in any namespace (`chest`, `barrel`, `shulker_box`, `crate`, ...). `ender_chest` is
   explicitly excluded - it reads as a container by name but can never be adopted, and a template
   position no live container can satisfy breaks `inferTemplateByLayout`'s exact set-equality test
   and drags down `StructureAligner`'s coverage score.
2. `StructureStorageBlock` carries `lootTable`/`lootTableSeed` alongside `items`, and
   `emptyInTemplate()` (items empty AND no loot table) is what now means "decorative, leave alone".
3. The precise loop checks `hasLootTable()` BEFORE `items().isEmpty()`, and restores via a
   `LootTableTemplate` - through `ContainerRegistrar.applyTemplate` for a
   `RandomizableContainerBlockEntity` (so the block entity's own loot-table field is cleared too,
   not just its inventory) or `ContainerCompatibility.registerWithLootTable` otherwise. If the live
   container still has its own loot table, that is used instead of the template's copy: it is the
   actual placed state, which a datapack or processor could have diverged from since generation.
A container already shared against that same loot table is left alone rather than re-registered -
re-registering would discard every player's existing roll, which is `/chestshare reset`'s job.

**`isAdoptableContainer` is the live-world counterpart of `ContainerFingerprintFilter`, and the two
must stay in agreement.** It accepts any compat container (Sophisticated Storage, CobbleFurnies,
Cobblemon, anything implementing `Container` from a non-vanilla class) plus vanilla chests, barrels
and shulker boxes, plus any vanilla block entity that actually carries a loot table. It deliberately
does NOT accept every `RandomizableContainerBlockEntity`: hoppers, dispensers and droppers all
extend it, and counting a structure's hoppers as containers would put positions in the live set that
no template block matches - breaking layout inference and content alignment, both of which compare
position sets. The same widened definition is used for the bounding-box scan, the layout matcher and
the alignment anchors, so all three see the same world.

**The fallback path no longer contents-steals vanilla containers.** It never saw them before this
change. A vanilla-class container reached through the bounding-box scan is registered only when it
still has an intact loot table (unambiguously untouched world-gen loot - a player's own chest never
has one); otherwise it is counted and logged as having no known original contents. Stealing the
current contents of an arbitrary vanilla chest inside a structure's bounding box would be as likely
to capture player storage as loot, which is exactly the class of bug 0.3.1 fixed for compat
containers.

**Idempotency: an already-correct container must not be re-restored.** The loot-table branch
compares an already-shared entry's `LootTable` id against the template's before touching anything;
the baked-items branch does the equivalent via `sharedEntryMatchesBaked` (slot+id+count set
comparison, same granularity `FingerprintRegistry` matches fingerprints at). Without this, running
`adopt-structure` twice in a row - which an admin will do, e.g. after checking the first run's
summary - reported every container as freshly "restored" a second time and, worse, actually
discarded and rebuilt every player's already-correct per-player roll for nothing. Both checks only
compare identity of the template reference/contents, not the per-player instances themselves;
`SharedContainerEntry.instances` is left untouched either way.

**`registerWithItems` places by recorded slot, not list position** — see
`compat/NOTES.md`'s `FingerprintRegistry.java` entry. This command is the reason that
distinction matters in practice: get it wrong here and adopt-structure looks like it
worked (no exceptions, a plausible-looking item count) while quietly handing every
player the wrong loot in the wrong slots.

**Overlapping bounding boxes:** `fallbackPositions.removeAll(precisePositions.keySet())`
exists because a piece's bounding box can genuinely overlap another piece's precise
positions (adjacent/interlocking jigsaw pieces). Without the subtraction, a position
already handled correctly by the precise path could get reprocessed by the fallback
path's more conservative (and contents-blind) logic.

**Diagnostic logging added after a real "12 containers, only 10 accounted for" report.**
Two separate causes, both now visible instead of silent:
1. `register()` bails out with a bare `return null` when the live container is currently
   empty (nothing to steal). The fallback loop only incremented `adopted` on a non-null
   result, so an empty, not-yet-shared container in the bounding-box fallback path
   vanished from every bucket in the summary — not adopted, not "already shared", not
   "no recognized container". Now logged and counted separately as `skippedEmpty`.
2. Whether a piece takes the precise (`FingerprintRegistry`) path or falls back to the
   bounding-box scan was previously invisible from outside the code. Every piece now logs
   its class, its `parseTemplateId` result, and (if resolved) how many storage blocks
   `FingerprintRegistry` has for that template id — so "why did this whole structure use
   the low-fidelity fallback" is answerable from the log instead of requiring a source read.

**Fallback path cannot restore contents into an empty container, full stop.** Unlike the
precise path (which knows the correct baked items and can write them into any container
state via `registerWithItems`), the fallback path's only source of truth is "whatever's
currently in the container" — there is nothing to fall back *to* for a container that's
empty and wasn't fingerprint-matched. Getting the precise path to actually engage (see
diagnostic logging above) is the only real fix for "this empty container should have had
X in it"; the fallback path skipping it with a clear log line is a correctness-preserving
stopgap, not a fix.
