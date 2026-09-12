# dev.chestshare.compat — package notes

## ContainerCompatibility.java

This class reflectively bridges storage mods whose block entities don't implement
vanilla `Container` (Sophisticated Storage, CobbleFurnies, etc.) into the same
`CompatInventory` shape ChestShare uses everywhere else. Most of the complexity
here is not incidental — it's the result of three earlier, failed attempts at the
Sophisticated Storage lookup specifically, each of which failed *silently*. This
file documents that history so nobody re-introduces the same mistakes.

### `register()` — success logging

Success previously had no log line at all, meaning even a fully working
reflective lookup gave no positive confirmation, only the absence of a warning.
The `LOGGER.info("[ChestShare] compat-registered ...")` line exists so a working
run is distinguishable from one that silently never reached this code path.

### `findHandler()` — Sophisticated Storage lookup

Path: `StorageBlockEntity -> getStorageWrapper() -> getInventoryHandler()`, where
the returned `net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler` extends
`io.github.fabricators_of_create.porting_lib.transfer.item.ItemStackHandler` —
confirmed by extracting the actual shaded class out of sophisticatedcore's own
jar-in-jar libs (`META-INF/jars/transfer-*.jar`) rather than assumed from Forge's
API shape.

**Important, and the reason this kept silently breaking:** this is NOT a 1:1 port
of Forge's `IItemHandler`. That interface's `getSlots()` returns `int`, but on
this class `getSlots()` is a *different* method that returns
`List<ItemStackHandlerSlot>`. The real int-returning slot-count method here is
`getSlotCount()`.

Three earlier fix attempts all picked a slot-count method purely by **name**, with
no check on the return type:
- **0.3.1** guessed `getNumberOfInventorySlots` (wrong name for this class).
- **0.3.2** guessed `getSlotCount`, but the superclass walk needed to reach it
  wasn't in place yet.
- **0.3.3** added that walk but put `"getSlots"` first in the candidate list —
  which now resolves (unlike the first two attempts) but hands back a `List`, so
  `v instanceof Number` was false and slots silently stayed `0` every time, with
  the only trace a `warnOnce` log line nobody was watching for.

**The fix:** invoke each candidate name in turn and only accept the first one
whose return value is actually a `Number`, so a name that resolves to the wrong
type is skipped instead of silently accepted. See the `slots <= 0` fallback loop
over `{"getSlotCount", "getSlots", "getNumberOfInventorySlots", "size"}`.

**Primary approach**, tried before that fallback loop: ask the storage wrapper
itself for its slot count via `getNumberOfInventorySlots()`. Confirmed (via a
working mod that reflects into the same Sophisticated Storage API) to be a plain
public no-arg `int` method directly on `StorageWrapper` — no need to dig into the
`InventoryHandler`/`ItemStackHandler` internals at all for this. Earlier
ChestShare versions only checked the `IStorageWrapper` *interface*, which indeed
has no slot-count method, and missed that the concrete `StorageWrapper` class
declares one directly.

**Every branch in this lookup now logs its own miss on the way to `null`.**
Previously all of these fell through completely silently: exceptions were
discarded with just a comment, and a `null` at any stage before the final `inv`
produced no `warnOnce` call at all — which is why nothing showed up in
`latest.log` even while the whole lookup was failing.

The outer `try/catch` around the whole Sophisticated Storage block also logs
(rather than silently swallowing): optional compatibility must never prevent
server startup, but a swallowed-and-uncommented exception here is exactly what
hid the original `setAccessible` bug for as long as it was hidden.

The isolated `try/catch` around each slot-count invoke attempt (inside the
fallback loop) is deliberate: a failure there must not propagate up into
`findHandler`'s top-level catch, which would silently drop the whole lookup
instead of trying the next candidate name.

### `findMethod(Class<?>, int paramCount, String... names)`

Like the simpler `findMethod` overload, but only matches methods with the given
parameter count, to avoid grabbing the wrong overload when a class reuses a name
(e.g. a 0-arg `size()` vs a 1-arg `size(int)`). Tries the public API first
(`Class.getMethods()` already walks the full inheritance chain including
interfaces — the common case for methods like `getSlots()` declared on a
library superclass), then falls back to walking declared methods for
non-public/package-private members.

**`setAccessible` is required even for methods found via `getMethods()`:**
`Class.getMethods()` walks inherited *public* methods even when the *declaring*
class is package-private (e.g. a library's internal `ItemStackHandler` base class
sitting under the public `InventoryHandler` subclass). Method objects obtained
this way still fail `invoke()` with `IllegalAccessException` unless
`setAccessible` is called explicitly — the JVM checks the declaring class's
accessibility, not just the method's own public modifier. Without this,
`getSlots()` (inherited, not declared on `InventoryHandler` itself) was found but
every `invoke()` threw, and that exception propagated up into `findHandler`'s
top-level catch, silently discarding the whole Sophisticated Storage lookup with
nothing logged.

### Other notes

- `WARNED_CLASSES` / `warnOnce`: avoids log spam by only warning once per
  block-entity class per session.
- Get/set method lookups (`getStackInSlot`/`getSlotStack`/`getStack`/`getItem`
  and setters) are arity-checked so the code never accidentally binds to an
  unrelated overload of the same name.
- CobbleFurnies branch: keeps its inventory in the inherited protected method
  `method_11282()` / `getItems()` (intermediary vs. named mapping).
- Generic modded `Container` branch: last-resort fallback for other inventory
  block entities that do implement vanilla `Container` but weren't caught by
  `getContainerInventory`'s direct check (defensive, kept simple on purpose).
