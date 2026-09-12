# dev.chestshare.open — package notes

## SharedContainerOpener.java

**`replaceFactory` — reflective/compat branch must use `resolveCompatEntry`, not
`ContainerCompatibility.register()` directly.** This was a real bug, not a style
choice: `register()` is a one-shot "steal current contents" call that returns
`null` once the container is already marked shared (its own guard checks
`marker.chestshare$isShared()`). Since `replaceFactory` runs on **every**
`ServerPlayer#openMenu` call, calling `register()` here meant a Sophisticated
Storage container's first open through this modded/reflective branch would
register and open correctly, but every open after that got `entry == null`,
fell through to `return factory`, and opened the raw, already-emptied storage
menu with no shared-instance items ever populated again — i.e. "storage becomes
unopenable" after the first use. `resolveCompatEntry()` fixes this: it's
idempotent, returning the existing entry on repeat opens instead of `null`.

**`openCompatContainerForUse`** — directly opens the shared menu for a
reflective-compat container (Sophisticated Storage, etc.) from the block-use
event itself, instead of registering the container and hoping the block's own
click-handling later calls `ServerPlayer#openMenu(...)` in a way
`ServerPlayerEntityMixin` can intercept. Registration (the one-shot
item-stealing step) and showing the replacement menu now always happen
together, in this one call, so there's no window where the container has been
emptied but nothing has opened a menu to show where the items went. Returns
`true` if the shared menu was opened (caller should cancel the block-use event
so the real menu never opens underneath this one); `false` if there was
nothing to share (caller should let vanilla/the block's own handling proceed
normally).

**`resolveCompatEntry`** — idempotent resolve for the reflective-compat path:
returns the existing shared entry if this container was already registered,
otherwise performs the one-shot registration (stealing its current contents
into the shared state) exactly once. Unlike calling
`ContainerCompatibility.register()` directly from multiple call sites, this is
safe to call repeatedly — it never re-registers, and never returns `null` just
because a previous call already happened. See the `replaceFactory` note above
for why this distinction matters.
