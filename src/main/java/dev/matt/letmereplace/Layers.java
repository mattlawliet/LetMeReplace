package dev.matt.letmereplace;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Subtracting whole layers from the loaded schematic.
 *
 * <p>The same {@link LayerRange} that drives Litematica's render layers menu - the four modes,
 * the three axes, the same numbers - except that the layers it selects are <em>removed</em>
 * rather than hidden. Set the view up until you are looking at what you want gone, then cut it.
 *
 * <p>Two passes like {@link Swap}: {@link #plan} counts without touching anything so the confirm
 * screen can be honest, then {@link #apply} does the same walk for real and records one undo
 * snapshot.
 */
public final class Layers {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private Layers() {
    }

    /**
     * What a cut would do. {@code kept} counts the non-air blocks that survive, which is what
     * makes "this removes the whole schematic" visible before it happens rather than after.
     *
     * <p>{@code entities} is counted separately because it is not made of blocks: a layer can
     * hold an armour stand, an item frame or a minecart and no blocks at all, and a cut that
     * reported "removes 0" while deleting them - or worse, left them floating in the empty space
     * it had just made - is the bug this count exists to make impossible.
     */
    public record Plan(int removed, int kept, int blockEntities, int entities) {

        public boolean removesNothing() {
            return removed == 0 && entities == 0;
        }

        public boolean removesEverything() {
            return kept == 0 && removed > 0;
        }
    }

    public static Plan plan(LitematicaSchematic schematic,
                            SchematicPlacement placement,
                            LayerRange range) {
        int removed = 0;
        int kept = 0;
        int blockEntities = 0;
        int entities = 0;

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            ToWorld toWorld = ToWorld.forRegion(schematic, placement, region);

            if (toWorld == null) {
                continue;
            }

            Vec3i size = container.getSize();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        if (container.get(x, y, z).isAir()) {
                            continue;
                        }

                        if (toWorld.isWithin(range, x, y, z)) {
                            ++removed;
                        } else {
                            ++kept;
                        }
                    }
                }
            }

            blockEntities += countBlockEntities(schematic, region, container, toWorld, range);
            entities += countEntities(schematic, region, toWorld, range);
        }

        return new Plan(removed, kept, blockEntities, entities);
    }

    /**
     * Cuts the selected layers and returns how much was removed - blocks and entities together,
     * because a layer holding only an armour stand is still a layer that was cut, and every
     * caller uses this number to decide whether anything happened. Records a single undo
     * snapshot under {@code label}, holding the states, any block entity NBT and any entities
     * that went with them - putting a chest back empty would be the loss undo exists to prevent.
     */
    public static int apply(LitematicaSchematic schematic,
                            SchematicPlacement placement,
                            LayerRange range,
                            String label) {
        int removed = 0;
        int removedEntities = 0;
        Map<String, Int2ObjectMap<BlockState>> previous = UndoLog.newStateMap();
        Map<String, Map<BlockPos, CompoundData>> removedNbt = UndoLog.newNbtMap();
        Map<String, Map<BlockPos, ScheduledTick<Block>>> removedBlockTicks = new LinkedHashMap<>();
        Map<String, Map<BlockPos, ScheduledTick<Fluid>>> removedFluidTicks = new LinkedHashMap<>();
        Map<String, List<EntityInfo>> removedEntityList = new LinkedHashMap<>();

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            ToWorld toWorld = ToWorld.forRegion(schematic, placement, region);

            if (toWorld == null) {
                continue;
            }

            Map<BlockPos, CompoundData> removedHere = new LinkedHashMap<>();
            dropBlockEntities(schematic, region, container, toWorld, range, removedHere);

            if (!removedHere.isEmpty()) {
                removedNbt.put(region, removedHere);
            }

            // A layer is removed whatever it is made of, and water is not only the block: a
            // scheduled fluid tick left at a cleared position puts the water back the next time
            // the schematic is pasted. Block ticks go the same way, for the same reason.
            Map<BlockPos, ScheduledTick<Block>> blockTicksGone =
                    dropTicks(schematic.getScheduledBlockTicksForRegion(region), container,
                            toWorld, range);

            if (!blockTicksGone.isEmpty()) {
                removedBlockTicks.put(region, blockTicksGone);
            }

            Map<BlockPos, ScheduledTick<Fluid>> fluidTicksGone =
                    dropTicks(schematic.getScheduledFluidTicksForRegion(region), container,
                            toWorld, range);

            if (!fluidTicksGone.isEmpty()) {
                removedFluidTicks.put(region, fluidTicksGone);
            }

            // Entities are not in the container at all - they are a separate list with their own
            // coordinates - so clearing the blocks leaves them standing in the space that was
            // just emptied. Reported at the keyboard 2026-09-20.
            List<EntityInfo> entitiesGone = dropEntities(schematic, region, toWorld, range);

            if (!entitiesGone.isEmpty()) {
                removedEntityList.put(region, entitiesGone);
                removedEntities += entitiesGone.size();
            }

            Vec3i size = container.getSize();
            int sizeX = size.getX();
            int layer = sizeX * size.getZ();
            Int2ObjectMap<BlockState> before = UndoLog.newRegionMap();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        BlockState current = container.get(x, y, z);

                        // Air is skipped rather than rewritten: it changes nothing, and storing
                        // it would blow the undo budget on a mostly-empty schematic.
                        if (current.isAir() || !toWorld.isWithin(range, x, y, z)) {
                            continue;
                        }

                        before.put(UndoLog.index(x, y, z, sizeX, layer), current);
                        container.set(x, y, z, AIR);
                        ++removed;
                    }
                }
            }

            if (!before.isEmpty()) {
                previous.put(region, before);
            }
        }

        UndoLog.record(schematic,
                new UndoLog.Snapshot(label, previous, removedNbt, removedBlockTicks,
                        removedFluidTicks, removedEntityList));

        // Read the selection back out of the container we just wrote to. If anything is still
        // standing in it, the cut did not do what it reported and the difference says so here
        // rather than in a schematic somebody saved.
        Plan after = plan(schematic, placement, range);

        LetMeReplace.LOGGER.info(
                "cut '{}': removed {} blocks, {} block entities, {} entities; selection now holds"
                        + " {} blocks and {} entities",
                label, removed, removedNbt.values().stream().mapToInt(Map::size).sum(),
                removedEntities, after.removed(), after.entities());

        if (after.removed() > 0 || after.entities() > 0) {
            LetMeReplace.LOGGER.error(
                    "cut left {} blocks and {} entities inside the selection - the removal is"
                            + " incomplete",
                    after.removed(), after.entities());
        }

        if (removed > 0 || removedEntities > 0) {
            Schematics.refreshCounts(schematic);
            schematic.getMetadata().setModifiedSinceSaved();
            schematic.getMetadata().setTimeModifiedToNow();
            // Without this the placements keep rendering the layers we just cut - see Swap.apply,
            // where the same omission made the whole mod look like it did nothing.
            Schematics.refreshRender(schematic);
        }

        return removed + removedEntities;
    }

    /** Whether a container position is inside a layer selection. See {@link Scope}. */
    public interface Mask {

        boolean covers(String region, int x, int y, int z);
    }

    /**
     * A layer selection frozen for later, so a replace can be confined to it.
     *
     * <p>The range is <strong>copied</strong>: the screen's own range keeps moving under the
     * controls after the button is pressed, and a scope that followed it would confine a swap to
     * layers the user never chose. {@code LayerRange.copy} assigns the fields directly rather than
     * going through the clamping setters, so it is exact for any mode.
     *
     * <p>The geometry is the cut's own {@link ToWorld} - the same transform, the same linearity
     * sampling, the same refusal to guess at a region with no sub-region placement. A replace
     * inside a selection has to agree with the highlight the user was looking at, and the way to
     * guarantee that is to ask the same object rather than a second implementation of it.
     */
    public static Mask mask(LitematicaSchematic schematic,
                            SchematicPlacement placement,
                            LayerRange range) {
        LayerRange frozen = range.copy();

        return new Mask() {

            // ponytail: one-entry memo, not a map - every caller walks one region to completion
            // before moving to the next, so this is one ToWorld build per region either way.
            private String lastRegion;
            private ToWorld toWorld;

            @Override
            public boolean covers(String region, int x, int y, int z) {
                if (!region.equals(lastRegion)) {
                    lastRegion = region;
                    toWorld = ToWorld.forRegion(schematic, placement, region);
                }

                return toWorld != null && toWorld.isWithin(frozen, x, y, z);
            }
        };
    }

    /**
     * Where the schematic actually sits on one world axis, as {@code {min, max}} inclusive, or
     * {@code null} when it has no sub-regions.
     *
     * <p>The layer numbers are world coordinates, so on X and Z they are wherever the placement
     * happens to be - which is nowhere near the Y values the render menu was last left on. Without
     * this the screen can only say "nothing selected" and leave you guessing what to type.
     *
     * <p>Two opposite corners are enough: the transform is an axis permutation with signs, so the
     * image of a box is the box spanned by the images of its corners.
     */
    public static int[] worldSpan(LitematicaSchematic schematic,
                                  SchematicPlacement placement,
                                  Direction.Axis axis) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            ToWorld toWorld = ToWorld.forRegion(schematic, placement, region);

            if (toWorld == null) {
                continue;
            }

            Vec3i size = container.getSize();

            int near = toWorld.coordinate(axis, 0, 0, 0);
            int far = toWorld.coordinate(axis, size.getX() - 1, size.getY() - 1, size.getZ() - 1);

            min = Math.min(min, Math.min(near, far));
            max = Math.max(max, Math.max(near, far));
        }

        return min > max ? null : new int[] {min, max};
    }

    /**
     * Checks our idea of where each region sits against Litematica's, and says nothing unless they
     * disagree.
     *
     * <p>Litematica computes the world box for every sub-region itself, so it is the ground truth
     * for whether {@code ToWorld} agrees with the placement - rotation, mirror, and the negative
     * region sizes that put the whole build on the wrong side of its own origin. A disagreement
     * means the layer numbers name a different part of the build than the one they highlight, and
     * a cut would land on the wrong plane.
     *
     * <p>This is the regression net for that bug. It cannot live in {@code LayerCheck}, which runs
     * headless and has no placement to test against, so it runs here instead - against the real
     * placement, every time the screen opens, for free when everything agrees.
     */
    public static void auditAgainstPlacement(LitematicaSchematic schematic,
                                             SchematicPlacement placement) {
        if (placement == null) {
            return;
        }

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            ToWorld toWorld = ToWorld.forRegion(schematic, placement, region);

            if (container == null || toWorld == null) {
                continue;
            }

            Vec3i size = container.getSize();
            StringBuilder ours = new StringBuilder();

            for (Direction.Axis axis : Direction.Axis.values()) {
                int near = toWorld.coordinate(axis, 0, 0, 0);
                int far = toWorld.coordinate(axis, size.getX() - 1, size.getY() - 1, size.getZ() - 1);
                ours.append(' ').append(axis.getSerializedName()).append('=')
                        .append(Math.min(near, far)).append("..").append(Math.max(near, far));
            }

            Box box = placement.getSubRegionBoxFor(region, SubRegionPlacement.RequiredEnabled.ANY)
                    .get(region);

            if (box == null || agrees(box, toWorld, size)) {
                continue;
            }

            LetMeReplace.LOGGER.error(
                    "region '{}' does not agree with its placement - layer numbers would name the"
                            + " wrong part of the build. size {}x{}x{} | ours{} | litematica {} .. {}",
                    region, size.getX(), size.getY(), size.getZ(), ours,
                    box.getPos1(), box.getPos2());
        }
    }

    /** Both corners of our mapping fall on the box Litematica computed for the same region. */
    private static boolean agrees(Box box, ToWorld toWorld, Vec3i size) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            int near = toWorld.coordinate(axis, 0, 0, 0);
            int far = toWorld.coordinate(axis, size.getX() - 1, size.getY() - 1, size.getZ() - 1);

            int theirsOne = box.getPos1().get(axis);
            int theirsTwo = box.getPos2().get(axis);

            if (Math.min(near, far) != Math.min(theirsOne, theirsTwo)
                    || Math.max(near, far) != Math.max(theirsOne, theirsTwo)) {
                return false;
            }
        }

        return true;
    }

    /**
     * The corner a region's local block coordinates count from, given where the region sits and
     * how big it is.
     *
     * <p>A sub-region's stored size can be negative, meaning it extends back from its own
     * position rather than forward from it. Litematica indexes the container from the min corner
     * either way, so block {@code (0,0,0)} is at this position and not necessarily at the
     * region's own. Zero difference for a positive-size region, which is nearly all of them.
     */
    public static BlockPos minCorner(BlockPos regionPos, Vec3i areaSize) {
        if (areaSize == null) {
            return regionPos;
        }

        BlockPos end = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(areaSize))
                .offset(regionPos);

        return PositionUtils.getMinCorner(regionPos, end);
    }

    private static int countBlockEntities(LitematicaSchematic schematic,
                                          String region,
                                          LitematicaBlockStateContainer container,
                                          ToWorld toWorld,
                                          LayerRange range) {
        Map<BlockPos, CompoundData> entities = schematic.getBlockEntityMapForRegion(region);

        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        int counted = 0;

        for (BlockPos pos : entities.keySet()) {
            if (within(container, pos) && toWorld.isWithin(range, pos.getX(), pos.getY(), pos.getZ())) {
                ++counted;
            }
        }

        return counted;
    }

    private static int countEntities(LitematicaSchematic schematic,
                                     String region,
                                     ToWorld toWorld,
                                     LayerRange range) {
        List<EntityInfo> entities = schematic.getEntityListForRegion(region);

        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        int counted = 0;

        for (EntityInfo entity : entities) {
            if (toWorld.isEntityWithin(range, entity.posVec())) {
                ++counted;
            }
        }

        return counted;
    }

    /** Takes every entity standing in the cut out of the region, and hands them back for undo. */
    private static List<EntityInfo> dropEntities(LitematicaSchematic schematic,
                                                 String region,
                                                 ToWorld toWorld,
                                                 LayerRange range) {
        List<EntityInfo> removed = new ArrayList<>();
        List<EntityInfo> entities = schematic.getEntityListForRegion(region);

        if (entities == null || entities.isEmpty()) {
            return removed;
        }

        for (Iterator<EntityInfo> it = entities.iterator(); it.hasNext(); ) {
            EntityInfo entity = it.next();

            if (toWorld.isEntityWithin(range, entity.posVec())) {
                removed.add(entity);
                it.remove();
            }
        }

        return removed;
    }

    /** Removes the NBT of every block entity in the cut, keeping it for undo. */
    private static void dropBlockEntities(LitematicaSchematic schematic,
                                          String region,
                                          LitematicaBlockStateContainer container,
                                          ToWorld toWorld,
                                          LayerRange range,
                                          Map<BlockPos, CompoundData> removed) {
        Map<BlockPos, CompoundData> entities = schematic.getBlockEntityMapForRegion(region);

        if (entities == null || entities.isEmpty()) {
            return;
        }

        for (Iterator<Map.Entry<BlockPos, CompoundData>> it = entities.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<BlockPos, CompoundData> entry = it.next();
            BlockPos pos = entry.getKey();

            if (within(container, pos)
                    && toWorld.isWithin(range, pos.getX(), pos.getY(), pos.getZ())) {
                removed.put(pos, entry.getValue());
                it.remove();
            }
        }
    }

    /** Takes every scheduled tick inside the cut out of {@code live}, and hands them back. */
    private static <T> Map<BlockPos, T> dropTicks(Map<BlockPos, T> live,
                                                  LitematicaBlockStateContainer container,
                                                  ToWorld toWorld,
                                                  LayerRange range) {
        Map<BlockPos, T> removed = new LinkedHashMap<>();

        if (live == null || live.isEmpty()) {
            return removed;
        }

        for (Iterator<Map.Entry<BlockPos, T>> it = live.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, T> entry = it.next();
            BlockPos pos = entry.getKey();

            if (within(container, pos)
                    && toWorld.isWithin(range, pos.getX(), pos.getY(), pos.getZ())) {
                removed.put(pos, entry.getValue());
                it.remove();
            }
        }

        return removed;
    }

    private static boolean within(LitematicaBlockStateContainer container, BlockPos pos) {
        Vec3i size = container.getSize();

        return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0
                && pos.getX() < size.getX() && pos.getY() < size.getY() && pos.getZ() < size.getZ();
    }

    /**
     * One sub-region's local positions, in world coordinates.
     *
     * <p>The layer numbers have to be the ones the render layers menu shows, which means world
     * coordinates, which means going through the placement's rotation and mirror. The formula is
     * transcribed from {@code SchematicPlacingUtils.placeBlocksWithinChunk}, which is the code
     * that decides {@code shouldPasteBlock} against this very same {@code LayerRange}:
     *
     * <pre>
     * regionWorld = getTransformedBlockPos(sub.getPos(), placement.getMirror(), placement.getRotation())
     *                   .offset(placement.getOrigin())
     * world       = getTransformedPlacementPosition(local, placement, sub).offset(regionWorld)
     * </pre>
     *
     * <p>{@code getTransformedPlacementPosition} composes the placement's and the sub-region's
     * own rotation and mirror itself, including the quirk where a sub-region mirror flips axis
     * under a 90 degree placement rotation - so none of that algebra is repeated here.
     *
     * <p>ponytail: that transform is a rotation and a mirror about the origin, so it is linear,
     * and the whole map is sampled once per region into three integers per axis instead of being
     * recomputed per block. {@link #linear} verifies the assumption on a fourth point and falls
     * back to calling Litematica per position if it ever fails to hold - cutting the wrong blocks
     * is not a failure worth being fast about.
     */
    static final class ToWorld {

        private final SchematicPlacement placement;
        private final SubRegionPlacement subRegion;
        private final BlockPos regionWorld;
        private final Vec3i localOffset;
        private final int[] base = new int[3];
        private final int[][] step = new int[3][3];      // step[worldAxis][localAxis]
        private final boolean linear;

        /**
         * The two null cases are <em>not</em> the same, and treating them as one was a silent
         * wrong-cut waiting to happen.
         *
         * <p>No placement at all means the schematic is nowhere in the world - a material list
         * opened straight from a loaded file - so its own coordinates are the only ones there
         * are, and the identity map is the right answer.
         *
         * <p>A placement that exists but has no sub-region under this name is a different thing
         * entirely: there is a rotation, a mirror and an origin that we have failed to find. The
         * old fallback quietly used raw container coordinates for that region, which on a rotated
         * or simply distant placement cuts a completely different part of the build than the one
         * the layer numbers name. That region is now refused rather than guessed at.
         */
        static ToWorld forRegion(LitematicaSchematic schematic, SchematicPlacement placement,
                                 String region) {
            if (placement == null) {
                return new ToWorld();
            }

            SubRegionPlacement subRegion = placement.getRelativeSubRegionPlacement(region);

            if (subRegion == null) {
                LetMeReplace.LOGGER.error(
                        "no sub-region placement for region '{}' of placement '{}' - skipping it,"
                                + " because without it the rotation and origin are unknown",
                        region, placement.getName());
                return null;
            }

            return new ToWorld(schematic, region, placement, subRegion);
        }

        private ToWorld() {
            this.placement = null;
            this.subRegion = null;
            this.regionWorld = BlockPos.ZERO;
            this.localOffset = Vec3i.ZERO;
            this.linear = true;

            for (int axis = 0; axis < 3; ++axis) {
                step[axis][axis] = 1;
            }
        }

        private ToWorld(LitematicaSchematic schematic, String region,
                        SchematicPlacement placement, SubRegionPlacement subRegion) {
            this.placement = placement;
            this.subRegion = subRegion;

            BlockPos regionPos = subRegion.getPos();

            this.regionWorld = PositionUtils
                    .getTransformedBlockPos(regionPos,
                            placement.getMirror(), placement.getRotation())
                    .offset(placement.getOrigin());

            // A sub-region's stored size can be negative, meaning it extends back from its own
            // position rather than forward from it. Litematica corrects for that by offsetting
            // every local position by (min corner - region position) before transforming it, and
            // leaving that out put the whole region on the wrong side of its origin: a 51-deep
            // region read as z 206..256 where it really occupies 156..206, so "the nearest layer"
            // picked the far face and the highlight landed on the opposite end of the build.
            // Zero for a positive-size region, so this costs nothing in the ordinary case.
            BlockPos corner = minCorner(regionPos, schematic.getAreaSizeAsVec3i(region));

            this.localOffset = corner.subtract(regionPos);

            BlockPos origin = transform(0, 0, 0);
            BlockPos alongX = transform(1, 0, 0);
            BlockPos alongY = transform(0, 1, 0);
            BlockPos alongZ = transform(0, 0, 1);

            base[0] = origin.getX();
            base[1] = origin.getY();
            base[2] = origin.getZ();

            fill(0, origin, alongX, alongY, alongZ, BlockPos::getX);
            fill(1, origin, alongX, alongY, alongZ, BlockPos::getY);
            fill(2, origin, alongX, alongY, alongZ, BlockPos::getZ);

            BlockPos diagonal = transform(1, 1, 1);
            this.linear = diagonal.getX() == world(0, 1, 1, 1)
                    && diagonal.getY() == world(1, 1, 1, 1)
                    && diagonal.getZ() == world(2, 1, 1, 1);
        }

        private void fill(int axis, BlockPos origin, BlockPos alongX, BlockPos alongY,
                          BlockPos alongZ, java.util.function.ToIntFunction<BlockPos> component) {
            int at = component.applyAsInt(origin);
            step[axis][0] = component.applyAsInt(alongX) - at;
            step[axis][1] = component.applyAsInt(alongY) - at;
            step[axis][2] = component.applyAsInt(alongZ) - at;
        }

        private BlockPos transform(int x, int y, int z) {
            BlockPos local = new BlockPos(x + localOffset.getX(),
                    y + localOffset.getY(),
                    z + localOffset.getZ());

            return PositionUtils
                    .getTransformedPlacementPosition(local, placement, subRegion)
                    .offset(regionWorld);
        }

        private int world(int axis, int x, int y, int z) {
            return base[axis] + step[axis][0] * x + step[axis][1] * y + step[axis][2] * z;
        }

        int coordinate(Direction.Axis axis, int x, int y, int z) {
            int index = axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2;

            return linear ? world(index, x, y, z) : component(index, transform(x, y, z));
        }

        private static int component(int index, BlockPos pos) {
            return index == 0 ? pos.getX() : index == 1 ? pos.getY() : pos.getZ();
        }

        /**
         * Where an entity of this region stands, in the world.
         *
         * <p>Entities do <strong>not</strong> go through the block path. Their stored position is
         * a {@link Vec3} relative to the region's own position - not to the min corner the blocks
         * are indexed from - and Litematica transforms it with two plain
         * {@code getTransformedPosition} calls, the placement's and then the sub-region's, rather
         * than the composed {@code getTransformedPlacementPosition} the blocks use. Transcribed
         * from {@code SchematicPlacingUtils.placeEntitiesToWorldWithinChunk}, which is the code
         * that decides {@code shouldPasteEntity} against this very same {@code LayerRange}, so
         * the entities a cut removes are exactly the ones the paste would have put in the layer.
         */
        Vec3 entityWorldPos(Vec3 local) {
            Vec3 pos = local;

            if (placement != null) {
                pos = PositionUtils.getTransformedPosition(pos,
                        placement.getMirror(), placement.getRotation());
                pos = PositionUtils.getTransformedPosition(pos,
                        subRegion.getMirror(), subRegion.getRotation());
            }

            return pos.add(regionWorld.getX(), regionWorld.getY(), regionWorld.getZ());
        }

        boolean isEntityWithin(LayerRange range, Vec3 local) {
            Vec3 world = entityWorldPos(local);

            // The layer an entity is in is the block it is standing in, floored - the same thing
            // Litematica asks when it decides whether to paste one.
            return range.isPositionWithinRange(
                    Mth.floor(world.x), Mth.floor(world.y), Mth.floor(world.z));
        }

        boolean isLinear() {
            return linear;
        }

        /** A local position in the world, through the sampled map. Linear regions only. */
        int[] toWorld(int x, int y, int z) {
            return new int[] {world(0, x, y, z), world(1, x, y, z), world(2, x, y, z)};
        }

        /** A world position in this region's container coordinates. Linear regions only. */
        int[] toLocal(int x, int y, int z) {
            return vectorToLocal(x - base[0], y - base[1], z - base[2]);
        }

        /**
         * A world-space offset in this region's local axes, which is how {@link BoxEdit} turns
         * "three blocks east" into a step through a rotated container.
         *
         * <p>The step matrix is a rotation and a mirror, so it is a signed permutation and its
         * inverse is its transpose - no division, no rounding.
         */
        int[] vectorToLocal(int dx, int dy, int dz) {
            int[] world = {dx, dy, dz};
            int[] local = new int[3];

            for (int l = 0; l < 3; ++l) {
                for (int w = 0; w < 3; ++w) {
                    local[l] += step[w][l] * world[w];
                }
            }

            return local;
        }

        boolean isWithin(LayerRange range, int x, int y, int z) {
            if (!linear) {
                return range.isPositionWithinRange(transform(x, y, z));
            }

            return range.isPositionWithinRange(
                    world(0, x, y, z), world(1, x, y, z), world(2, x, y, z));
        }
    }
}
