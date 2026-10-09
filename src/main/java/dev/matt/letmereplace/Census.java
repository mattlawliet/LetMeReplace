package dev.matt.letmereplace;

import fi.dy.masa.litematica.materials.MaterialCache;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every distinct block state in a loaded schematic, with how many positions hold it.
 *
 * <p>One pass over every sub-region. On the 51x123x51 guardian farm that is 320k container
 * reads, which is single-digit milliseconds - hence 004's decision to plan and apply as two
 * plain synchronous passes rather than staging a diff or reaching for a task system.
 */
public record Census(Map<BlockState, Integer> counts) {

    public static Census of(LitematicaSchematic schematic) {
        Map<BlockState, Integer> counts = new LinkedHashMap<>();

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            Vec3i size = container.getSize();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        // Counted only where a replace could actually land, so every number the
                        // picker, the palette screen and the confirm show is the number of
                        // positions that will change. See Scope.
                        if (Scope.covers(schematic, region, x, y, z)) {
                            counts.merge(container.get(x, y, z), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        return new Census(counts);
    }

    /**
     * The states a material row covers: every state of every block whose build item is that
     * row's item, per ticket 001 edge 4. Deliberately several blocks in some rows - {@code kelp}
     * and {@code kelp_plant} share one item, and the row's count already sums both.
     *
     * <p>Matched on the <strong>block</strong>, not the state, and that distinction is the whole
     * point. Litematica's {@code getRequiredBuildItemForState} answers "what would you have to
     * hold to build this", so it deliberately returns nothing for states you do not pay for:
     * flowing water, door upper halves, bed heads, the tops of tall plants. Filtering states by
     * that item left every one of them out of the swap - reported from the instance as water
     * that would not vanish, and it would have orphaned the top half of every door.
     */
    public Map<BlockState, Integer> statesForItem(Item item) {
        Map<Block, Item> representative = representativeItems();
        Map<BlockState, Integer> out = new LinkedHashMap<>();

        for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
            if (entry.getKey().isAir()) {
                continue;
            }

            if (representative.get(entry.getKey().getBlock()) == item) {
                out.put(entry.getKey(), entry.getValue());
            }
        }

        return out;
    }

    /**
     * One item per block: the first of its states that costs something, falling back to the
     * block's own item for blocks that never cost anything at all.
     */
    private Map<Block, Item> representativeItems() {
        Map<Block, Item> out = new HashMap<>();
        MaterialCache cache = MaterialCache.getInstance();

        for (BlockState state : counts.keySet()) {
            if (state.isAir()) {
                continue;
            }

            Block block = state.getBlock();

            // Fluids first. Litematica only charges for a source block, so every flowing state
            // resolves to nothing, and a fluid's asItem() is AIR - so the fallback below cannot
            // rescue it either. The picker already grants water and lava an exception for the
            // same reason (ticket 001 edge 5); this is that exception on the source side.
            if (block == Blocks.WATER) {
                out.put(block, Items.WATER_BUCKET);
                continue;
            }

            if (block == Blocks.LAVA) {
                out.put(block, Items.LAVA_BUCKET);
                continue;
            }

            ItemStack required = cache.getRequiredBuildItemForState(state);

            if (!required.isEmpty()) {
                out.put(block, required.getItem());          // a paying state wins outright
            } else {
                // Blocks that never cost anything - the top half of a door, a bed head - are
                // covered by whichever of their states does.
                out.putIfAbsent(block, ownerItem(block));
            }
        }

        return out;
    }

    /** The state most positions hold, which is the one the picker previews (ticket 005 P5). */
    public static BlockState modal(Map<BlockState, Integer> states) {
        BlockState best = null;
        int bestCount = -1;

        for (Map.Entry<BlockState, Integer> entry : states.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }

        return best;
    }

    public static int total(Map<BlockState, Integer> states) {
        int total = 0;

        for (int count : states.values()) {
            total += count;
        }

        return total;
    }

    /** Distinct blocks behind a row, for labelling when a row covers more than one. */
    public static Map<String, Integer> blockNames(Map<BlockState, Integer> states) {
        Map<String, Integer> out = new HashMap<>();

        for (Map.Entry<BlockState, Integer> entry : states.entrySet()) {
            out.merge(entry.getKey().getBlock().getDescriptionId(), entry.getValue(), Integer::sum);
        }

        return out;
    }

    /**
     * The row a block belongs to when it has no item and no state that costs anything.
     *
     * <p>139 blocks in 26.2 have no item of their own, and leaving them unmapped means they
     * belong to no material row and can never be replaced. Reported from the instance twice:
     * wall torches were never in the swap set at all - the {@code Forms} fix was carrying a
     * mounting form for blocks that could not be selected in the first place - and deleting the
     * water left {@code kelp_plant} and {@code bubble_column} behind.
     *
     * <p>Three derivations, no table: a wall-mounted form belongs to its floor twin's row (61
     * pairs, torches through coral fans); an {@code X_plant} or {@code X_stem} belongs to
     * {@code X} (kelp, cave vines, twisting and weeping vines, big dripleaf); and a bubble column
     * is part of the water it rises through. Anything still unmapped - piston heads, portals -
     * genuinely has no owner and stays out.
     */
    private static Item ownerItem(Block block) {
        if (block == Blocks.BUBBLE_COLUMN) {
            return Items.WATER_BUCKET;
        }

        if (Forms.isWallForm(block)) {
            Item floor = Forms.floorFormOf(block).asItem();

            if (floor != Items.AIR) {
                return floor;
            }
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        if (id != null) {
            for (String suffix : new String[] {"_plant", "_stem"}) {
                if (id.getPath().endsWith(suffix)) {
                    Identifier base = Identifier.tryParse("minecraft:"
                            + id.getPath().substring(0, id.getPath().length() - suffix.length()));

                    if (base != null && BuiltInRegistries.BLOCK.containsKey(base)) {
                        Item item = BuiltInRegistries.BLOCK.getValue(base).asItem();

                        if (item != Items.AIR) {
                            return item;
                        }
                    }
                }
            }
        }

        return block.asItem();
    }
}
