package dev.matt.letmereplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps Litematica's saved placements in line with the schematic file, not with unsaved edits.
 *
 * <p>Growing a region moves its position in memory, and the placements are reset onto the new
 * position. Litematica writes each placement's sub-region positions into the world's JSON when
 * the world closes - but the schematic it reloads next time is the file, which never saw the
 * edit. The sub-region then sits wherever the unsaved edit had moved it: reported 2026-10-01, a
 * 5x5x5 tile stacked west and north came back displaced by (-10, 0, -3).
 *
 * <p>So each placement's sub-region JSON is remembered the first time a resize touches it, and
 * written back over whatever Litematica would save, until a Save puts the edit into a file. Any
 * sub-region rotation or move the user had set comes back with it on reload, which the in-memory
 * reset had wiped.
 *
 * <p>A placement made <em>after</em> the resize - the edited schematic placed a second time -
 * never had a file state to remember, so the region positions the file has are kept as well, and
 * those placements get the file's position for each sub-region.
 *
 * <p>Weak keys: a removed placement or an unloaded schematic must not be kept alive by this.
 */
public final class FileGeometry {

    private static final Map<SchematicPlacement, Map<String, JsonObject>> AS_IN_FILE =
            new WeakHashMap<>();

    private static final Map<LitematicaSchematic, Map<String, BlockPos>> FILE_POSITIONS =
            new WeakHashMap<>();

    private FileGeometry() {
    }

    /**
     * Before regions are swapped: their positions as they are now, kept only the first time, so
     * after any number of edits this still holds what the file has.
     */
    static synchronized void rememberRegions(LitematicaSchematic schematic,
                                             Map<String, UndoLog.Region> before) {
        Map<String, BlockPos> positions =
                FILE_POSITIONS.computeIfAbsent(schematic, s -> new LinkedHashMap<>());

        for (Map.Entry<String, UndoLog.Region> region : before.entrySet()) {
            if (region.getValue().position() != null) {
                positions.putIfAbsent(region.getKey(), region.getValue().position());
            }
        }
    }

    /** Before a reset: remember each placement as the file has it, once. */
    static synchronized void remember(LitematicaSchematic schematic) {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager()
                .getAllPlacementsOfSchematic(schematic)) {
            AS_IN_FILE.computeIfAbsent(placement, p -> {
                Map<String, JsonObject> regions = new LinkedHashMap<>();

                for (SubRegionPlacement region : p.getAllSubRegionsPlacements()) {
                    regions.put(region.getName(), region.toJson());
                }

                return regions;
            });
        }
    }

    /** The edit is in a file now, so the placements as they are in memory are the truth. */
    public static synchronized void forget(LitematicaSchematic schematic) {
        AS_IN_FILE.keySet().removeIf(placement -> placement.getSchematic() == schematic);
        FILE_POSITIONS.remove(schematic);
    }

    /** From the end of {@code SchematicPlacement.toJson}: puts the file's sub-regions back. */
    public static synchronized void patch(SchematicPlacement placement, JsonObject json) {
        Map<String, JsonObject> asInFile = AS_IN_FILE.get(placement);
        Map<String, BlockPos> filePositions = FILE_POSITIONS.get(placement.getSchematic());

        if ((asInFile == null && filePositions == null) || json == null
                || !json.has("placements")) {
            return;
        }

        for (JsonElement element : json.getAsJsonArray("placements")) {
            JsonObject entry = element.getAsJsonObject();

            if (!entry.has("name")) {
                continue;
            }

            String name = entry.get("name").getAsString();
            JsonObject saved = asInFile == null ? null : asInFile.get(name);
            BlockPos pos = filePositions == null ? null : filePositions.get(name);

            if (saved != null) {
                entry.add("placement", saved.deepCopy());
            } else if (pos != null && entry.has("placement")) {
                JsonArray xyz = new JsonArray();
                xyz.add(pos.getX());
                xyz.add(pos.getY());
                xyz.add(pos.getZ());
                entry.getAsJsonObject("placement").add("pos", xyz);
            }
        }
    }
}
