package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.LitematicaSchematicRegionsAccessor;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Stacking and moving a box of the loaded schematic inside itself - ticket 007.
 *
 * <p>The box is in world coordinates, the way Litematica's area selection and the layer numbers
 * are. Each sub-region it touches is mapped through the placement with the cut's own
 * {@link Layers.ToWorld}, so a rotated or mirrored placement moves the way it looks, and the
 * offsets are turned into that region's local axes the same way.
 *
 * <p>Everything in the box travels: block states, block entity NBT, block and fluid ticks, and
 * the entities standing in it. Whatever is in the way is overwritten and counted. Air in the box
 * either overwrites too or is left out, the player's choice. A copy that lands past the region's
 * edge grows the region by exactly as much as it needs, and growing on the negative side moves
 * the region's position the other way - {@link Bounds} run backwards - so nothing already there
 * changes its world coordinates.
 *
 * <p>No region is ever edited in place. Each one touched is rebuilt into a fresh container and
 * fresh maps and swapped in, so undo puts the old references back - see {@link UndoLog.Region}.
 * That is also what makes {@link #plan} honest: it is the same build, not swapped in.
 */
public final class BoxEdit {

    private BoxEdit() {
    }

    /**
     * What a stack or move does. {@code blocks} and {@code entities} are what is in the box;
     * {@code overwrites} is what was in the way and is lost, blocks and entities together.
     * {@code sizeAfter} is the schematic's world-space size afterwards, on world axes.
     */
    public record Plan(int blocks, int entities, int overwrites, Vec3i sizeBefore, Vec3i sizeAfter,
                       int seamsFixed, List<Doomed> wontSurvive) {

        public boolean doesNothing() {
            return blocks == 0 && entities == 0;
        }

        public boolean grows() {
            return !sizeBefore.equals(sizeAfter);
        }
    }

    /** A block the seam check says would not survive, in world coordinates. */
    public record Doomed(BlockPos pos, BlockState state) {
    }

    /** {@code count} copies, {@code step} blocks apart, going out from the box toward {@code toward}. */
    public static List<Vec3i> stackOffsets(Direction toward, int step, int count) {
        List<Vec3i> offsets = new ArrayList<>();

        for (int k = 1; k <= count; ++k) {
            offsets.add(new Vec3i(toward.getStepX() * step * k, toward.getStepY() * step * k,
                    toward.getStepZ() * step * k));
        }

        return offsets;
    }

    /** The box's length along {@code direction}'s axis, which is the default step. */
    public static int length(BlockPos corner1, BlockPos corner2, Direction direction) {
        return Math.abs(corner1.get(direction.getAxis()) - corner2.get(direction.getAxis())) + 1;
    }

    public static Plan plan(LitematicaSchematic schematic, SchematicPlacement placement,
                            BlockPos corner1, BlockPos corner2, List<Vec3i> offsets,
                            boolean move, boolean airReplaces) {
        return run(schematic, placement, corner1, corner2, offsets, move, airReplaces, false, null,
                false).plan();
    }

    /**
     * Does it, as one undo entry under {@code label}. With {@code dropDoomed}, the blocks the seam
     * check says would not survive are cleared to air instead of kept - asked for at the keyboard
     * 2026-10-01 as the other answer to the review.
     */
    public static Plan apply(LitematicaSchematic schematic, SchematicPlacement placement,
                             BlockPos corner1, BlockPos corner2, List<Vec3i> offsets,
                             boolean move, boolean airReplaces, boolean dropDoomed, String label) {
        return run(schematic, placement, corner1, corner2, offsets, move, airReplaces, dropDoomed,
                label, true).plan();
    }

    /**
     * A change shown live and taken off again. {@code revert} is {@code null} when nothing was
     * applied. The dirty flag is the caller's to put back, as with the layers preview.
     */
    public record Preview(Plan plan, Runnable revert) {
    }

    /**
     * Does it for real but <strong>off</strong> the undo stack. A preview is re-applied on every
     * notch and key press, and pushing each one would push real history off the bounded stack -
     * the oldest change silently becoming permanent. The swapped-out regions are held here
     * instead, and put back the same way undo would.
     */
    public static Preview preview(LitematicaSchematic schematic, SchematicPlacement placement,
                                  BlockPos corner1, BlockPos corner2, List<Vec3i> offsets,
                                  boolean move, boolean airReplaces) {
        Result result = run(schematic, placement, corner1, corner2, offsets, move, airReplaces,
                false, "Preview", false);
        Map<String, UndoLog.Region> previous = result.previous();

        return new Preview(result.plan(), previous == null ? null : () -> {
            UndoLog.restoreRegions(schematic, previous);
            Schematics.refreshRender(schematic);
        });
    }

    private record Result(Plan plan, Map<String, UndoLog.Region> previous) {
    }

    /** One region, rebuilt. {@code shift} is how far its old contents moved inside the container. */
    private record Built(LitematicaBlockStateContainer container,
                         Map<BlockPos, CompoundData> blockEntities,
                         Map<BlockPos, ScheduledTick<Block>> blockTicks,
                         Map<BlockPos, ScheduledTick<Fluid>> fluidTicks,
                         List<EntityInfo> entities,
                         BlockPos position,
                         Vec3i size,
                         boolean grew,
                         int blocks, int movedEntities, int overwrites,
                         int seamsFixed, List<Doomed> wontSurvive) {
    }

    private static Result run(LitematicaSchematic schematic, SchematicPlacement placement,
                              BlockPos corner1, BlockPos corner2, List<Vec3i> offsets,
                              boolean move, boolean airReplaces, boolean dropDoomed,
                              String label, boolean record) {
        BlockPos lo = PositionUtils.getMinCorner(corner1, corner2);
        BlockPos hi = PositionUtils.getMaxCorner(corner1, corner2);

        Map<String, Built> built = new LinkedHashMap<>();
        Span before = new Span();
        Span after = new Span();
        int blocks = 0;
        int entities = 0;
        int overwrites = 0;
        int seamsFixed = 0;
        List<Doomed> wontSurvive = new ArrayList<>();

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            Layers.ToWorld toWorld = Layers.ToWorld.forRegion(schematic, placement, region);

            if (container == null || toWorld == null) {
                continue;
            }

            Vec3i size = container.getSize();
            int[] last = {size.getX() - 1, size.getY() - 1, size.getZ() - 1};

            before.add(toWorld, new int[3], last);

            if (!toWorld.isLinear()) {
                // LayerCheck says this cannot happen. If it ever does, the inverse map is wrong
                // and a copy would land somewhere other than where the box points - refuse.
                LetMeReplace.LOGGER.error("box: region '{}' maps non-linearly, left alone", region);
                after.add(toWorld, new int[3], last);
                continue;
            }

            Built result = build(schematic, region, container, toWorld, lo, hi, offsets, move,
                    airReplaces, dropDoomed, after);

            if (result == null) {
                after.add(toWorld, new int[3], last);
                continue;
            }

            built.put(region, result);
            blocks += result.blocks();
            entities += result.movedEntities();
            overwrites += result.overwrites();
            seamsFixed += result.seamsFixed();
            wontSurvive.addAll(result.wontSurvive());
        }

        Plan plan = new Plan(blocks, entities, overwrites, before.size(), after.size(),
                seamsFixed, wontSurvive);

        if (label == null || built.isEmpty() || plan.doesNothing()) {
            return new Result(plan, null);
        }

        Map<String, UndoLog.Region> previous = commit(schematic, built, label, record);

        if (record) {
            LetMeReplace.LOGGER.info("box '{}': {} blocks, {} entities, {} overwritten, {} -> {}",
                    label, blocks, entities, overwrites, plan.sizeBefore(), plan.sizeAfter());
        }

        return new Result(plan, previous);
    }

    private static Built build(LitematicaSchematic schematic, String region,
                               LitematicaBlockStateContainer container, Layers.ToWorld toWorld,
                               BlockPos lo, BlockPos hi, List<Vec3i> offsets,
                               boolean move, boolean airReplaces, boolean dropDoomed,
                               Span after) {
        Vec3i size = container.getSize();
        int[] dims = {size.getX(), size.getY(), size.getZ()};
        int[][] clipped = clip(toWorld, lo, hi, dims);

        if (clipped == null) {
            return null;
        }

        int[] srcLo = clipped[0];
        int[] srcHi = clipped[1];

        int[][] step = new int[offsets.size()][];
        int[] newLo = {0, 0, 0};
        int[] newHi = {dims[0] - 1, dims[1] - 1, dims[2] - 1};

        for (int k = 0; k < offsets.size(); ++k) {
            Vec3i d = offsets.get(k);
            step[k] = toWorld.vectorToLocal(d.getX(), d.getY(), d.getZ());

            for (int i = 0; i < 3; ++i) {
                newLo[i] = Math.min(newLo[i], srcLo[i] + step[k][i]);
                newHi[i] = Math.max(newHi[i], srcHi[i] + step[k][i]);
            }
        }

        after.add(toWorld, newLo, newHi);

        int[] shift = {-newLo[0], -newLo[1], -newLo[2]};
        Vec3i newSize = new Vec3i(newHi[0] - newLo[0] + 1, newHi[1] - newLo[1] + 1,
                newHi[2] - newLo[2] + 1);
        LitematicaBlockStateContainer out =
                new LitematicaBlockStateContainer(newSize.getX(), newSize.getY(), newSize.getZ());

        Predicate<BlockPos> inSource = p -> p.getX() >= srcLo[0] && p.getX() <= srcHi[0]
                && p.getY() >= srcLo[1] && p.getY() <= srcHi[1]
                && p.getZ() >= srcLo[2] && p.getZ() <= srcHi[2];
        Predicate<BlockPos> leaves = p -> move && inSource.test(p);

        // Everything that stays where it is, at its new index. A move leaves its source behind
        // as air, which a fresh container already is.
        for (int y = 0; y < dims[1]; ++y) {
            for (int z = 0; z < dims[2]; ++z) {
                for (int x = 0; x < dims[0]; ++x) {
                    BlockState state = container.get(x, y, z);

                    if (!state.isAir() && !(move && inSource.test(new BlockPos(x, y, z)))) {
                        out.set(x + shift[0], y + shift[1], z + shift[2], state);
                    }
                }
            }
        }

        Carried<CompoundData> blockEntities = new Carried<>(
                schematic.getBlockEntityMapForRegion(region), (nbt, at) -> nbt.copy(), shift, leaves);
        Carried<ScheduledTick<Block>> blockTicks = new Carried<>(
                schematic.getScheduledBlockTicksForRegion(region), BoxEdit::retick, shift, leaves);
        Carried<ScheduledTick<Fluid>> fluidTicks = new Carried<>(
                schematic.getScheduledFluidTicksForRegion(region), BoxEdit::retick, shift, leaves);

        // The copies, read from the old container, so no copy ever reads another copy and a
        // stack whose step is shorter than the box still repeats the original.
        int blocks = 0;
        int overwrites = 0;

        for (int k = 0; k < step.length; ++k) {
            for (int y = srcLo[1]; y <= srcHi[1]; ++y) {
                for (int z = srcLo[2]; z <= srcHi[2]; ++z) {
                    for (int x = srcLo[0]; x <= srcHi[0]; ++x) {
                        BlockState state = container.get(x, y, z);

                        if (k == 0 && !state.isAir()) {
                            ++blocks;
                        }

                        if (state.isAir() && !airReplaces) {
                            continue;
                        }

                        int tx = x + step[k][0] + shift[0];
                        int ty = y + step[k][1] + shift[1];
                        int tz = z + step[k][2] + shift[2];
                        BlockState there = out.get(tx, ty, tz);

                        if (!there.isAir() && there != state) {
                            ++overwrites;
                        }

                        out.set(tx, ty, tz, state);

                        BlockPos from = new BlockPos(x, y, z);
                        BlockPos to = new BlockPos(tx, ty, tz);
                        blockEntities.land(from, to);
                        blockTicks.land(from, to);
                        fluidTicks.land(from, to);
                    }
                }
            }
        }

        // Entities: their own list, positions relative to the region's position rather than its
        // min corner, and tested in the world through the entity path - see Layers.ToWorld.
        BlockPos regionPos = schematic.getSubRegionPosition(region);
        BlockPos corner = Layers.minCorner(regionPos, schematic.getAreaSizeAsVec3i(region));
        Vec3i entityOffset = corner.subtract(regionPos);
        Vec3 toNew = new Vec3(shift[0] - entityOffset.getX(), shift[1] - entityOffset.getY(),
                shift[2] - entityOffset.getZ());

        List<EntityInfo> oldEntities = schematic.getEntityListForRegion(region);
        List<EntityInfo> newEntities = new ArrayList<>();
        List<EntityInfo> travelling = new ArrayList<>();

        if (oldEntities != null) {
            for (EntityInfo entity : oldEntities) {
                Vec3 world = toWorld.entityWorldPos(entity.posVec());
                BlockPos standing = BlockPos.containing(world);
                boolean inBox = within(standing, lo, hi);

                if (inBox) {
                    travelling.add(entity);
                }

                if (move && inBox) {
                    continue;
                }

                if (airReplaces && inAnyCopy(standing, lo, hi, offsets)) {
                    ++overwrites;                         // in the way, and the copy replaces it
                    continue;
                }

                newEntities.add(Schematics.entityAt(entity, entity.posVec().add(toNew)));
            }
        }

        for (int k = 0; k < step.length; ++k) {
            Vec3 by = toNew.add(step[k][0], step[k][1], step[k][2]);

            for (EntityInfo entity : travelling) {
                newEntities.add(Schematics.entityAt(entity, entity.posVec().add(by)));
            }
        }

        // Against the stored position and signed size, not the container: a region saved with a
        // negative size is rewritten positive from its min corner even when nothing grows, and
        // that moves its position just as much as growing does. Missing it left the placement on
        // the old position - a 5-cube saved at (4,4,4) size -5 drew four blocks off.
        BlockPos newPos = corner.offset(-shift[0], -shift[1], -shift[2]);
        boolean grew = !newPos.equals(regionPos)
                || !new BlockPos(newSize).equals(schematic.getAreaSize(region));

        Seams.Result seams = Seams.settle(container, out,
                seamShell(srcLo, srcHi, step, shift, move, newSize),
                pos -> origin(pos, container, srcLo, srcHi, step, shift, move, airReplaces));
        List<Doomed> doomed = new ArrayList<>();

        for (Seams.Flag flag : seams.flagged()) {
            if (dropDoomed) {
                // ponytail: one pass - a block that only stood on a removed one is not
                // re-checked. Run the stack again if that ever leaves a second wave.
                BlockPos at = flag.pos();
                out.set(at.getX(), at.getY(), at.getZ(), Blocks.AIR.defaultBlockState());
                blockEntities.out.remove(at);
                blockTicks.out.remove(at);
                fluidTicks.out.remove(at);
            }

            BlockPos local = flag.pos().offset(-shift[0], -shift[1], -shift[2]);
            int[] world = toWorld.toWorld(local.getX(), local.getY(), local.getZ());
            doomed.add(new Doomed(new BlockPos(world[0], world[1], world[2]), flag.state()));
        }

        return new Built(out, blockEntities.out, blockTicks.out, fluidTicks.out, newEntities,
                newPos, newSize, grew,
                blocks, travelling.size(), overwrites, seams.fixed(), doomed);
    }

    /**
     * Every position whose neighbour changed, in the new container: the outer layer of each copy
     * and the ring just outside it, and for a move the same around the hole it left. The inside
     * of a box is not here - nothing next to it changed.
     */
    private static List<BlockPos> seamShell(int[] srcLo, int[] srcHi, int[][] step, int[] shift,
                                            boolean move, Vec3i size) {
        java.util.Set<BlockPos> shell = new java.util.LinkedHashSet<>();

        for (int[] e : step) {
            addShell(shell, srcLo, srcHi, new int[] {e[0] + shift[0], e[1] + shift[1], e[2] + shift[2]},
                    size);
        }

        if (move) {
            addShell(shell, srcLo, srcHi, shift, size);
        }

        return new ArrayList<>(shell);
    }

    private static void addShell(java.util.Set<BlockPos> shell, int[] lo, int[] hi, int[] by,
                                 Vec3i size) {
        int[] dims = {size.getX(), size.getY(), size.getZ()};
        int[] from = new int[3];
        int[] to = new int[3];

        for (int i = 0; i < 3; ++i) {
            from[i] = Math.max(0, lo[i] + by[i] - 1);
            to[i] = Math.min(dims[i] - 1, hi[i] + by[i] + 1);
        }

        for (int y = from[1]; y <= to[1]; ++y) {
            for (int z = from[2]; z <= to[2]; ++z) {
                for (int x = from[0]; x <= to[0]; ++x) {
                    boolean inside = x > lo[0] + by[0] && x < hi[0] + by[0]
                            && y > lo[1] + by[1] && y < hi[1] + by[1]
                            && z > lo[2] + by[2] && z < hi[2] + by[2];

                    if (!inside) {
                        shell.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    /**
     * Where the block now at {@code pos} (new container) came from in the old one: the source
     * cell of the last copy that wrote it, or the same place if nothing did. {@code null} for a
     * cell a move emptied or one that did not exist before.
     */
    private static BlockPos origin(BlockPos pos, LitematicaBlockStateContainer old, int[] srcLo,
                                   int[] srcHi, int[][] step, int[] shift, boolean move,
                                   boolean airReplaces) {
        int[] p = {pos.getX() - shift[0], pos.getY() - shift[1], pos.getZ() - shift[2]};

        for (int k = step.length - 1; k >= 0; --k) {
            int[] src = {p[0] - step[k][0], p[1] - step[k][1], p[2] - step[k][2]};

            if (within(src, srcLo, srcHi)) {
                BlockPos from = new BlockPos(src[0], src[1], src[2]);

                if (airReplaces || !old.get(src[0], src[1], src[2]).isAir()) {
                    return from;
                }
            }
        }

        if (move && within(p, srcLo, srcHi)) {
            return null;
        }

        Vec3i size = old.getSize();
        return within(p, new int[3], new int[] {size.getX() - 1, size.getY() - 1, size.getZ() - 1})
                ? new BlockPos(p[0], p[1], p[2]) : null;
    }

    private static boolean within(int[] p, int[] lo, int[] hi) {
        return p[0] >= lo[0] && p[0] <= hi[0] && p[1] >= lo[1] && p[1] <= hi[1]
                && p[2] >= lo[2] && p[2] <= hi[2];
    }

    /**
     * The box in a region's container coordinates, clipped to the container, as
     * {@code {lo, hi}} inclusive - or {@code null} when the region holds none of it. A region
     * only ever holds its own slice of the box.
     */
    private static int[][] clip(Layers.ToWorld toWorld, BlockPos lo, BlockPos hi, int[] dims) {
        int[] a = toWorld.toLocal(lo.getX(), lo.getY(), lo.getZ());
        int[] b = toWorld.toLocal(hi.getX(), hi.getY(), hi.getZ());
        int[] localLo = new int[3];
        int[] localHi = new int[3];

        for (int i = 0; i < 3; ++i) {
            localLo[i] = Math.max(0, Math.min(a[i], b[i]));
            localHi[i] = Math.min(dims[i] - 1, Math.max(a[i], b[i]));

            if (localLo[i] > localHi[i]) {
                return null;
            }
        }

        return new int[][] {localLo, localHi};
    }

    /**
     * The smallest box inside the given one that still holds every block and every entity in it,
     * as {@code {min, max}} world corners, or {@code null} when it holds nothing. The same walk as
     * {@code Bounds.tightenRegion}, applied to a box instead of a region.
     */
    public static BlockPos[] fit(LitematicaSchematic schematic, SchematicPlacement placement,
                                 BlockPos corner1, BlockPos corner2) {
        BlockPos lo = PositionUtils.getMinCorner(corner1, corner2);
        BlockPos hi = PositionUtils.getMaxCorner(corner1, corner2);
        Span span = new Span();

        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            Layers.ToWorld toWorld = Layers.ToWorld.forRegion(schematic, placement, region);

            if (container == null || toWorld == null || !toWorld.isLinear()) {
                continue;
            }

            Vec3i size = container.getSize();
            int[][] clipped = clip(toWorld, lo, hi, new int[] {size.getX(), size.getY(), size.getZ()});

            if (clipped != null) {
                for (int y = clipped[0][1]; y <= clipped[1][1]; ++y) {
                    for (int z = clipped[0][2]; z <= clipped[1][2]; ++z) {
                        for (int x = clipped[0][0]; x <= clipped[1][0]; ++x) {
                            if (!container.get(x, y, z).isAir()) {
                                span.addPoint(toWorld.toWorld(x, y, z));
                            }
                        }
                    }
                }
            }

            List<EntityInfo> entities = schematic.getEntityListForRegion(region);

            if (entities != null) {
                for (EntityInfo entity : entities) {
                    BlockPos standing = BlockPos.containing(toWorld.entityWorldPos(entity.posVec()));

                    if (within(standing, lo, hi)) {
                        span.addPoint(new int[] {standing.getX(), standing.getY(), standing.getZ()});
                    }
                }
            }
        }

        return span.isEmpty() ? null : new BlockPos[] {
                new BlockPos(span.min[0], span.min[1], span.min[2]),
                new BlockPos(span.max[0], span.max[1], span.max[2])};
    }

    private static Map<String, UndoLog.Region> commit(LitematicaSchematic schematic,
                                                      Map<String, Built> built, String label,
                                                      boolean record) {
        LitematicaSchematicRegionsAccessor maps = (LitematicaSchematicRegionsAccessor) schematic;
        Map<String, UndoLog.Region> previous = new LinkedHashMap<>();
        boolean grew = false;

        for (Map.Entry<String, Built> entry : built.entrySet()) {
            String region = entry.getKey();
            Built b = entry.getValue();

            previous.put(region, UndoLog.Region.take(maps, region));
            new UndoLog.Region(b.container(), b.blockEntities(), b.blockTicks(), b.fluidTicks(),
                    b.entities(), b.position(),
                    new BlockPos(b.size().getX(), b.size().getY(), b.size().getZ()))
                    .put(maps, region);
            grew |= b.grew();
        }

        FileGeometry.rememberRegions(schematic, previous);

        if (record) {
            UndoLog.record(schematic, UndoLog.Snapshot.ofRegions(label, previous));
        }

        Bounds.refreshMetadata(schematic);

        if (grew) {
            // The placements hold their own copy of each region's position. Same call, same
            // documented cost as a trim: per-sub-region rotation and mirror are reset.
            Bounds.resetPlacements(schematic);
            // A layer scope memoises local coordinates that just moved.
            Scope.clear();
        }

        schematic.getMetadata().setModifiedSinceSaved();
        schematic.getMetadata().setTimeModifiedToNow();
        Schematics.refreshRender(schematic);

        return previous;
    }

    private static <T> ScheduledTick<T> retick(ScheduledTick<T> tick, BlockPos at) {
        // The tick carries its own position, and that is the one written to the file.
        return new ScheduledTick<>(tick.type(), at, tick.triggerTick(), tick.priority(),
                tick.subTickOrder());
    }

    private static boolean within(BlockPos pos, BlockPos lo, BlockPos hi) {
        return pos.getX() >= lo.getX() && pos.getX() <= hi.getX()
                && pos.getY() >= lo.getY() && pos.getY() <= hi.getY()
                && pos.getZ() >= lo.getZ() && pos.getZ() <= hi.getZ();
    }

    private static boolean inAnyCopy(BlockPos pos, BlockPos lo, BlockPos hi, List<Vec3i> offsets) {
        for (Vec3i d : offsets) {
            if (within(pos, lo.offset(d), hi.offset(d))) {
                return true;
            }
        }

        return false;
    }

    /**
     * One position-keyed map of a region, rebuilt alongside the container: what stays is carried
     * to its new index, and each copied position takes the source's entry or loses its own.
     */
    private static final class Carried<V> {

        private final Map<BlockPos, V> old;
        private final BiFunction<V, BlockPos, V> relocate;
        final Map<BlockPos, V> out = new LinkedHashMap<>();

        Carried(Map<BlockPos, V> old, BiFunction<V, BlockPos, V> relocate, int[] shift,
                Predicate<BlockPos> leaves) {
            this.old = old;
            this.relocate = relocate;

            if (old != null) {
                for (Map.Entry<BlockPos, V> entry : old.entrySet()) {
                    if (!leaves.test(entry.getKey())) {
                        BlockPos at = entry.getKey().offset(shift[0], shift[1], shift[2]);
                        out.put(at, relocate.apply(entry.getValue(), at));
                    }
                }
            }
        }

        void land(BlockPos from, BlockPos to) {
            out.remove(to);

            if (old != null && !old.isEmpty()) {
                V value = old.get(from);

                if (value != null) {
                    out.put(to, relocate.apply(value, to));
                }
            }
        }
    }

    /** The world-space box a set of regions covers, for the size line. */
    private static final class Span {

        private final int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        private final int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        /** A local box, through the map. Two corners are enough: the map is a signed permutation. */
        void add(Layers.ToWorld toWorld, int[] localLo, int[] localHi) {
            for (int[] world : new int[][] {
                    toWorld.toWorld(localLo[0], localLo[1], localLo[2]),
                    toWorld.toWorld(localHi[0], localHi[1], localHi[2])}) {
                addPoint(world);
            }
        }

        void addPoint(int[] world) {
            for (int i = 0; i < 3; ++i) {
                min[i] = Math.min(min[i], world[i]);
                max[i] = Math.max(max[i], world[i]);
            }
        }

        boolean isEmpty() {
            return min[0] > max[0];
        }

        Vec3i size() {
            return isEmpty() ? Vec3i.ZERO
                    : new Vec3i(max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1);
        }
    }
}
