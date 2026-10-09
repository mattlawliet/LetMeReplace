package dev.matt.letmereplace;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planning and applying one material swap.
 *
 * <p>Two passes, both synchronous on the main thread: {@link #plan} counts without touching
 * anything so the confirm screen can be honest, then {@link #apply} does the same walk for real.
 * Ticket 004 D11 - staging a diff would cost memory proportional to hits, which on a water swap
 * in the guardian farm is 54,674 entries for no gain.
 */
public final class Swap {

    /** Cached because {@code newBlockEntity} allocates, and the picker asks about 1057 targets. */
    private static final Map<Block, BlockEntityType<?>> BE_TYPES = new IdentityHashMap<>();

    private Swap() {
    }

    /**
     * What a swap would do. {@code mapping} holds only the source states that convert; a state
     * absent from it is one whose positions are skipped and counted (ticket 001 R3/R5).
     */
    public record Plan(Block target,
                       Map<BlockState, BlockState> mapping,
                       int replaced,
                       int skipped,
                       int waterDropped,
                       int nbtDropped) {

        public boolean convertsNothing() {
            return replaced == 0;
        }

        public boolean isLossless() {
            return skipped == 0 && waterDropped == 0 && nbtDropped == 0;
        }
    }

    public static Plan plan(LitematicaSchematic schematic,
                            Map<BlockState, Integer> sourceStates,
                            Block target) {
        Map<BlockState, BlockState> mapping = new LinkedHashMap<>();
        int replaced = 0;
        int skipped = 0;
        int waterDropped = 0;

        for (Map.Entry<BlockState, Integer> entry : sourceStates.entrySet()) {
            BlockState carried = Carry.onto(entry.getKey(), target);

            if (carried == null) {
                skipped += entry.getValue();
                continue;
            }

            mapping.put(entry.getKey(), carried);
            replaced += entry.getValue();

            if (Carry.dropsWater(entry.getKey(), target)) {
                waterDropped += entry.getValue();
            }
        }

        return new Plan(target, mapping, replaced, skipped, waterDropped,
                countDroppedNbt(schematic, mapping));
    }

    /**
     * Applies a plan and returns how many positions were written. Marks the schematic dirty, and
     * records a single-level undo snapshot under {@code label} as it goes - only the positions
     * that actually change, plus any block entity NBT the swap removes.
     */
    public static int apply(LitematicaSchematic schematic, Plan plan, String label) {
        int written = 0;
        Map<String, it.unimi.dsi.fastutil.ints.Int2ObjectMap<BlockState>> previous =
                UndoLog.newStateMap();
        Map<String, Map<BlockPos, CompoundData>> removedNbt = UndoLog.newNbtMap();

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            Map<BlockPos, CompoundData> removedHere = new LinkedHashMap<>();
            dropNbt(schematic, region, container, plan.mapping(), false, removedHere);

            if (!removedHere.isEmpty()) {
                removedNbt.put(region, removedHere);
            }

            Vec3i size = container.getSize();
            int sizeX = size.getX();
            int layer = sizeX * size.getZ();
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<BlockState> before = UndoLog.newRegionMap();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        BlockState current = container.get(x, y, z);
                        BlockState replacement = plan.mapping().get(current);

                        // The mapping is state-to-state and says nothing about where, so a layer
                        // selection has to be tested here - this is the one place that decides
                        // which positions the swap actually writes to.
                        if (replacement != null && Scope.covers(schematic, region, x, y, z)) {
                            before.put(UndoLog.index(x, y, z, sizeX, layer), current);
                            container.set(x, y, z, replacement);
                            ++written;
                        }
                    }
                }
            }

            if (!before.isEmpty()) {
                previous.put(region, before);
            }
        }

        UndoLog.record(schematic, new UndoLog.Snapshot(label, previous, removedNbt));

        if (written > 0) {
            schematic.getMetadata().setModifiedSinceSaved();
            schematic.getMetadata().setTimeModifiedToNow();

            // The one call without which nothing appears to happen. Litematica renders from a
            // separate WorldSchematic built out of the placements, so mutating the container
            // changes nothing anybody can see until the placements are rebuilt from it. Its own
            // edit paths all end with this - SchematicUtils.setAllIdenticalSchematicBlockStates
            // does exactly the same thing after mutating.
            //
            // It is also why the material list looked stale: a placement-backed list recounts
            // with TaskCountBlocksPlacement, which reads the rendered world, so without the
            // rebuild it faithfully recounts the old blocks.
            Schematics.refreshRender(schematic);
        }

        return written;
    }

    private static int countDroppedNbt(LitematicaSchematic schematic,
                                       Map<BlockState, BlockState> mapping) {
        int dropped = 0;

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container != null) {
                dropped += dropNbt(schematic, region, container, mapping, true, null);
            }
        }

        return dropped;
    }

    /**
     * Ticket 004's amendment to the map: NBT survives only an <em>identical</em> block entity
     * type. Copying it across types is what would make chest-to-furnace keep three items and
     * discard twenty-four while reporting success, because {@code Items} is not chest-specific.
     */
    private static int dropNbt(LitematicaSchematic schematic,
                               String region,
                               LitematicaBlockStateContainer container,
                               Map<BlockState, BlockState> mapping,
                               boolean countOnly,
                               Map<BlockPos, CompoundData> removed) {
        Map<BlockPos, CompoundData> entities = schematic.getBlockEntityMapForRegion(region);

        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        Vec3i size = container.getSize();
        int dropped = 0;

        for (Iterator<Map.Entry<BlockPos, CompoundData>> it = entities.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<BlockPos, CompoundData> entry = it.next();
            BlockPos pos = entry.getKey();

            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                    || pos.getX() >= size.getX() || pos.getY() >= size.getY()
                    || pos.getZ() >= size.getZ()) {
                continue;                       // not a container-local position; leave it alone
            }

            BlockState current = container.get(pos.getX(), pos.getY(), pos.getZ());
            BlockState replacement = mapping.get(current);

            if (replacement == null
                    || !Scope.covers(schematic, region, pos.getX(), pos.getY(), pos.getZ())) {
                continue;                       // this position is not being touched
            }

            if (blockEntityType(current.getBlock()) != blockEntityType(replacement.getBlock())) {
                ++dropped;

                if (!countOnly) {
                    // Kept so undo can put the contents back - restoring a chest empty would be
                    // the very loss the identical-type rule exists to prevent.
                    if (removed != null) {
                        removed.put(pos, entry.getValue());
                    }

                    it.remove();
                }
            }
        }

        return dropped;
    }

    private static BlockEntityType<?> blockEntityType(Block block) {
        if (BE_TYPES.containsKey(block)) {
            return BE_TYPES.get(block);
        }

        BlockEntityType<?> type = null;

        if (block instanceof EntityBlock entityBlock) {
            try {
                BlockEntity entity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                type = entity == null ? null : entity.getType();
            } catch (Exception e) {
                // Some blocks refuse to build a block entity outside a world. Treating that as
                // "no block entity" only ever drops NBT, which is the counted, reported outcome.
                type = null;
            }
        }

        BE_TYPES.put(block, type);
        return type;
    }

    /** Cleared on world change so a modded registry swap cannot leave stale types behind. */
    public static void clearCaches() {
        BE_TYPES.clear();
    }

    /**
     * The same plan across many block pairs at once - a palette change (ticket 006). Every pair
     * goes through {@link Carry#onto} exactly as a single-row Replace does, so ticket 001's rule
     * governs each one and nothing new decides anything here.
     */
    public static Plan planPairs(LitematicaSchematic schematic,
                                 Census census,
                                 Map<Block, Block> pairs) {
        Map<BlockState, BlockState> mapping = new LinkedHashMap<>();
        int replaced = 0;
        int skipped = 0;
        int waterDropped = 0;

        for (Map.Entry<BlockState, Integer> entry : census.counts().entrySet()) {
            Block target = pairs.get(entry.getKey().getBlock());

            if (target == null) {
                continue;
            }

            BlockState carried = Carry.onto(entry.getKey(), target);

            if (carried == null) {
                skipped += entry.getValue();
                continue;
            }

            mapping.put(entry.getKey(), carried);
            replaced += entry.getValue();

            if (Carry.dropsWater(entry.getKey(), target)) {
                waterDropped += entry.getValue();
            }
        }

        return new Plan(null, mapping, replaced, skipped, waterDropped,
                countDroppedNbt(schematic, mapping));
    }

    /**
     * Just the converted and skipped counts, without touching the schematic - the picker needs
     * this for all 1057 candidates at once (ticket 005 P7) and the full {@link #plan} would walk
     * every sub-region 1057 times to answer a question pure map iteration already answers.
     */
    public static int[] quickCounts(Map<BlockState, Integer> sourceStates, Block target) {
        int replaced = 0;
        int skipped = 0;

        for (Map.Entry<BlockState, Integer> entry : sourceStates.entrySet()) {
            if (Carry.onto(entry.getKey(), target) == null) {
                skipped += entry.getValue();
            } else {
                replaced += entry.getValue();
            }
        }

        return new int[] {replaced, skipped};
    }
}
