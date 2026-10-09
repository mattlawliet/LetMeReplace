package dev.matt.letmereplace;

import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which half a slab lands on, when a swap makes slabs.
 *
 * <p>Asked for at the keyboard 2026-09-21. The carry rule gets a slab's half right when there was
 * one to keep - slab to slab preserves {@code type} under R1 - but a full block has no half, so
 * every one of them landed on {@code bottom}, which is the target's default under R4 and not a
 * choice anybody made. Replacing a floor with top slabs meant replacing it with bottom slabs and
 * then fixing 1,200 positions by hand.
 *
 * <p>ponytail: this rewrites a finished {@link Swap.Plan} instead of being threaded back through
 * {@link Carry} and both planners. A slab's half changes no count - {@code top} is legal wherever
 * {@code bottom} is, it costs the same item and carries no block entity - so the plan's replaced,
 * skipped, water and NBT numbers are the same plan's numbers whichever half is chosen, and the
 * confirm screen can offer the choice without re-walking the schematic. One button, one place, and
 * it covers a row Replace and a palette change alike because both arrive at that one screen.
 */
public final class Slabs {

    private Slabs() {
    }

    /**
     * The choice. {@code KEEP} is the carry rule's own answer, which is the right default: it is
     * the only one that cannot surprise anybody who is swapping slabs for slabs.
     */
    public enum Side {

        KEEP("Slabs: keep", "Each slab keeps the half it had; a new slab lands on the bottom"),
        BOTTOM("Slabs: bottom", "Every single slab on the bottom half"),
        TOP("Slabs: top", "Every single slab on the top half");

        public final String label;
        public final String hover;

        Side(String label, String hover) {
            this.label = label;
            this.hover = hover;
        }

        public Side next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Whether this plan makes any single slab, and so whether the choice means anything. */
    public static boolean writesSlabs(Swap.Plan plan) {
        for (BlockState state : plan.mapping().values()) {
            if (isSingleSlab(state)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The same plan with every single slab put on the chosen half.
     *
     * <p>Double slabs are deliberately left alone. A double slab forced onto one half would throw
     * away the second half's worth of material silently, and this screen's whole job is to not do
     * that - the choice is which half a <em>single</em> slab sits on.
     */
    public static Swap.Plan applied(Swap.Plan plan, Side side) {
        if (side == Side.KEEP) {
            return plan;
        }

        SlabType half = side == Side.TOP ? SlabType.TOP : SlabType.BOTTOM;
        Map<BlockState, BlockState> mapping = new LinkedHashMap<>();

        for (Map.Entry<BlockState, BlockState> entry : plan.mapping().entrySet()) {
            BlockState to = entry.getValue();

            mapping.put(entry.getKey(), isSingleSlab(to)
                    ? to.setValue(BlockStateProperties.SLAB_TYPE, half)
                    : to);
        }

        return new Swap.Plan(plan.target(), mapping, plan.replaced(), plan.skipped(),
                plan.waterDropped(), plan.nbtDropped());
    }

    /**
     * Matched on {@link SlabBlock} and the property together, not on the block alone: the property
     * is the thing being written, and a modded slab that models its half some other way must be
     * left to whatever the carry rule made of it rather than crashed on.
     */
    private static boolean isSingleSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
    }
}
