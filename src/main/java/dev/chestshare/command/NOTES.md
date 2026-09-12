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
