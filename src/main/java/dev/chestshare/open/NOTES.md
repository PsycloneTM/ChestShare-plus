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
is returned, matching the same pattern `resolveGenericEntry` and
`resolveBlockEntry` use elsewhere in this file.
