package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One orientation for the blocks whose orientation cannot be seen.
 *
 * <p>Asked for at the keyboard 2026-09-21. A bark block is bark on all six faces, so its
 * {@code axis} is invisible in the build and visible only in the hand: to match a schematic that
 * happens to hold {@code axis=x} you have to place the block against the right face, and getting
 * it wrong leaves a block that looks finished and reads as wrong. Turning all of them upright is
 * the difference between placing them and thinking about placing them.
 *
 * <p>Deliberately <strong>not</strong> logs. A log's ends are a different texture from its sides,
 * so its axis is the whole point of it - this only covers the blocks where the game itself cannot
 * tell you which way round they are.
 *
 * <p>Derived from the id rather than listed, the way {@link Census} and {@link Forms} derive their
 * families: everything whose path ends {@code _wood} or {@code _hyphae}, which is the 18 wood
 * blocks, the 2 hyphae and every stripped variant of both, and picks up a modded wood that follows
 * the convention for free. {@code bamboo_block} is not one of them and is correctly left out - it
 * has ends.
 */
public final class Normalize {

    /** Upright, which is what placing one on the ground gives you - so it is the one to match. */
    public static final Direction.Axis UPRIGHT = Direction.Axis.Y;

    private Normalize() {
    }

    /** Whether this state is a bark block lying on its side, and so worth standing up. */
    public static boolean turns(BlockState state) {
        return isBark(state.getBlock())
                && state.hasProperty(BlockStateProperties.AXIS)
                && state.getValue(BlockStateProperties.AXIS) != UPRIGHT;
    }

    public static boolean isBark(Block block) {
        if (!(block instanceof RotatedPillarBlock)) {
            return false;
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        if (id == null) {
            return false;
        }

        String path = id.getPath();

        return path.endsWith("_wood") || path.endsWith("_hyphae");
    }

    /**
     * What standing them all up would do, as an ordinary {@link Swap.Plan}.
     *
     * <p>A plan rather than a walk of its own, so this reaches the same confirm screen, the same
     * undo, the same pending count and the same layer selection as every other change - the only
     * thing new here is which pairs go in the mapping. Nothing is skipped and nothing is lost:
     * {@code axis} is legal on every one of these blocks in all three values, they hold no block
     * entity, and no other property is touched.
     */
    public static Swap.Plan plan(Census census) {
        Map<BlockState, BlockState> mapping = new LinkedHashMap<>();
        int replaced = 0;

        for (Map.Entry<BlockState, Integer> entry : census.counts().entrySet()) {
            if (!turns(entry.getKey())) {
                continue;
            }

            mapping.put(entry.getKey(),
                    entry.getKey().setValue(BlockStateProperties.AXIS, UPRIGHT));
            replaced += entry.getValue();
        }

        return new Swap.Plan(null, mapping, replaced, 0, 0, 0);
    }

    /**
     * One line per block being turned, so the confirm names what it is about to touch rather than
     * only counting it - the same reason a palette change lists the members it has to skip.
     */
    public static List<String> notes(Census census) {
        Map<String, Integer> counts = new TreeMap<>();

        for (Map.Entry<BlockState, Integer> entry : census.counts().entrySet()) {
            if (turns(entry.getKey())) {
                counts.merge(name(entry.getKey().getBlock()), entry.getValue(), Integer::sum);
            }
        }

        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue() + " turned upright")
                .toList();
    }

    private static String name(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        return id == null ? block.getDescriptionId() : id.getPath();
    }
}
