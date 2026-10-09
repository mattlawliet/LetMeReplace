package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.LitematicaSchematicRegionsAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shrinking a schematic to what is actually left in it.
 *
 * <p>Cutting layers off an edge empties the blocks but not the box: the region keeps the size it
 * was saved with, so the schematic still claims - and still renders, and still asks you to line
 * up - a footprint made mostly of air. This walks each sub-region, finds the smallest box that
 * still holds every block and every entity, and moves the region onto it.
 *
 * <p>Off by default and asked for by name in the save dialog. It is not an edit anybody asked for
 * when they clicked Save, and it is not free: it rewrites every container, and it invalidates
 * undo, because the positions a snapshot holds are indices into containers that no longer exist.
 *
 * <p>Nothing moves in the world. A region's position shifts by exactly the amount its contents
 * shift inside it, so every surviving block keeps the world coordinates it had - which is what
 * makes this safe to do to a schematic that is placed and being looked at.
 */
public final class Bounds {

    private Bounds() {
    }

    /** What a trim did. {@code before} and {@code after} are the schematic's enclosing size. */
    public record Trim(Vec3i before, Vec3i after, int regionsDropped, int orphansDropped) {

        public boolean shrank() {
            return !before.equals(after) || regionsDropped > 0;
        }

        public String describe() {
            return before.getX() + "x" + before.getY() + "x" + before.getZ()
                    + " to " + after.getX() + "x" + after.getY() + "x" + after.getZ();
        }
    }

