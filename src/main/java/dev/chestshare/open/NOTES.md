# dev.chestshare.open — package notes

## SharedContainerOpener.java

**`resolveCompatEntry` must never register a new entry — it may only resolve
one that's already registered.** This was a real, shipped bug, not a
hypothetical: an earlier version of this method called
`ContainerCompatibility.register(world, be, true)` whenever nothing was
registered yet at that position, meaning the **first time any player opened
any Sophisticated Storage container** — including one they had just crafted
and placed themselves — it got silently converted into a shared container.
There's no way to fix this by checking "was this chunk freshly generated"
the way `ContainerScanner` does for other modded containers: that check only
makes sense at chunk-load time, and by the time a player has walked up and
opened a container, the chunk could have been loaded (and the container
placed) at any point in the past, including seconds after the mod was
installed by a player with their own existing base. Unlike vanilla
containers, which have a real per-container signal (`getLootTable() !=
null` — untouched loot table) that stays valid regardless of when the chunk
loaded, a reflective-compat container has no equivalent field to check on
open. The only safe rule is: **registration for these containers happens
exclusively through `ContainerScanner`'s `freshlyGenerated`-gated passive
scan.** Opening a container only ever resolves an entry that scan already
created; it's never allowed to create one itself.

**`replaceFactory` — reflective/compat branch must use `resolveCompatEntry`,
not `ContainerCompatibility.register()` directly, for a second, separate
reason on top of the one above.** `register()` is a one-shot "steal current
contents" call that returns `null` once the container is already marked
shared (its own guard checks `marker.chestshare$isShared()`). Since
`replaceFactory` runs on **every** `ServerPlayer#openMenu` call, calling
`register()` directly here — even if it were otherwise safe to auto-register
on open, which the note above explains it isn't — would mean a container's
first open works, but every open after that gets `entry == null` and falls
through to `return factory`, opening the raw, already-emptied storage menu
with no shared-instance items ever populated again ("storage becomes
unopenable" after the first use). Always go through `resolveCompatEntry()`.

**`openCompatContainerForUse`** — directly opens the shared menu for a
reflective-compat container (Sophisticated Storage, etc.) from the block-use
event itself, instead of registering the container and hoping the block's own
click-handling later calls `ServerPlayer#openMenu(...)` in a way
`ServerPlayerEntityMixin` can intercept. If `resolveCompatEntry` finds nothing
registered, this correctly returns `false` and the caller lets the block's own
(unmodified) menu open instead — this is the expected, common case for a
player's own placed storage, not an error. Returns `true` only when an
already-registered shared entry was found and its menu opened (caller should
cancel the block-use event so the real menu never opens underneath this one).

**`resolveCompatEntry`** — resolves the existing shared entry for the
reflective-compat path if one exists; returns `null` otherwise, and does
**not** perform registration itself (see the note above for why). If a
position previously had an entry but the block entity isn't currently marked
shared (e.g. the block was replaced), the stale entry is removed and `null`
is returned — the same stale-entry cleanup pattern `resolveGenericEntry` and
`resolveBlockEntry` also use, though (see below) `resolveGenericEntry` did
not, until recently, share this method's stricter "never register" rule.

**`resolveGenericEntry` / `scanRegisterGenericContainer` — the same
auto-register-on-open bug as `resolveCompatEntry` above, but for
`Container`-based compat blocks (Carved Wood, Handcrafted, and similar),
found and fixed later.** This was a second instance of exactly the bug
described at the top of this file, missed the first time because it lives
in a different method: `resolveGenericEntry` used to register-on-demand the
same way `resolveCompatEntry` used to, and it is reachable from **two**
call sites with no `freshlyGenerated` gate at all — `ChestShare`'s
`UseBlockCallback` handler, and `replaceFactory` itself, both of which fire
on every single interaction with a `Container`-based modded block. A
player's own freshly-placed Carved Wood barrel would be silently converted
into a shared container the moment they opened it, identical in effect to
the bug already fixed for Sophisticated Storage. The fix follows the exact
same shape as `resolveCompatEntry`/`register()`: `resolveGenericEntry` is
now resolve-only (never registers, used by both open-time call sites and by
the public `resolveGenericEntryForUse` wrapper), and a separate method,
`scanRegisterGenericContainer`, carries the actual registration logic and
is only ever called from `ContainerScanner`'s `freshlyGenerated`-gated
passive scan — the one place it's safe. `scanRegisterGenericContainer` also
now calls `state.setDirty()` after `putBlock`, which the original
registration code never did (every other registration path in the codebase
does); without it, a newly-shared generic container wasn't guaranteed to
persist if the server stopped before its next autosave.

**Why this doesn't use a "was this position inside a generated structure"
check (`StructureManager#getStructureWithPieceAt`) instead of relying only on
`freshlyGenerated`.** That API is real and would be a genuine improvement for
vanilla/`Container`-based containers — it can distinguish "this chest is
inside a village house" from "this chest is a player's own base" even on old,
already-explored chunks, which `freshlyGenerated` can't do retroactively.
But it doesn't help here: Sophisticated Storage and CobbleFurnies are
player-craftable furniture, not structure loot — neither mod places its
containers as part of any generated structure's pieces. There is no
"genuine world-gen instance" of these containers for such a check to ever
distinguish from a player-placed one; every one that will ever exist on a
server is player-placed by construction. So for these two mods specifically,
"never auto-register on open" isn't a stopgap waiting for a smarter check —
it's the correct final behavior. An admin who deliberately wants to share a
specific Sophisticated Storage/CobbleFurnies container (e.g. staging a
hand-placed reward chest for an event) already has the right tool for that:
`/chestshare convert <pos> <loot_table>` already has a modded/compat branch
for exactly this deliberate, one-position-at-a-time opt-in.

**`mixedDoubleChestFactory` - a double chest with one shared half and one ordinary chest.**
Builds a vanilla `CompoundContainer` of the shared half's per-player `SharedInventory` (27
slots, seeded from that player's own instance) and the ordinary chest's real, live
inventory, and opens it in a 9x6 `ChestMenu`. The ordinary half is never registered,
captured or emptied - see mixin/NOTES.md for why the older force-registration was wrong.

Why `CompoundContainer` rather than copying both halves into one `SharedInventory` the way
`doubleChestFactory` does: edits to the ordinary half must reach the REAL chest, and
`CompoundContainer` routes `setItem`/`setChanged` to it directly. It also gives us, for
free, the validity check for the ordinary half (`stillValid` requires both halves to be
valid: block entity still placed, player in range) and vanilla's open/close handling
(`startOpen`/`stopOpen` reach the real chest, so its lid opens and closes). The
`SharedInventory` saver persists only the shared half's 27 slots.

`doubleChestFactory` itself still requires BOTH entries to be non-null - it calls
`getInstance` on each. Callers must route the one-shared-one-ordinary case here.

**`save()` calls `entry.clearEmptyInstances()` right after `putInstance`** —
see `state/NOTES.md` for why. The double-chest close handler above doesn't
go through `save()` (it writes to two entries directly), so it calls
`clearEmptyInstances()` on both entries itself, for the same reason.
