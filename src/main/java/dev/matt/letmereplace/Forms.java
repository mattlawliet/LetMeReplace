package dev.matt.letmereplace;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Floor and wall forms of the same thing, which Minecraft models as two <em>blocks</em> rather
 * than two states: {@code torch} and {@code wall_torch}, {@code oak_sign} and
 * {@code oak_wall_sign}, banners, heads, coral fans - 61 pairs in 26.2.
 *
 * <p>Both halves resolve to one item and therefore one material-list row (ticket 001 edge 4), so
 * a single Replace hits both. Without this, a wall torch swapped to {@code soul_torch} became a
 * <em>floor</em> soul torch stuck to nothing: a perfectly legal state and the wrong answer, since
 * the carry rule dropped {@code facing} under R2 because the floor block has no such property.
 *
 * <p>The wall halves have no item of their own, so they never appear in the picker. The
 * adjustment therefore happens inside the swap, where the source block's form is known.
 */
public final class Forms {

    private static final String SEGMENT = "wall_";

    private static Map<Block, Block> floorToWall;
    private static Map<Block, Block> wallToFloor;

    private Forms() {
    }

    private static synchronized void build() {
        if (floorToWall != null) {
            return;
        }

        floorToWall = new HashMap<>();
        wallToFloor = new HashMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier key = BuiltInRegistries.BLOCK.getKey(block);

            if (key == null) {
                continue;
            }

            String path = key.getPath();
            int at = path.indexOf(SEGMENT);

            // The segment must start a path segment, so "wall_torch" and "oak_wall_sign" match
            // while "cobblestone_wall" does not.
            if (at < 0 || (at > 0 && path.charAt(at - 1) != '_')) {
                continue;
            }

            String withoutWall = path.substring(0, at) + path.substring(at + SEGMENT.length());
            Identifier floorKey = Identifier.tryParse("minecraft:" + withoutWall);

            if (floorKey == null || !BuiltInRegistries.BLOCK.containsKey(floorKey)) {
                continue;
            }

            Block floor = BuiltInRegistries.BLOCK.getValue(floorKey);

            if (floor != null) {
                floorToWall.put(floor, block);
                wallToFloor.put(block, floor);
            }
        }
    }

    public static boolean isWallForm(Block block) {
        build();
        return wallToFloor.containsKey(block);
    }

    /** The floor twin of a wall-mounted block, or the block itself when it has none. */
    public static Block floorFormOf(Block block) {
        build();
        return wallToFloor.getOrDefault(block, block);
    }

    /**
     * The target in the same form as the source: a wall-mounted source gets the target's wall
     * twin, a floor source gets the target's floor twin. Falls back to the target unchanged when
     * it has no twin - replacing a wall torch with stone is still stone.
     */
    public static Block matching(Block source, Block target) {
        build();

        if (isWallForm(source)) {
            return floorToWall.getOrDefault(target, target);
        }

        // A floor source should not land on a wall block either, though the picker does not
        // currently offer one.
        return wallToFloor.getOrDefault(target, target);
    }

    /** For the tests and for reporting; 61 in 26.2. */
    public static int pairCount() {
        build();
        return floorToWall.size();
    }
}
