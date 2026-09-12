# dev.chestshare.mixin — package notes

## ChestBlockMixin.java

The single-chest branch registers lazily, immediately before the chest's
normal menu is opened. This replaces an older `CHUNK_GENERATE`-time scan
approach without losing vanilla chest discovery — vanilla chests are picked up
on first right-click instead of on chunk load.

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
