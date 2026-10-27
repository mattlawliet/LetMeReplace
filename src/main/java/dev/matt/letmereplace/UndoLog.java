package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.LitematicaSchematicRegionsAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Undo history for each loaded schematic.
 *
 * <p>Ticket 004 D3 settled on <em>no</em> undo, on the grounds that not saving discards the
 * session and a per-swap stack would cost a container copy per level. Reversed at the keyboard
 * on 2026-09-10, then extended to a multi-level stack. The copy-per-level objection still does
 * not apply: a snapshot holds only the positions that actually changed.
 *
 * <p>ponytail: the stack is bounded at {@value #MAX_SNAPSHOTS} changes and
 * {@value #MAX_POSITIONS} stored positions, oldest dropped first. A water swap on the guardian
 * farm touches 54,674 positions, so an unbounded stack would be tens of megabytes of history for
 * a session nobody asked to keep. Raise the ceilings if undo starts running out too early.
 *
 * <p>The snapshot includes the <strong>block entity NBT that the swap removed</strong>. Restoring
 * states alone would put a chest back and leave it empty, which is precisely the silent loss the
 * NBT rule exists to prevent.
 *
 * <p>Weak keys: an unloaded schematic must not be held alive by its undo history.
 */
public final class UndoLog {

    private static final int MAX_SNAPSHOTS = 20;
    private static final int MAX_POSITIONS = 1_000_000;

    /**
     * Positions are stored as container indices, which is what the container itself uses, in
     * primitive-keyed maps - a boxed {@code Map<Integer, BlockState>} of a large swap is most of
     * the memory this class uses.
     */
    public record Snapshot(String label,
                           Map<String, Int2ObjectMap<BlockState>> states,
                           Map<String, Map<BlockPos, CompoundData>> blockEntities,
                           Map<String, Map<BlockPos, ScheduledTick<Block>>> blockTicks,
                           Map<String, Map<BlockPos, ScheduledTick<Fluid>>> fluidTicks,
                           Map<String, List<EntityInfo>> entities,
                           Map<String, Region> regions,
                           Map<String, List<EntityInfo>> entitiesRemove) {

        /** For changes that touch no scheduled ticks and no entities - every block swap. */
        public Snapshot(String label,
                        Map<String, Int2ObjectMap<BlockState>> states,
                        Map<String, Map<BlockPos, CompoundData>> blockEntities) {
            this(label, states, blockEntities, Map.of(), Map.of(), Map.of());
        }

        /** For changes made position by position - everything except {@link BoxEdit}. */
        public Snapshot(String label,
                        Map<String, Int2ObjectMap<BlockState>> states,
                        Map<String, Map<BlockPos, CompoundData>> blockEntities,
                        Map<String, Map<BlockPos, ScheduledTick<Block>>> blockTicks,
                        Map<String, Map<BlockPos, ScheduledTick<Fluid>>> fluidTicks,
                        Map<String, List<EntityInfo>> entities) {
            this(label, states, blockEntities, blockTicks, fluidTicks, entities, Map.of(), Map.of());
        }

        /** For a change that swapped whole regions out - see {@link Region}. */
        static Snapshot ofRegions(String label, Map<String, Region> regions) {
            return new Snapshot(label, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), regions,
                    Map.of());
        }

        /**
         * A swapped-out region counts its whole container: the snapshot holds very little, but
         * it keeps the old container alive, and that is the memory the budget exists to bound.
         */
        int positions() {
            int total = 0;

            for (Int2ObjectMap<BlockState> region : states.values()) {
                total += region.size();
            }

            for (Region region : regions.values()) {
                if (region.container() != null) {
                    Vec3i size = region.container().getSize();
                    total += size.getX() * size.getY() * size.getZ();
                }
            }

            return total;
        }
    }

    /**
     * Everything one sub-region is, by reference: the seven entries it has in the schematic's
     * seven maps.
     *
     * <p>{@link BoxEdit} never edits a region in place. It builds a new container and new maps
     * and puts them under the same name, so the old ones are left exactly as they were and undo
     * is putting them back - O(1), whatever the size, and correct even when the region grew,
     * because the old position and size come back with the old container. Snapshots older than
     * this one index the old container, which is restored before they are reached: the stack is
     * LIFO.
     */
    public record Region(LitematicaBlockStateContainer container,
                         Map<BlockPos, CompoundData> blockEntities,
                         Map<BlockPos, ScheduledTick<Block>> blockTicks,
                         Map<BlockPos, ScheduledTick<Fluid>> fluidTicks,
                         List<EntityInfo> entities,
                         BlockPos position,
                         BlockPos size) {

        static Region take(LitematicaSchematicRegionsAccessor maps, String name) {
            return new Region(maps.letmereplace$containers().get(name),
                    maps.letmereplace$blockEntities().get(name),
                    maps.letmereplace$blockTicks().get(name),
                    maps.letmereplace$fluidTicks().get(name),
                    maps.letmereplace$entities().get(name),
                    maps.letmereplace$positions().get(name),
                    maps.letmereplace$sizes().get(name));
        }

        void put(LitematicaSchematicRegionsAccessor maps, String name) {
            putOrRemove(maps.letmereplace$containers(), name, container);
            putOrRemove(maps.letmereplace$blockEntities(), name, blockEntities);
            putOrRemove(maps.letmereplace$blockTicks(), name, blockTicks);
            putOrRemove(maps.letmereplace$fluidTicks(), name, fluidTicks);
            putOrRemove(maps.letmereplace$entities(), name, entities);
            putOrRemove(maps.letmereplace$positions(), name, position);
            putOrRemove(maps.letmereplace$sizes(), name, size);
        }

        private static <V> void putOrRemove(Map<String, V> map, String name, V value) {
            if (value == null) {
                map.remove(name);
            } else {
                map.put(name, value);
            }
        }
    }

    private static final Map<LitematicaSchematic, Deque<Snapshot>> HISTORY = new WeakHashMap<>();

    /**
     * What undo took back, newest first, so it can be put back again. Each entry is the inverse
     * {@link #apply} handed back when the change was undone - the same kind of snapshot, so redo
     * is undo run on it. Cleared by any new change and by Save.
     */
    private static final Map<LitematicaSchematic, Deque<Snapshot>> REDO = new WeakHashMap<>();

    /** The schematic changed, undone or redone most recently - what the hotkeys act on. */
    private static java.lang.ref.WeakReference<LitematicaSchematic> last =
            new java.lang.ref.WeakReference<>(null);

    private static void touched(LitematicaSchematic schematic) {
        last = new java.lang.ref.WeakReference<>(schematic);
    }

    /** Makes this the schematic the hotkeys act on - a shared one an edit just arrived for. */
    static synchronized void markEdited(LitematicaSchematic schematic) {
        touched(schematic);
    }

    /** The schematic the undo and redo hotkeys act on, or {@code null}. */
    public static synchronized LitematicaSchematic lastEdited() {
        return last.get();
    }

    public static synchronized boolean canRedo(LitematicaSchematic schematic) {
        Deque<Snapshot> stack = REDO.get(schematic);
        return stack != null && !stack.isEmpty();
    }

    public static synchronized String redoLabel(LitematicaSchematic schematic) {
        Deque<Snapshot> stack = REDO.get(schematic);
        return stack == null || stack.isEmpty() ? null : stack.peek().label();
    }

    private UndoLog() {
    }

    static synchronized void record(LitematicaSchematic schematic, Snapshot snapshot) {
        // Entities as well as states: a layer can hold an armour stand and no blocks at all, and
        // dropping that snapshot would make the one thing the cut removed unrecoverable.
        if (snapshot.states().isEmpty() && snapshot.entities().isEmpty()
                && snapshot.regions().isEmpty() && snapshot.entitiesRemove().isEmpty()) {
            return;
        }

        Deque<Snapshot> stack = HISTORY.computeIfAbsent(schematic, k -> new ArrayDeque<>());
        stack.push(snapshot);
        trim(stack);
        // A new change ends the branch that could have been redone - unless it is the layers
        // screen's preview, which is taken straight back off again by takeBack.
        if (!PREVIEW.equals(snapshot.label())) {
            REDO.remove(schematic);
            touched(schematic);
            Share.changed(schematic, snapshot.label());
        }
    }

    /** Oldest first, by count and then by stored positions. */
    private static void trim(Deque<Snapshot> stack) {
        while (stack.size() > MAX_SNAPSHOTS) {
            stack.removeLast();
        }

        int total = 0;

        for (Snapshot snapshot : stack) {
            total += snapshot.positions();
        }

        while (total > MAX_POSITIONS && stack.size() > 1) {
            total -= stack.removeLast().positions();
        }
    }

    public static synchronized boolean has(LitematicaSchematic schematic) {
        Deque<Snapshot> stack = HISTORY.get(schematic);
        return stack != null && !stack.isEmpty();
    }

    /** How many changes can still be taken back. */
    public static synchronized int depth(LitematicaSchematic schematic) {
        Deque<Snapshot> stack = HISTORY.get(schematic);
        return stack == null ? 0 : stack.size();
    }

    public static synchronized String label(LitematicaSchematic schematic) {
        Deque<Snapshot> stack = HISTORY.get(schematic);
        return stack == null || stack.isEmpty() ? null : stack.peek().label();
    }

    public static synchronized void forget(LitematicaSchematic schematic) {
        HISTORY.remove(schematic);
        REDO.remove(schematic);
    }

    /** The label {@code Layers.apply} records a hidden preview under. */
    public static final String PREVIEW = "Preview";

    /**
     * Takes the most recent change back without offering it for redo - a preview, which was
     * never a change anybody made.
     */
    public static synchronized int takeBack(LitematicaSchematic schematic) {
        return step(schematic, HISTORY, null);
    }

    /** Puts the most recent change back. Returns how many positions were restored. */
    public static synchronized int undo(LitematicaSchematic schematic) {
        return step(schematic, HISTORY, REDO);
    }

    /** Puts back the most recent change undo took back. Returns how many positions changed. */
    public static synchronized int redo(LitematicaSchematic schematic) {
        return step(schematic, REDO, HISTORY);
    }

    /**
     * Pops from one stack, applies it, and pushes what it overwrote onto the other - or drops it,
     * when {@code to} is {@code null}.
     */
    private static int step(LitematicaSchematic schematic,
                            Map<LitematicaSchematic, Deque<Snapshot>> from,
                            Map<LitematicaSchematic, Deque<Snapshot>> to) {
        Deque<Snapshot> stack = from.get(schematic);

        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        int[] changed = {0};
        Snapshot inverse = apply(schematic, stack.pop(), changed);

        if (to != null) {
            Deque<Snapshot> other = to.computeIfAbsent(schematic, k -> new ArrayDeque<>());
            other.push(inverse);
            trim(other);
            touched(schematic);
        }

        if (changed[0] > 0) {
            Schematics.refreshCounts(schematic);
            schematic.getMetadata().setTimeModifiedToNow();
            // Same call every change ends with - without it the world keeps rendering the
            // version that was just replaced.
            Schematics.refreshRender(schematic);

            if (to != null) {
                Share.changed(schematic, (to == REDO ? "Undo " : "Redo ") + inverse.label());
            }
        }

        return changed[0];
    }

    /**
     * Writes a snapshot into the schematic and returns its inverse: everything it overwrote, in
     * the same shape, so applying the inverse takes it back again. That one property is what
     * makes redo the same code as undo.
     *
     * <p>A block entity or tick the snapshot holds as {@code null} means "none here": it is
     * removed, which is how the inverse of putting one back is told apart from leaving it.
     */
    private static Snapshot apply(LitematicaSchematic schematic, Snapshot snapshot, int[] changed) {
        Map<String, Region> regionsBefore = snapshot.regions().isEmpty()
                ? Map.of() : swapRegions(schematic, snapshot.regions());
        changed[0] += regionsBefore.size();

        Map<String, Int2ObjectMap<BlockState>> statesBefore = newStateMap();

        for (Map.Entry<String, Int2ObjectMap<BlockState>> region : snapshot.states().entrySet()) {
            LitematicaBlockStateContainer container =
                    schematic.getSubRegionContainer(region.getKey());

            if (container == null) {
                continue;
            }

            Vec3i size = container.getSize();
            int sizeX = size.getX();
            int layer = sizeX * size.getZ();
            Int2ObjectMap<BlockState> before = newRegionMap();

            for (Int2ObjectMap.Entry<BlockState> at : region.getValue().int2ObjectEntrySet()) {
                int index = at.getIntKey();
                int x = x(index, sizeX, layer);
                int y = y(index, layer);
                int z = z(index, sizeX, layer);

                before.put(index, container.get(x, y, z));
                container.set(x, y, z, at.getValue());
                ++changed[0];
            }

            statesBefore.put(region.getKey(), before);
        }

        Map<String, Map<BlockPos, CompoundData>> blockEntitiesBefore =
                swapEntries(snapshot.blockEntities(), schematic::getBlockEntityMapForRegion);
        // Scheduled ticks go with the blocks. A restored water source whose tick was left
        // behind is not the block that was removed.
        Map<String, Map<BlockPos, ScheduledTick<Block>>> blockTicksBefore =
                swapEntries(snapshot.blockTicks(), schematic::getScheduledBlockTicksForRegion);
        Map<String, Map<BlockPos, ScheduledTick<Fluid>>> fluidTicksBefore =
                swapEntries(snapshot.fluidTicks(), schematic::getScheduledFluidTicksForRegion);

        // Entities: the ones this snapshot adds go back into the list they came out of, and the
        // ones it removes come out by identity - redo removing exactly the objects undo put back.
        // Order within the list means nothing to Litematica.
        for (Map.Entry<String, List<EntityInfo>> region : snapshot.entitiesRemove().entrySet()) {
            List<EntityInfo> live = schematic.getEntityListForRegion(region.getKey());

            if (live != null) {
                for (EntityInfo gone : region.getValue()) {
                    live.removeIf(entity -> entity == gone);
                }

                changed[0] += region.getValue().size();
            }
        }

        for (Map.Entry<String, List<EntityInfo>> region : snapshot.entities().entrySet()) {
            List<EntityInfo> live = schematic.getEntityListForRegion(region.getKey());

            if (live != null) {
                live.addAll(region.getValue());
                changed[0] += region.getValue().size();
            }
        }

        return new Snapshot(snapshot.label(), statesBefore, blockEntitiesBefore, blockTicksBefore,
                fluidTicksBefore, snapshot.entitiesRemove(), regionsBefore, snapshot.entities());
    }

    /** Writes each entry, {@code null} meaning remove, and returns what each key held before. */
    private static <V> Map<String, Map<BlockPos, V>> swapEntries(
            Map<String, Map<BlockPos, V>> entries,
            java.util.function.Function<String, Map<BlockPos, V>> liveFor) {
        Map<String, Map<BlockPos, V>> before = new LinkedHashMap<>();

        for (Map.Entry<String, Map<BlockPos, V>> region : entries.entrySet()) {
            Map<BlockPos, V> live = liveFor.apply(region.getKey());

            if (live == null) {
                continue;
            }

            Map<BlockPos, V> was = new LinkedHashMap<>();

            for (Map.Entry<BlockPos, V> entry : region.getValue().entrySet()) {
                was.put(entry.getKey(), live.get(entry.getKey()));

                if (entry.getValue() == null) {
                    live.remove(entry.getKey());
                } else {
                    live.put(entry.getKey(), entry.getValue());
                }
            }

            before.put(region.getKey(), was);
        }

        return before;
    }

    /** Puts swapped-out regions back - undo's half of {@link Region}, shared with previews. */
    static int restoreRegions(LitematicaSchematic schematic, Map<String, Region> regions) {
        return swapRegions(schematic, regions).size();
    }

    /** Puts these regions in and returns the ones they replaced, by reference. */
    static Map<String, Region> swapRegions(LitematicaSchematic schematic,
                                           Map<String, Region> regions) {
        Map<String, Region> replaced = new LinkedHashMap<>();
        LitematicaSchematicRegionsAccessor maps = (LitematicaSchematicRegionsAccessor) schematic;
        boolean moved = false;

        // Taken before the regions shrink back. Litematica's own reset works out which chunks
        // the placement used to touch from the region sizes - which by then are already the
        // restored, smaller ones - so a chunk only the grown version reached is never rebuilt
        // and keeps drawing blocks that are gone. Reported 2026-10-01: an east stack's preview
        // left part of itself behind when the direction changed.
        java.util.Set<net.minecraft.world.level.ChunkPos> touched = new java.util.HashSet<>();

        for (var placement : DataManager.getSchematicPlacementManager()
                .getAllPlacementsOfSchematic(schematic)) {
            touched.addAll(placement.getTouchedChunks());
        }

        for (Map.Entry<String, Region> region : regions.entrySet()) {
            Region now = Region.take(maps, region.getKey());
            Region then = region.getValue();
            replaced.put(region.getKey(), now);

            moved |= !java.util.Objects.equals(now.position(), then.position())
                    || !java.util.Objects.equals(now.size(), then.size());
            then.put(maps, region.getKey());
        }

        Bounds.refreshMetadata(schematic);

        // Only when a region actually changed shape: the reset also wipes any per-sub-region
        // rotation the user set, which a same-size stack had no reason to touch.
        if (moved) {
            Bounds.resetPlacements(schematic);
            Scope.clear();
        }

        // Entities first. Litematica's rebuild of a column no placement touches any more unloads
        // its blocks and leaves its entities, so a stack preview that reached a neighbouring column
        // and was taken back left mobs hanging in the sky there (screenshot 2026-10-07 04:18).
        // Columns still in use get theirs back from the rebuild.
        var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        for (var chunk : touched) {
            if (world != null) {
                world.unloadEntitiesByChunk(chunk.x(), chunk.z());
            }

            Schematics.rebuildChunk(chunk);
        }

        return replaced;
    }

    /**
     * The container's own index order, matching {@code LitematicaBlockStateContainer.getIndex}:
     * {@code y * sizeX * sizeZ + z * sizeX + x}. Extracted rather than left inline because an
     * off-by-one here would put blocks back in the wrong places, silently.
     */
    public static int index(int x, int y, int z, int sizeX, int layer) {
        return y * layer + z * sizeX + x;
    }

    public static int y(int index, int layer) {
        return index / layer;
    }

    public static int z(int index, int sizeX, int layer) {
        return (index - y(index, layer) * layer) / sizeX;
    }

    public static int x(int index, int sizeX, int layer) {
        return (index - y(index, layer) * layer) % sizeX;
    }

    static Map<String, Int2ObjectMap<BlockState>> newStateMap() {
        return new LinkedHashMap<>();
    }

    static Int2ObjectMap<BlockState> newRegionMap() {
        return new Int2ObjectOpenHashMap<>();
    }

    static Map<String, Map<BlockPos, CompoundData>> newNbtMap() {
        return new LinkedHashMap<>();
    }
}
