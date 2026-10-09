package dev.matt.letmereplace;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which axes a schematic is actually on, and what a change along one would do.
 *
 * <p>Ticket 006. The swap itself is reused unchanged - this only chooses the block pairs and
 * hands them to {@link Swap}.
 */
public final class Palette {

    /** The literal source value meaning "every value present", per A7. */
    public static final String ALL = "(all)";

    private Palette() {
    }

    /** One axis the schematic is actually on, with the values found and their position counts. */
    public record Present(Axis axis, Map<String, Integer> valueCounts) {

        public List<String> valuesPresent() {
            return List.copyOf(valueCounts.keySet());
        }

        public int total() {
            int total = 0;

            for (int count : valueCounts.values()) {
                total += count;
            }

            return total;
        }
    }

    /**
     * A6: only axes present in the schematic are offered. Presence is a hard fact already
     * computed by the census, so there is nothing to guess and nothing to grey out.
     */
    public static List<Present> present(Census census) {
        List<Present> out = new ArrayList<>();

        for (Axis axis : Axis.all()) {
            Map<String, Integer> counts = new TreeMap<>();

            for (Map.Entry<BlockState, Integer> entry : census.counts().entrySet()) {
                if (entry.getKey().isAir()) {
                    continue;
                }

                String value = axis.valueOf(entry.getKey().getBlock());

                if (value != null) {
                    counts.merge(value, entry.getValue(), Integer::sum);
                }
            }

            if (!counts.isEmpty()) {
                out.add(new Present(axis, counts));
            }
        }

        return out;
    }

    /** One block that is changing, or one that cannot - for the confirm and the preview grid. */
    public record Pair(Block from, Block to) {
    }

    /**
     * The block pairs a change would produce, and the blocks that have no counterpart.
     *
     * <p>A2: every role with a counterpart is included, not just the universal ones - leaving
     * every oak <em>log</em> standing in a spruce build is the most visible half of the job.
     * A3: a member with no counterpart is skipped and counted, mirroring ticket 001's R3.
     */
    public record Change(Axis axis, String source, String target,
                         List<Pair> pairs, List<Block> noCounterpart) {

        public boolean isEmpty() {
            return pairs.isEmpty();
        }
    }

    public static Change change(Census census, Axis axis, String source, String target) {
        List<Pair> pairs = new ArrayList<>();
        List<Block> noCounterpart = new ArrayList<>();
        List<Block> seen = new ArrayList<>();

        for (BlockState state : census.counts().keySet()) {
            Block block = state.getBlock();

            if (state.isAir() || seen.contains(block)) {
                continue;
            }

            seen.add(block);
            String value = axis.valueOf(block);

            if (value == null || (!ALL.equals(source) && !value.equals(source))) {
                continue;
            }

            if (value.equals(target)) {
                continue;                                   // already there, nothing to do
            }

            Block counterpart = axis.at(block, target);

            if (counterpart == null || counterpart == block) {
                noCounterpart.add(block);
            } else {
                pairs.add(new Pair(block, counterpart));
            }
        }

        return new Change(axis, source, target, pairs, noCounterpart);
    }

    /**
     * Turns the pairs into the single state mapping {@link Swap} already knows how to apply.
     * Every pair goes through {@link Carry#onto}, so ticket 001's rule governs each one exactly
     * as it does a single-row Replace.
     */
    public static Map<Block, Block> asBlockMap(Change change) {
        Map<Block, Block> map = new LinkedHashMap<>();

        for (Pair pair : change.pairs()) {
            map.put(pair.from(), pair.to());
        }

        return map;
    }
}
