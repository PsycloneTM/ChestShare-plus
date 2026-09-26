# Changelog

All notable changes to ChestShare+ are documented here. This fork's own
version numbers only — see [README.md](README.md) for what ChestShare+ adds
on top of Calamech's original [ChestShare](https://modrinth.com/mod/chestshare).

## 0.3.3

### Fixed

- **A shared container's saved per-player data grew forever, with nothing
  ever removing an old entry.** Every player who opened a given shared
  container kept a permanent record of their roll in that world's save
  data, even once they had taken everything and had nothing left to lose by
  rolling fresh next time. On a server with many players and many shared
  containers over time, this is unbounded growth with no natural ceiling.
  An all-empty entry — a player who legitimately took everything, or the
  rare case of a roll that should have had loot but didn't — is now dropped
  the moment the container is closed in that state, and that player rolls
  fresh from the template on their next visit.

- **Critical: `Container`-based compat blocks (Carved Wood, Handcrafted, and
  similar) could be silently converted into shared containers the first time
  a player opened their own freshly-placed one.** This is a second instance
  of the bug already fixed in 0.3.1 for Sophisticated Storage — found later
  because it lived in a different code path (`resolveGenericEntry`, used for
  modded blocks that implement vanilla's own `Container` interface, rather
  than the reflective path Sophisticated Storage uses). Registration for
  these containers now only ever happens through the passive,
  `freshlyGenerated`-gated scan; opening one only ever displays an
  already-registered shared container.

