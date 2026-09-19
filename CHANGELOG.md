# Changelog

All notable changes to ChestShare+ are documented here. This fork's own
version numbers only — see [README.md](README.md) for what ChestShare+ adds
on top of Calamech's original [ChestShare](https://modrinth.com/mod/chestshare).

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
