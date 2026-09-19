package dev.chestshare.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out where a registered structure template was placed in the world, using only the
 * containers that still hold their original baked contents as anchors - no piece type, template
 * id, piece origin or piece rotation needed. See command/NOTES.md ("content alignment").
 *
 * Each anchor (a live container whose contents exactly equal some template block's baked items)
 * votes for every (template, rotation, origin) that would put that template block on it. The true
 * placement collects a vote from every intact container in the structure; coincidental matches
 * collect one. Once a placement is pinned, EVERY storage block of that template - including ones
 * that are currently empty because a player looted them - has a known world position and known
 * original contents.
 *
 * Deliberately free of Minecraft types: rotations use the same four transforms vanilla does
 * (0, 90, 180, 270 degrees about the vertical axis). Because the origin is derived with the same
 * function used to place blocks, the result is self-consistent even if a rotation's label differs
 * from vanilla's.
 */
public final class StructureAligner {
    private StructureAligner() {}

    public record Itm(int slot, String id, int count) {}
    /** A storage block in a template, relative to the template origin. items may be empty. */
    public record Blk(int x, int y, int z, Set<Itm> items) {}
    /** A live container in the world with the contents it is currently known to hold. */
    public record Anch(int x, int y, int z, Set<Itm> items) {}
    /** A template block placed in the world; blockIndex indexes the template's Blk list. */
    public record Placed(int x, int y, int z, int blockIndex) {}
    public record Result(String templateId, int rotation, int originX, int originY, int originZ, int votes) {}

    private record Key(String id, int rot, int ox, int oy, int oz) {}

    static int rotX(int r, int x, int z) {
        return switch (r) { case 1 -> -z; case 2 -> -x; case 3 -> z; default -> x; };
    }
    static int rotZ(int r, int x, int z) {
        return switch (r) { case 1 -> x; case 2 -> -z; case 3 -> -x; default -> z; };
    }

    /**
     * Best placement for the given anchors, or null if none / ambiguous. A candidate placement
     * (template, rotation, origin) comes from an anchor whose contents exactly equal one of the
     * template's blocks; it is ACCEPTED only if the template's storage layout really fits the
     * world - every storage block lands on a live container (live), or at least 75% do and at
     * least two anchors agree. The layout check is what lets a single intact container per piece
     * pin a placement: wrong rotations/offsets don't line up with the other containers.
     * Among accepted candidates the one with the most agreeing anchors wins, then best coverage.
     * "Ambiguous" means equally-good placements that put different contents on the same
     * positions AND share an anchor; equally-good placements backed by disjoint anchors (two
     * instances of one template) are fine - one is returned and locateAll() picks up the rest.
     */
    public static Result locate(Map<String, List<Blk>> templates, List<Anch> anchors, Set<Long> live) {
        Map<Key, Set<Integer>> support = new HashMap<>();
        for (int ai = 0; ai < anchors.size(); ai++) {
            Anch a = anchors.get(ai);
            if (a.items().isEmpty()) continue;
            for (Map.Entry<String, List<Blk>> t : templates.entrySet()) {
                for (Blk b : t.getValue()) {
                    if (b.items().isEmpty() || !b.items().equals(a.items())) continue;
                    for (int r = 0; r < 4; r++) {
                        support.computeIfAbsent(new Key(t.getKey(), r,
                                a.x() - rotX(r, b.x(), b.z()), a.y() - b.y(), a.z() - rotZ(r, b.x(), b.z())),
                                k -> new HashSet<>()).add(ai);
                    }
                }
            }
        }
        Map<Key, Integer> coverage = new HashMap<>();
        List<Key> accepted = new ArrayList<>();
        int bestVotes = 0, bestCov = 0;
        for (Map.Entry<Key, Set<Integer>> e : support.entrySet()) {
            Key k = e.getKey();
            List<Blk> blocks = templates.get(k.id());
            int cov = 0;
            for (Blk b : blocks) {
                if (live.contains(pack(k.ox() + rotX(k.rot(), b.x(), b.z()), k.oy() + b.y(),
                        k.oz() + rotZ(k.rot(), b.x(), b.z())))) cov++;
            }
            int votes = e.getValue().size();
            boolean fits = cov == blocks.size() || (cov * 4 >= blocks.size() * 3 && votes >= 2);
            if (!fits) continue;
            coverage.put(k, cov);
            accepted.add(k);
            if (votes > bestVotes || (votes == bestVotes && cov > bestCov)) { bestVotes = votes; bestCov = cov; }
        }
        if (accepted.isEmpty()) return null;

        List<Key> top = new ArrayList<>();
        for (Key k : accepted) {
            if (support.get(k).size() == bestVotes && coverage.get(k) == bestCov) top.add(k);
        }
        for (int i = 0; i < top.size(); i++) {
            for (int j = i + 1; j < top.size(); j++) {
                Key ki = top.get(i), kj = top.get(j);
                if (Collections.disjoint(support.get(ki), support.get(kj))) continue;
                if (!signature(ki, templates.get(ki.id())).equals(signature(kj, templates.get(kj.id())))) return null;
            }
        }
        Key first = top.get(0);
        return new Result(first.id(), first.rot(), first.ox(), first.oy(), first.oz(), bestVotes);
    }

    /** How many anchors' contents exactly equal some template block's baked items - a cheap
     *  diagnostic: 0 means the template database can't explain these containers at all. */
    public static int countMatchingAnchors(Map<String, List<Blk>> templates, List<Anch> anchors) {
        int n = 0;
        for (Anch a : anchors) {
            boolean hit = false;
            for (List<Blk> blocks : templates.values()) {
                for (Blk b : blocks) if (!b.items().isEmpty() && b.items().equals(a.items())) { hit = true; break; }
                if (hit) break;
            }
            if (hit) n++;
        }
        return n;
    }

    /** World positions of every storage block of the template under the given placement. */
    public static List<Placed> place(Result r, List<Blk> template) {
        List<Placed> out = new ArrayList<>(template.size());
        for (int i = 0; i < template.size(); i++) {
            Blk b = template.get(i);
            out.add(new Placed(r.originX() + rotX(r.rotation(), b.x(), b.z()), r.originY() + b.y(),
                    r.originZ() + rotZ(r.rotation(), b.x(), b.z()), i));
        }
        return out;
    }

    private record PlacedItems(int x, int y, int z, Set<Itm> items) {}

    private static Set<PlacedItems> signature(Key k, List<Blk> template) {
        Set<PlacedItems> s = new HashSet<>();
        for (Blk b : template) {
            s.add(new PlacedItems(k.ox() + rotX(k.rot(), b.x(), b.z()), k.oy() + b.y(),
                    k.oz() + rotZ(k.rot(), b.x(), b.z()), b.items()));
        }
        return s;
    }

    /**
     * Repeatedly locates placements until no more can be pinned (a structure can be built from
     * many different templates/pieces). Returns every placed block. After each placement, its
     * anchors and positions are removed from consideration before the next round.
     */
    public static List<Map.Entry<String, Placed>> locateAll(Map<String, List<Blk>> templates,
                                                              List<Anch> anchors, Set<Long> live) {
        List<Map.Entry<String, Placed>> out = new ArrayList<>();
        List<Anch> remaining = new ArrayList<>(anchors);
        Set<Long> liveLeft = new HashSet<>(live);
        for (int round = 0; round < 256 && !remaining.isEmpty(); round++) {
            Result r = locate(templates, remaining, liveLeft);
            if (r == null) break;
            List<Placed> placed = place(r, templates.get(r.templateId()));
            Set<Long> covered = new HashSet<>();
            for (Placed p : placed) {
                out.add(Map.entry(r.templateId(), p));
                covered.add(pack(p.x(), p.y(), p.z()));
            }
            int before = remaining.size();
            remaining.removeIf(a -> covered.contains(pack(a.x(), a.y(), a.z())));
            liveLeft.removeAll(covered);
            if (remaining.size() == before) break; // no progress - never loop
        }
        return out;
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