- **Structure scanning could silently fail to record some legitimate
  megastructures**, including all four regional leagues, `team_galactic_hq`,
  `stark_mountain`, `secret_garden`, `sky_pillar`, `newmoon_island`, and
  `fullmoon_island` — several failing to parse by only tens of bytes. The NBT
  read used a 64 MiB allocation cap borrowed as a generic safe default,
  without checking it against real structure sizes; both the structure
  fingerprint scan and `/chestshare import` now read without a cap, since
  both only ever read files the server admin already chose to run (bundled
  structure files, or ChestShare+'s own export files) — there's no untrusted
  network input to guard against here the way vanilla's own cap exists for.

- **A container already correctly shared from a structure could be
  needlessly rebuilt**, discarding a player's current progress looting it,
  purely because its `SharedMarker` flag or structure-provenance record had
  drifted out of sync with `SharedContainersState` (e.g. after a block
  entity reload). The passive chunk scan now leaves every container that is
  already registered completely alone: it never rebuilds one from its
  structure template, and it does no repairing of any kind (no marker
  re-sync, no provenance stamping). `SharedContainerEntry` records which
  structure template a structure-derived registration came from, persisted
  across restarts, and that record is written once, when the entry is
  created.

- **Critical: newly-shared vanilla-style containers could silently fail to
  persist across a restart.** A missing `state.setDirty()` call after
  writing a new registration meant the write only ever updated
  `SharedContainersState`'s in-memory copy — it looked completely successful
  for the rest of that session (the container opened correctly, and
  re-running the same command recognized it as already shared), but was
  never actually saved to disk. On the next reload of that data (most
  commonly a server restart), the registration was simply gone, and
  whatever registered it — the passive scanner, `/chestshare convert`,
  `/chestshare import`, or `/chestshare adopt-structure` — would do so
  again, discarding the previous per-player state. This affected any
  vanilla-style container (including loot-table-based ones like Cobblemon's
  Gilded Chest) registered through the shared `ContainerRegistrar` write
  path, plus the modded/compat branches of `convert` and `import`
  specifically. Every write path in the codebase now correctly marks the
  state dirty.

- **Critical: a just-registered shared container could be silently deleted the
  very next time it was opened, even within the same server session.** Four
  places that resolve a shared container on open used to treat "the block
  entity's own cached flag disagrees with `SharedContainersState`" as "this
  entry must be stale garbage," and deleted the real, correct entry to match
  the flag. That flag is a cheap, on-block cache of one bit of the real data
  in `SharedContainersState`, not a second source of truth — it can and does
  desync for ordinary reasons (most commonly a block entity getting a fresh
  Java object instance across a chunk reload), and the fix now trusts the
  actual data and repairs the flag to match it, rather than the reverse.
  This is what caused a real reported case: `/chestshare adopt-structure`
  correctly restored a Cobblemon Gilded Chest, but the entry was deleted the
  moment a player opened it, so every subsequent run of the command reported
  "restored" again, forever, with no restart involved.

- **A confirmed, genuine mismatch between a shared container's stored
  contents and its structure template could still silently fail to restore.**
  Two separate issues combined to produce this: first, the check used to
  decide whether an already-shared container's contents were correct compared
  the stored (already slot-normalized) entry against the raw baked item list
  directly, so any normalization difference between the two representations
  made them permanently disagree even when the actual contents matched —
  fixed by running both sides through the same normalization pass before
  comparing. Second, once a mismatch was correctly confirmed as genuine, the
  actual restore was calling the same write method used for a first-time
  registration, which refuses on purpose to overwrite a container that's
  already registered — so the restore silently no-opped instead of running.
  `/chestshare adopt-structure` now uses a separate, explicitly-guarded write
  path for this one case; every other registration path is unaffected and
  keeps the original protection against overwriting real shared state.

- **Critical: opening a double chest made of a shared chest and an ordinary
  chest failed with an exception.** Placing a new, empty chest next to a shared
  loot or structure chest (very ordinary — e.g. beside a village or dungeon
  chest) and opening the resulting double chest threw a `NullPointerException`
  on the server instead of opening. The double-chest hook force-registered the
  ordinary half so it would have something to hand to the menu builder, but an
  empty chest with no loot table has nothing to capture, so no entry was created
  and the missing one was passed straight through.

- **Critical: a player's own chest joined to a shared chest was converted into
  a shared container.** That same force-registration, when the ordinary half
  was *not* empty, captured its contents as a template, emptied the real chest,
  and gave every other player who opened it their own copy of those items —
  sweeping up a player's own storage, which ChestShare+ is meant never to do.
  The ordinary half of a double chest is now never registered or touched. The
  menu shows the shared half from the opening player's own copy next to the
  ordinary half's real, live inventory, in the same slot order as an all-shared
  double chest, and the ordinary half's contents are seen identically by
  everyone.

- **The world-gen safety gate could be defeated across dimensions.** The set of
  freshly generated chunks was keyed by chunk position alone, but every
  dimension uses the same chunk coordinates (the spawn area exists in all of
  them, as does anything reached through a portal at matching coordinates). A
  chunk freshly generated in one dimension could therefore be "consumed" by a
  different, already-existing chunk loading at the same coordinates in another
  dimension: the old chunk was then scanned as if it were new — the exact case
  the gate exists to rule out, and what stops installing the mod on an
  existing world from sweeping up players' own modded storage — while the
  genuinely new chunk lost its flag. The set is now keyed by dimension as well
  as chunk position.

### Changed

- **Sophisticated Storage and CobbleFurnies reflection is now resolved once per
  block-entity class instead of on every scan and right-click.** Which methods
  and fields to call is a fact about the class, so it is cached after the first
  successful lookup; slot counts and contents are still read fresh from every
  container (two barrels of the same block can have different slot counts), and
  a cached entry that doesn't work for a particular container falls back to the
  full search rather than being trusted. Failed lookups are never cached, since
  some failures are timing-dependent per container.

## 0.3.2

Adds a structure fingerprint registry and `/chestshare adopt-structure`, so
containers that were already in the world before ChestShare+ was installed —
including ones that have since been looted — can be brought under per-player
loot with their original contents.

### Added

- **Structure fingerprint registry.** At server start, ChestShare+ now scans every
  structure template registered on the server (vanilla, datapack and mod-provided
  `.nbt` files) and records the storage blocks baked into them: Sophisticated Storage
  and CobbleFurnies containers with their items, and any container that carries a
  baked loot table (vanilla chests and barrels, Cobblemon's gilded chest, and other
  loot containers). The startup log warns if structures were read but no storage
  blocks were recorded.

- **Automatic sharing for structure containers that predate the mod.** Sophisticated
  Storage and CobbleFurnies containers that came from a structure template (e.g.
  COBBLEVERSE trainer camps) are now shared when their chunk loads, even if the chunk
  was generated before ChestShare+ was installed. A container is only shared when both
  checks pass: its current contents exactly match a container baked into a registered
  template, *and* it sits inside that structure's bounding box.

  Known limitation: a player-built Sophisticated Storage inside a structure's bounding
  box that holds exactly the same items in exactly the same slots as a template
  container would also match. This is far narrower than the 0.3.0 problem fixed in
  0.3.1, and `adopt-structure` (below) does not share it, since it never uses contents
  to decide where to look.

- **Optional automatic restore of emptied structure containers** (off by default). Toggle it with
  `/chestshare toggle restore-empty-structures true` (persists across restarts; `false` to turn it
  back off, or the command with no argument to check the current value). When a chunk that already
  existed loads, an **empty** container that stands
  at exactly a storage position of a registered structure template — and is exactly the block the
  template places there — is refilled with the template's original loot (baked items or loot table)
  and made a shared container, without anyone running `adopt-structure`. It is deliberately
  narrower than the command: it only acts on an unshared, completely empty container in a jigsaw
  piece whose template the game names outright, where exactly one template block lands on that
  position with a matching block id. Non-empty containers, containers the template places empty,
  templates with more than one palette, and anything that would need layout matching or content
  alignment to identify are left alone — use `adopt-structure` for those.

  Known limitation: a player who uses a structure's own storage block as personal storage will see
  it refilled and made per-player if it happens to be empty when its chunk loads. Nothing is lost —
  it is empty at that moment — but what that chest is changes. That is why the setting is off by
  default.

- **`/chestshare adopt-structure <pos>`** (op level 2, available once server startup
  has finished). Adopts every storage container in the structure at `<pos>` and
  restores each one's original loot from the structure template, whatever it holds
  now — so emptied and looted containers are restored, as are containers that were
  previously shared with the wrong contents.
  - **Baked-item containers** (Sophisticated Storage, CobbleFurnies) get their
    original items back in their original slots.
  - **Loot-table containers** (vanilla chests, barrels and shulker boxes, Cobblemon's
    gilded chest, and other modded loot containers) are re-pointed at the template's
    loot table and roll per player, the same as `/chestshare convert`.
  - **Safe to re-run.** A container already shared with exactly what the template
    says is reported as "already shared" and left untouched, so players' existing
    rolls aren't wiped. A shared container that differs from the template is
    rewritten, which discards players' saved copies of it. Use
    `/chestshare reset <pos>` to deliberately re-roll one.
  - **Finds the template even when the game won't name it.** It tries the structure
    piece's template id, then matches the container layout against every registered
    template (it will not guess if two templates fit equally well), then works out
    which template was placed, where and at what rotation from containers that still
    hold their original items and restores the emptied ones by position.
  - **Fallback when no template is found.** Unshared Sophisticated Storage /
    CobbleFurnies containers with items in them are adopted from their current
    contents, and any container that still has its original loot table is adopted
    from that. Vanilla containers with no loot table left are never touched, since
    they could be a player's own chest. Containers that are already shared are never
    changed on this path. Hoppers, dispensers and droppers are never treated as
    storage.
  - **Readable result.** One summary line lists only what actually happened: adopted,
    restored to original contents, restored from the template's loot table, already
    shared, empty with no known original contents, no recognized container at a
    template position, empty in the template itself, or could not be restored. A
    second line explaining how the structure was resolved appears only when
    something was left unhandled. Per-container detail is logged at DEBUG, so a
    normal run adds one line to the server log.

### Fixed

- **Startup log spam:** every block entity that isn't a Sophisticated Storage block (waystones,
  Cozy Home lamps/chimneys/clocks, Cobblemon PCs and healing machines, PokeBlocks plush, trainer
  spawners, ...) logged `[ChestShare] '<class>' has no getStorageWrapper() method ... Sophisticated
  Storage compat does not apply to this block entity.` once per class. That is the expected state
  for anything that isn't Sophisticated Storage, so it is no longer logged; the warning is kept
  only for Sophisticated Storage's own classes, where a missing method would be a real problem.
- The `getStorageWrapper()` reflection lookup is now cached per class, so it no longer re-walks a
  block entity's superclass chain on every chunk load and right-click.

## 0.3.1

### Fixed


- **Critical:** opening a Sophisticated Storage (or CobbleFurnies) container
  for the first time could silently convert it into a shared container —
  including a player's own crafted-and-placed storage, not just
  world-generated loot containers. Registration for these containers now
  only ever happens through the passive world-generation scanner (gated on
  the chunk being freshly generated, never on merely being opened). Opening
  a container now only displays an *already-registered* shared container;
  it never creates one.

  If you installed 0.3.0, check your server log for
  `[ChestShare] compat-registered` lines from before you update — those
  containers were already converted and their original contents moved into
  shared state. This fix stops it from happening to any more containers,
  but does not retroactively restore ones already affected.

### Added

- `/chestshare import` now logs exactly why a position was counted as
  "missing" in the final summary, instead of only contributing to a bare
  count: whether the whole chunk failed to load in time, or the chunk
  loaded fine but no recognized container was found at that specific
  position (and if so, what was found there instead).

## 0.3.0

Initial public release of ChestShare+, forked from ChestShare 0.2.3. See
[README.md](README.md) for the full feature list.
