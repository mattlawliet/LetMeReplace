package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.MaterialListPlacementAccessor;
import dev.matt.letmereplace.mixin.SchematicMetadataAccessor;
import dev.matt.letmereplace.mixin.MaterialListSchematicAccessor;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;

/**
 * Getting from a material list to the schematic behind it.
 *
 * <p>Both subclasses hold it in a private field with no getter in {@code 0.28.5}, which is the
 * one thing here that genuinely needs mixins - see ticket 003. Everything else on this path is
 * public API, including {@code GuiMaterialList.getMaterialList()}, which LMR appears to have
 * mixed into for nothing.
 */
public final class Schematics {

    private Schematics() {
    }

    public static LitematicaSchematic behind(MaterialListBase materialList) {
        if (materialList instanceof MaterialListSchematic schematicList) {
            return ((MaterialListSchematicAccessor) schematicList).letmereplace$getSchematic();
        }

        if (materialList instanceof MaterialListPlacement placementList) {
            return ((MaterialListPlacementAccessor) placementList).letmereplace$getPlacement()
                    .getSchematic();
        }

        return null;
    }

    /**
     * The placement behind a material list, or {@code null} when there is not one.
     *
     * <p>Only a placement-backed list has one: a list opened straight from a loaded schematic is
     * looking at blocks that are nowhere in the world, so there is no origin, rotation or mirror
     * and no world coordinates to speak of. {@link Layers} is the one caller that cares.
     */
    public static SchematicPlacement placementBehind(MaterialListBase materialList) {
        if (materialList instanceof MaterialListPlacement placementList) {
            return ((MaterialListPlacementAccessor) placementList).letmereplace$getPlacement();
        }

        return null;
    }

    /**
     * Makes the world show what the schematic now contains.
     *
     * <p>Litematica renders from a separate world built out of the placements, so mutating a
     * container changes nothing anybody can see until the placements are rebuilt - the call this
     * whole mod looked broken without.
     *
     * <p>The second call is the one that was missing. The placement manager also caches which
     * sub-chunks are worth drawing, and marking the columns for rebuild does not invalidate that
     * cache, so a sub-chunk emptied by a cut could keep its old geometry: a chunk-shaped
     * rectangle of water left hanging in a layer that had been removed, while the rest of the
     * same layer was correctly gone.
     */
    /**
     * Brings the entity and block entity counts on the schematic info panel back into line with
     * what the schematic now holds.
     *
     * <p>Litematica sets those two when a schematic is read and never again - they are the only
     * numbers on that panel with no setter - so a cut that removed forty item frames still
     * advertised forty. Counting is one pass over the per-region maps, which are small.
     */
    public static void refreshCounts(LitematicaSchematic schematic) {
        int entities = 0;
        int blockEntities = 0;

        for (String region : schematic.getAreas().keySet()) {
            var entityList = schematic.getEntityListForRegion(region);
            var blockEntityMap = schematic.getBlockEntityMapForRegion(region);

            entities += entityList == null ? 0 : entityList.size();
            blockEntities += blockEntityMap == null ? 0 : blockEntityMap.size();
        }

        SchematicMetadataAccessor metadata = (SchematicMetadataAccessor) schematic.getMetadata();
        metadata.letmereplace$setEntityCount(entities);
        metadata.letmereplace$setBlockEntityCount(blockEntities);
    }

    public static void refreshRender(LitematicaSchematic schematic) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();

        manager.markAllPlacementsOfSchematicForRebuild(schematic);
        manager.setVisibleSubChunksNeedsUpdate();
    }

    /**
     * The entity moved to {@code pos}, in its NBT as well as in memory.
     *
     * <p>Litematica writes an entity to file from its NBT alone and reads its position back from
     * the {@code Pos} tag, so a new {@code EntityInfo} with the old NBT moves the entity on screen
     * and nowhere else: Save, or a shared edit, put it back where it was. Found 2026-10-07 when a
     * box move synced its blocks and left the item frames behind. A copy, because the snapshot
     * that undo restores still holds the old one.
     */
    public static LitematicaSchematic.EntityInfo entityAt(LitematicaSchematic.EntityInfo entity,
                                                           net.minecraft.world.phys.Vec3 pos) {
        fi.dy.masa.malilib.util.data.tag.CompoundData nbt = entity.nbt().copy();
        fi.dy.masa.malilib.util.data.tag.util.DataTypeUtils.putVec3dCodec(nbt, pos, "Pos");
        return new LitematicaSchematic.EntityInfo(pos, nbt);
    }
}