    /**
     * Trims every sub-region to its contents. Returns what changed, or {@code null} when there
     * was nothing to trim - including the case of a schematic with nothing left in it at all,
     * which is deliberately left alone rather than reduced to no regions.
     */
    public static Trim tighten(LitematicaSchematic schematic) {
        Vec3i before = schematic.getMetadata().getEnclosingSize();
        int blocksBefore = countBlocks(schematic);

        if (isEmpty(schematic)) {
            LetMeReplace.LOGGER.info("trim: nothing left in the schematic, leaving it as it is");
            return null;
        }

        int dropped = 0;
        int orphans = 0;
        boolean changed = false;

        // A copy of the names: dropping an empty region removes it from the very maps
        // getAreas() is built from.
        for (String region : new ArrayList<>(schematic.getAreas().keySet())) {
            int result = tightenRegion(schematic, region);

            if (result == DROPPED) {
                ++dropped;
                changed = true;
            } else if (result >= 0) {
                orphans += result;
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }

        refreshMetadata(schematic);
        Vec3i after = schematic.getMetadata().getEnclosingSize();

        // The check this whole file needs: the blocks are supposed to be exactly the same set,
        // in a smaller box. A different count means the copy dropped something, and a schematic
        // that quietly loses blocks on save is worse than one with a loose footprint.
        int blocksAfter = countBlocks(schematic);

        if (blocksAfter != blocksBefore) {
            LetMeReplace.LOGGER.error(
                    "trim changed the block count from {} to {} - blocks were lost, this is a bug",
                    blocksBefore, blocksAfter);
        }

        // Undo indices are positions inside containers that no longer exist, so an undo across a
        // trim would write blocks at nonsense coordinates. The save that asked for this forgets
        // the history anyway; doing it here means a failed write cannot leave a live stale stack.
        UndoLog.forget(schematic);

        schematic.getMetadata().setModifiedSinceSaved();
        schematic.getMetadata().setTimeModifiedToNow();
        resetPlacements(schematic);
        Schematics.refreshRender(schematic);

        Trim trim = new Trim(before, after, dropped, orphans);
        LetMeReplace.LOGGER.info("trim: {}, {} regions dropped, {} orphans dropped",
                trim.describe(), dropped, orphans);

        return trim;
    }

    private static final int DROPPED = -2;
    private static final int UNCHANGED = -1;

    /**
     * One region. Returns {@link #DROPPED}, {@link #UNCHANGED}, or how many orphaned block
     * entities and ticks - NBT at a position that holds no block - were dropped with the trim.
     */
    private static int tightenRegion(LitematicaSchematic schematic, String region) {
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

        if (container == null) {
            return UNCHANGED;
        }

        BlockPos regionPos = schematic.getSubRegionPosition(region);
        BlockPos corner = Layers.minCorner(regionPos, schematic.getAreaSizeAsVec3i(region));
        // Blocks are indexed from the min corner, entities from the region's own position. Zero
        // apart for a positive-size region, and the whole reason to be careful for the rest.
        Vec3i entityOffset = corner.subtract(regionPos);

        Vec3i size = container.getSize();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        for (int y = 0; y < size.getY(); ++y) {
            for (int z = 0; z < size.getZ(); ++z) {
                for (int x = 0; x < size.getX(); ++x) {
                    if (!container.get(x, y, z).isAir()) {
                        expand(min, max, x, y, z);
                    }
                }
            }
        }

        List<EntityInfo> entities = schematic.getEntityListForRegion(region);

        if (entities != null) {
            for (EntityInfo entity : entities) {
                Vec3 local = entity.posVec().subtract(
                        entityOffset.getX(), entityOffset.getY(), entityOffset.getZ());

                // Clamped into the region it is already declared to be in. An entity standing a
                // hair outside its own box - an item frame on the far face, a minecart at the
                // edge - must not make the box bigger: this only ever shrinks.
                expand(min, max,
                        clamp(Mth.floor(local.x), size.getX()),
                        clamp(Mth.floor(local.y), size.getY()),
                        clamp(Mth.floor(local.z), size.getZ()));
            }
        }

        if (min[0] == Integer.MAX_VALUE) {
            drop(schematic, region);
            return DROPPED;
        }

        Vec3i trimmedSize = new Vec3i(max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1);

        if (min[0] == 0 && min[1] == 0 && min[2] == 0
                && trimmedSize.equals(size) && corner.equals(regionPos)) {
            return UNCHANGED;
        }

        LitematicaBlockStateContainer trimmed = new LitematicaBlockStateContainer(
                trimmedSize.getX(), trimmedSize.getY(), trimmedSize.getZ());

        for (int y = 0; y < trimmedSize.getY(); ++y) {
            for (int z = 0; z < trimmedSize.getZ(); ++z) {
                for (int x = 0; x < trimmedSize.getX(); ++x) {
                    BlockState state = container.get(x + min[0], y + min[1], z + min[2]);

                    // A fresh container is already all air, and writing air into it would put
                    // air in the palette for nothing.
                    if (!state.isAir()) {
                        trimmed.set(x, y, z, state);
                    }
                }
            }
        }

        int orphans = 0;
        orphans += remapBlockEntities(schematic.getBlockEntityMapForRegion(region), min, trimmedSize);
        orphans += remapTicks(schematic.getScheduledBlockTicksForRegion(region), min, trimmedSize);
        orphans += remapTicks(schematic.getScheduledFluidTicksForRegion(region), min, trimmedSize);

        if (entities != null) {
            for (int i = 0; i < entities.size(); ++i) {
                EntityInfo entity = entities.get(i);
                // The region's position moves to the new corner, so every entity position moves
                // by the same amount the other way and the entity stays where it was.
                entities.set(i, Schematics.entityAt(entity, entity.posVec().subtract(
                        entityOffset.getX() + min[0],
                        entityOffset.getY() + min[1],
                        entityOffset.getZ() + min[2])));
            }
        }

        LitematicaSchematicRegionsAccessor maps = (LitematicaSchematicRegionsAccessor) schematic;
        maps.letmereplace$containers().put(region, trimmed);
        maps.letmereplace$positions().put(region, corner.offset(min[0], min[1], min[2]));
        maps.letmereplace$sizes().put(region,
                new BlockPos(trimmedSize.getX(), trimmedSize.getY(), trimmedSize.getZ()));

        return orphans;
    }

    private static void expand(int[] min, int[] max, int x, int y, int z) {
        min[0] = Math.min(min[0], x);
        min[1] = Math.min(min[1], y);
        min[2] = Math.min(min[2], z);
        max[0] = Math.max(max[0], x);
        max[1] = Math.max(max[1], y);
        max[2] = Math.max(max[2], z);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(value, size - 1));
    }

    /** Moves every block entity onto the trimmed coordinates, dropping any left outside. */
    private static int remapBlockEntities(Map<BlockPos, CompoundData> live, int[] min, Vec3i size) {
        if (live == null || live.isEmpty()) {
            return 0;
        }

        Map<BlockPos, CompoundData> moved = new LinkedHashMap<>();
        int dropped = 0;

        for (Map.Entry<BlockPos, CompoundData> entry : live.entrySet()) {
            BlockPos pos = entry.getKey().offset(-min[0], -min[1], -min[2]);

            if (within(pos, size)) {
                moved.put(pos, entry.getValue());
            } else {
                ++dropped;
            }
        }

        live.clear();
        live.putAll(moved);

        return dropped;
    }

