package dev.matt.letmereplace;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the picker offers, and in what order (ticket 005 P2, P3, P4, P9).
 *
 * <p>The candidate rule is a rule, not a list: a block qualifies when its own item is a
 * {@code BlockItem} pointing back at it. That is 1054 of the 1196 registry blocks in 26.2, and it
 * excludes {@code piston_head}, {@code bubble_column} and friends without anyone maintaining a
 * blacklist that rots every version. Air, water and lava are added back by name because builds
 * legitimately contain them and none of the three has an item of its own.
 */
public record Candidate(Block block,
                        Item item,
                        String displayName,
                        String id,
                        String normalisedName,
                        String normalisedId,
                        boolean isDelete) {

    /**
     * Built on demand, not cached with the catalogue. An {@code ItemStack} binds its item's data
     * components on construction, so building 1054 of them up front both allocates for nothing
     * and makes the catalogue unusable anywhere the components are not bound yet.
     */
    public ItemStack icon() {
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static List<Candidate> cached;

    public static synchronized List<Candidate> all() {
        if (cached == null) {
            cached = build();
        }

        return cached;
    }

    public static synchronized void clearCache() {
        cached = null;
    }

    private static List<Candidate> build() {
        List<Candidate> out = new ArrayList<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();

            if (item instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                out.add(of(block, item, false));
            }
        }

        out.sort(Comparator.comparing(Candidate::normalisedName).thenComparing(Candidate::normalisedId));

        // Ticket 001 edge 5: replacing into air is deletion, and the two fluids earn an exception
        // because a build that contains them cannot otherwise be converted away from them.
        out.add(0, of(Blocks.WATER, null, false));
        out.add(0, of(Blocks.LAVA, null, false));
        out.add(0, of(Blocks.AIR, null, true));

        return List.copyOf(out);
    }

    private static Candidate of(Block block, Item item, boolean isDelete) {
        Identifier identifier = BuiltInRegistries.BLOCK.getKey(block);
        String id = identifier == null ? "?" : identifier.toString();
        // The block's own name, not the item stack's: reading a stack's hover name would bind
        // data components, and the two agree for every BlockItem anyway.
        String name = isDelete ? "Delete (air)" : block.getName().getString();

        return new Candidate(block, item, name, id, normalise(name), normalise(id), isDelete);
    }

    /**
     * Ranking. LMR's tiers rewritten from their description rather than copied - the mod is
     * GPL-3.0-only: exact match, then prefix, then substring, then a subsequence fuzzy match.
     * Display name beats namespaced id at equal quality.
     *
     * <p>On top of that, ticket 005 P4's family bonus <em>reorders within a tier and never across
     * one</em>, so typing an exact id still wins outright. Family means sharing the source block's
     * id suffix after the first underscore, which is what puts {@code spruce_stairs} above
     * {@code spruce_planks} when you came from {@code oak_stairs}.
     */
    public static List<Candidate> ranked(String query, Block source) {
        String needle = normalise(query).trim();
        String family = familyOf(source);
        List<Candidate> matches = new ArrayList<>();

        for (Candidate candidate : all()) {
            int score = needle.isEmpty() ? 0 : candidate.score(needle);

            if (score != Integer.MAX_VALUE) {
                matches.add(candidate);
            }
        }

        if (needle.isEmpty()) {
            // P3: an empty query is everything, alphabetical - with delete pinned to the top,
            // since nobody will think to search for it by name (P8).
            matches.sort(Comparator.comparing((Candidate c) -> c.isDelete() ? 0 : 1)
                    .thenComparing(Candidate::normalisedName)
                    .thenComparing(Candidate::normalisedId));
            return matches;
        }

        matches.sort(Comparator
                .comparingInt((Candidate c) -> tier(c.score(needle)))
                .thenComparingInt(c -> familyOf(c.block()).equals(family) && !family.isEmpty() ? 0 : 1)
                .thenComparingInt(c -> c.score(needle))
                .thenComparingInt(c -> c.id().startsWith("minecraft:") ? 0 : 1)   // P9
                .thenComparing(Candidate::normalisedName)
                .thenComparing(Candidate::normalisedId));

        return matches;
    }

    private static int tier(int score) {
        if (score == 0) {
            return 0;
        }

        if (score < 100) {
            return 1;
        }

        return score < 1000 ? 2 : 3;
    }

    int score(String needle) {
        int byName = scoreText(normalisedName, needle);
        int byId = scoreText(normalisedId, needle);
        // Names beat ids at equal quality, so an id match is nudged down a little.
        int biasedId = byId == Integer.MAX_VALUE ? Integer.MAX_VALUE : byId + 2;

        return Math.min(byName, biasedId);
    }

    private static int scoreText(String value, String needle) {
        if (value.equals(needle)) {
            return 0;
        }

        if (value.startsWith(needle)) {
            return 10 + value.length() - needle.length();
        }

        int at = value.indexOf(needle);

        if (at >= 0) {
            return 100 + at + value.length() - needle.length();
        }

        int cursor = 0;
        int gaps = 0;

        for (int i = 0; i < needle.length(); ++i) {
            int found = value.indexOf(needle.charAt(i), cursor);

            if (found < 0) {
                return Integer.MAX_VALUE;
            }

            gaps += found - cursor;
            cursor = found + 1;
        }

        return 1000 + gaps + value.length() - needle.length();
    }

    /** {@code minecraft:oak_stairs} -> {@code stairs}; a block with no underscore has no family. */
    static String familyOf(Block block) {
        Identifier identifier = BuiltInRegistries.BLOCK.getKey(block);

        if (identifier == null) {
            return "";
        }

        String path = identifier.getPath();
        int underscore = path.indexOf('_');

        return underscore < 0 ? "" : path.substring(underscore + 1);
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
