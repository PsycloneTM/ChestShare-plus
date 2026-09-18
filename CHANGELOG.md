# Changelog

All notable changes to ChestShare+ are documented here. This fork's own
version numbers only — see [README.md](README.md) for what ChestShare+ adds
on top of Calamech's original [ChestShare](https://modrinth.com/mod/chestshare).

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