    /**
     * The same for scheduled ticks, except that a tick carries its own position as well as being
     * keyed by one - and it is the carried one that gets written to the file, so moving the key
     * alone would save the tick back at its old coordinates.
     */
    private static <T> int remapTicks(Map<BlockPos, ScheduledTick<T>> live, int[] min, Vec3i size) {
        if (live == null || live.isEmpty()) {
            return 0;
        }

        Map<BlockPos, ScheduledTick<T>> moved = new LinkedHashMap<>();
        int dropped = 0;

        for (Map.Entry<BlockPos, ScheduledTick<T>> entry : live.entrySet()) {
            BlockPos pos = entry.getKey().offset(-min[0], -min[1], -min[2]);

            if (within(pos, size)) {
                ScheduledTick<T> tick = entry.getValue();
                moved.put(pos, new ScheduledTick<>(tick.type(), pos, tick.triggerTick(),
                        tick.priority(), tick.subTickOrder()));
            } else {
                ++dropped;
            }
        }

        live.clear();
        live.putAll(moved);

        return dropped;
    }

    private static boolean within(BlockPos pos, Vec3i size) {
        return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0
                && pos.getX() < size.getX() && pos.getY() < size.getY() && pos.getZ() < size.getZ();
    }

    /** Takes an emptied region out of all seven maps it lives in. */
    private static void drop(LitematicaSchematic schematic, String region) {
        LitematicaSchematicRegionsAccessor maps = (LitematicaSchematicRegionsAccessor) schematic;

        maps.letmereplace$containers().remove(region);
        maps.letmereplace$blockEntities().remove(region);
        maps.letmereplace$blockTicks().remove(region);
        maps.letmereplace$fluidTicks().remove(region);
        maps.letmereplace$entities().remove(region);
        maps.letmereplace$positions().remove(region);
        maps.letmereplace$sizes().remove(region);

        LetMeReplace.LOGGER.info("trim: region '{}' holds nothing, dropped", region);
    }

    /** Nothing but air and no entities, anywhere - the one case a trim refuses. */
    private static boolean isEmpty(LitematicaSchematic schematic) {
        for (String region : schematic.getAreas().keySet()) {
            List<EntityInfo> entities = schematic.getEntityListForRegion(region);

            if (entities != null && !entities.isEmpty()) {
                return false;
            }

            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            Vec3i size = container.getSize();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        if (!container.get(x, y, z).isAir()) {
                            return false;
                        }
                    }
                }
            }
        }

        return true;
    }

    private static int countBlocks(LitematicaSchematic schematic) {
        int blocks = 0;

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container == null) {
                continue;
            }

            Vec3i size = container.getSize();

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        if (!container.get(x, y, z).isAir()) {
                            ++blocks;
                        }
                    }
                }
            }
        }

        return blocks;
    }

    /**
     * The numbers the schematic advertises about itself, recomputed. The enclosing size is the
     * point of the whole exercise - it is what the placement lines up and what the browser shows
     * - and the rest would otherwise describe the schematic as it was before the trim.
     */
    static void refreshMetadata(LitematicaSchematic schematic) {
        SchematicMetadata metadata = schematic.getMetadata();
        Collection<Box> areas = schematic.getAreas().values();

        metadata.setEnclosingSize(PositionUtils.getEnclosingAreaSize(areas));
        metadata.setRegionCount(areas.size());

        int volume = 0;

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);

            if (container != null) {
                Vec3i size = container.getSize();
                volume += size.getX() * size.getY() * size.getZ();
            }
        }

        metadata.setTotalVolume(volume);
        metadata.setTotalBlocks(countBlocks(schematic));
        Schematics.refreshCounts(schematic);
    }

    /**
     * Every placement of this schematic re-reads where its sub-regions are.
     *
     * <p>A placement holds its own copy of each region's position, taken from the schematic when
     * it was created, so after the regions move the placement would keep drawing them at the old
     * offsets - the build visibly tearing apart. This is the only API Litematica offers for it,
     * and it also resets any per-sub-region rotation or mirror the user had set, which is the
     * documented cost of trimming a placed schematic.
     */
    static void resetPlacements(LitematicaSchematic schematic) {
        // Before the reset, while the placements still describe the file - see FileGeometry.
        FileGeometry.remember(schematic);

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager()
                .getAllPlacementsOfSchematic(schematic)) {
            placement.resetAllSubRegionsToSchematicValues(message -> { });
        }
    }
}
