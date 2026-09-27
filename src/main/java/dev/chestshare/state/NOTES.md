# dev.chestshare.state — package notes

## ContainerTemplate.java

**`LootTableTemplate.createStacks()` — loot tables must be looked up via
`server.reloadableRegistries()`, not `world.registryAccess()`.** Loot tables are
NOT part of `world.registryAccess()` in 1.21.1 — they live in the separate,
datapack-reloadable registry exposed via `server.reloadableRegistries()`, same
as vanilla's own loot-table lookups (`LootableContainerBlockEntity`, etc.) and
the same API `ChestShareCommands.convert()` already uses to validate the loot
table ID up front. Looking this up through `world.registryAccess()` instead
(a previous, buggy version of this code) always found nothing, so every
converted container — vanilla or modded compat — silently generated an
all-empty item list on every open, forever: the convert command reported
success and the container "opened" but was permanently empty, which is
indistinguishable from broken/unopenable storage in practice. This is a real
bug that was fixed once already; don't reintroduce the `world.registryAccess()`
lookup here.

**`createStacks()` — must use `LootTable#fill()` against a scratch container,
not `getRandomItems()`.** `getRandomItems()` just returns the flat generated
stack list with no slot placement, which left every roll packed into slots 0,
1, 2... instead of scattered the way vanilla loot chests look. `fill()` runs
vanilla's own slot-shuffle logic (`getAvailableSlots` + `shuffleAndSplitItems`)
against the container size, so both the seed and the resulting slot layout
come from the same call. This was also a real, previously-shipped bug ("loot
packed at the start") — fixed by filling a scratch `SimpleContainer` via
`fill()` then copying it out, matching what the original 0.2.3 source did.

**NBT encoding — `ItemStack.EMPTY` cannot be serialized.** Minecraft 1.21.1
throws when encoding `ItemStack.EMPTY`. Empty slots are simply not serialized;
non-empty stacks retain their slot index explicitly (`"Slot"` field) so they
can be placed back correctly on decode even with gaps. Applies to both
`LootTableTemplate` (n/a, it doesn't store items) and `ItemListTemplate.toNbt()`.

## SharedContainerEntry.java

Same NBT gotcha as above applies to `toNbt()`'s per-player instance
serialization: `ItemStack.EMPTY` slots are omitted, and non-empty stacks
retain their slot index so `fromNbt()` can restore them into the correct
position even though the slot list format is now sparse.

**An all-empty per-player entry is kept, not dropped.** Every player who
ever opens a given shared container gets a permanent entry here
(`putInstance` is called on every menu close, in `SharedContainerOpener`'s
`save()`, regardless of what's left in the container). Once a player fully
empties their instance of a shared container, that all-empty entry is kept
exactly like any other: `getOrCreateInstance` finds it on their next visit
and returns the stored (empty) stacks rather than rolling from the template
again. This is deliberate — a shared container is meant to be one-time
personal loot, not a renewable resource, so a player who takes everything
should find it empty if they come back, not get a second roll.
`clearInstances()` (used by `/chestshare reset`) is still the only way to
wipe an entry — a different, admin-triggered, whole-container operation,
not per-player pruning.

The previous behavior instead dropped an entry the instant it went
all-empty (`clearEmptyInstances()`, called right after `putInstance` in
`save()` and in the double-chest close handler), which made that player
roll fresh next time — worth knowing about if this needs revisiting, since
it's what stopped `instances` from growing forever. That tradeoff is real
again now: a busy server with many players and many shared containers
accumulates one `List<ItemStack>` per player per container for the life of
the world, empty ones included, with nothing pruning them. If that becomes
a problem, the fix is not to bring back instant pruning (that's what
reintroduces the free-fresh-roll loophole) — it's to prune only entries
that are both empty and stale (untouched for a long time), which needs a
last-accessed timestamp per entry that doesn't exist yet.
