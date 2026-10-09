package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.LitematicaSchematicAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.FileType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writing the working copy out.
 *
 * <p>Ticket 004's correction to the map: {@code SchematicUtils.saveSchematic(boolean)} is not a
 * save function - it saves the current <em>area selection</em> and opens Litematica's own GUI.
 * The real call is {@link LitematicaSchematic#writeToFile}, which does <strong>not</strong> clear
 * the dirty flag, so this does it.
 */
public final class SaveOp {

    private static final String EXTENSION = ".litematic";

    private SaveOp() {
    }

    public static Path directory() {
        return DataManager.getSchematicsBaseDirectory();
    }

    public static Path fileFor(String name) {
        String fileName = name.endsWith(EXTENSION) ? name : name + EXTENSION;
        return directory().resolve(fileName);
    }

    public static boolean exists(String name) {
        return Files.exists(fileFor(name));
    }

    /**
     * Writes the schematic and clears its dirty flag. Returns false if Litematica refused,
     * having already put its own error on screen.
     */
    public static boolean save(LitematicaSchematic schematic, String name, boolean overwrite) {
        Naming.mark(schematic.getMetadata());

        if (!schematic.writeToFile(directory(), name, overwrite)) {
            return false;
        }

        schematic.getMetadata().setName(name);
        schematic.getMetadata().clearModifiedSinceSaved();
        // The schematic now genuinely lives at the file it was written to. Litematica never does
        // this itself, and three separate bugs came from everything downstream believing the
        // stale path - see LitematicaSchematicAccessor.
        ((LitematicaSchematicAccessor) schematic).letmereplace$setFile(fileFor(name));
        // The file now has the edited regions, so the placements should be saved as they are.
        FileGeometry.forget(schematic);
        return true;
    }

    /**
     * The optional side-by-side (ticket 004 D8). The placements pointing at the edited schematic
     * are already correct - they hold a reference to the very object that was mutated and have
     * been rendering the swapped version live - so the only thing worth offering is bringing the
     * <em>original</em> back from disk to stand next to it.
     */
    public static boolean placeOriginalBeside(LitematicaSchematic edited, Path originalFile) {
        if (originalFile == null || !Files.exists(originalFile)) {
            return false;
        }

        // Read it off disk, deliberately not through SchematicHolder.getOrLoad. That method
        // returns the first loaded schematic whose getFile() matches - and because writeToFile
        // never updates schematicFile, the schematic we just edited still reports the original's
        // path. So getOrLoad handed back the edited copy, and "place the original beside this"
        // placed a second copy of the new version. Reported 2026-09-10.
        LitematicaSchematic original = LitematicaSchematic.createFromFile(
                originalFile.getParent(),
                originalFile.getFileName().toString(),
                FileType.fromFile(originalFile));

        if (original == null) {
            return false;
        }

        original.getMetadata().setName(original.getMetadata().getName() + " (original)");
        // allowDuplicates, because this genuinely is a second schematic sharing one file path -
        // the whole point is having the before and the after loaded at once.
        SchematicHolder.getInstance().addSchematic(original, true);

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        BlockPos origin = besideOrigin(manager, edited);

        SchematicPlacement placement = SchematicPlacement.createFor(
                original, origin, original.getMetadata().getName(), true, true);

        if (placement == null) {
            return false;
        }

        manager.addSchematicPlacement(placement, false);
        return true;
    }

    /** One schematic-width to the east of the edited copy, with a two block gap. */
    private static BlockPos besideOrigin(SchematicPlacementManager manager,
                                         LitematicaSchematic edited) {
        List<SchematicPlacement> existing = manager.getAllPlacementsOfSchematic(edited);
        BlockPos anchor = existing.isEmpty() ? BlockPos.ZERO : existing.get(0).getOrigin();
        Vec3i size = edited.getMetadata().getEnclosingSize();
        int width = size == null ? 16 : Math.abs(size.getX());

        return anchor.offset(width + 2, 0, 0);
    }
}
