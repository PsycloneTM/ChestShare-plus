# dev.chestshare.mixin — package notes

## ChestBlockMixin.java

The single-chest branch registers lazily, immediately before the chest's
normal menu is opened. This replaces an older `CHUNK_GENERATE`-time scan
approach without losing vanilla chest discovery — vanilla chests are picked up
on first right-click instead of on chunk load.

The double-chest branch has three cases, decided by `resolveBlockEntry` on each half:
neither half shared -> return and let vanilla open it; both shared ->
`doubleChestFactory`; exactly one shared -> `SharedContainerOpener.mixedDoubleChestFactory`.
A `null` from `resolveBlockEntry` means "nothing to share here" (no loot table, not
registered, not marked), i.e. an ordinary chest.

**Never force-register the ordinary half.** An earlier version did, so that
`doubleChestFactory` (which calls `entry.getInstance` on both halves) would always get a
non-null entry. That had two failures: `registerForced` returns `null` for an empty
chest with no loot table (nothing to capture), so a player's fresh chest placed next to a
loot chest produced a null entry and a `NullPointerException` when the menu opened; and
for a NON-empty ordinary half it captured the player's own items as a template, emptied
the real chest, and gave every other player a copy. A first attempted fix - just return
to vanilla's menu when a half is null - was also rejected: registering the shared half
empties its live block entity, so it showed as empty in the vanilla menu. Nothing in
the older notes explained the neighbor force-registration, so it was treated as a
workaround for the null problem, not a design decision.

**Slot order.** The LEFT half is "first" (slots 0-26) in both factories. In the mixed case
`sharedIsFirst = (primaryShared == primaryIsLeft)`: the shared half is the LEFT half when
it is the primary and the primary is LEFT, or when it is the secondary and the primary
is RIGHT.

## ServerChunkManagerAccessor.java

The private method this accessor targets is package-private on `ServerChunkCache`,
so `ImportJob` can't call it directly — this accessor exists so `ImportJob` can
poll a requested chunk's loading future each tick instead of blocking the server
thread on `ServerLevel#getChunkAt(pos)`.

**Invoker target name: `getVisibleChunkIfPresent`, not `getChunkHolder`.** The
original 0.2.3 source was built against **Yarn** mappings, where this method is
named `getChunkHolder`. This project's mixins use **Mojang (official/named)**
mappings throughout (`saveAdditional`/`loadAdditional`/`openMenu`, etc.), and
under Mojmap the same method (`method_14131` in both mapping sets' shared
intermediary layer) is named `getVisibleChunkIfPresent`. Using the Yarn name
here compiles fine syntactically but fails at Mixin annotation-processing time
with "Could not locate @Invoker target" (Mixin uses reflection against the
actual mapped class, and there is no `getChunkHolder` method on Mojmap's
`ServerChunkCache`). This was a straight copy-paste-from-original bug, not a
version issue — the underlying method exists in 1.21.1 under both mapping sets,
just under different names.
