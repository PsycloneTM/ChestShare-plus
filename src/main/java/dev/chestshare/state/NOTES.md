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

**`clearEmptyInstances()` — bounds `instances`' unbounded growth.** Every
player who ever opens a given shared container gets a permanent entry here
(`putInstance` is called on every menu close, in `SharedContainerOpener`'s
`save()`, regardless of what's left in the container), and nothing else in
the map's lifecycle removes an entry — `clearInstances()` (used by
`/chestshare reset`) wipes every player's entry at once, which is a
different, admin-triggered operation, not per-entry pruning. Left
unaddressed, a busy server with many players and many shared containers
accumulates one `List<ItemStack>` per player per container forever, most of
them eventually all-empty once a player has taken everything and never
returns.

Called from `SharedContainerOpener.save()` (and the double-chest close
handler, which writes to two entries directly rather than through `save()`)
immediately after `putInstance`, so an entry that just became fully empty
is dropped right at the moment it happened — the one point where it's safe
to evaluate a player's own entry, since they just walked away from exactly
that state. Removing an all-empty entry means that player rolls fresh from
the template next time they open it, which is the correct, harmless outcome
either way: they legitimately took everything (nothing lost by resetting),
or a bug produced an empty roll that should have had loot (resetting is the
recovery, not the damage). The entry itself can't distinguish the two
cases, which is fine — both cases want the same outcome.
