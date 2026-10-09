package dev.matt.letmereplace;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

/**
 * Undo and redo as the player sees them: the step itself, the pending count and dirty flag that
 * go with it, and every open material list brought back into line. The title-row button, the
 * settings screen and the hotkeys all come through here, so they cannot disagree.
 */
public final class History {

    private History() {
    }

    /** What undo and redo return when a shared schematic's server does the step instead. */
    public static final int SENT = -1;

    public static int undo(LitematicaSchematic schematic) {
        // Shared: one history for the group, and it is the server's (map, round 1).
        if (Share.idOf(schematic) != null) {
            return Share.step(schematic, true) ? SENT : 0;
        }

        int restored = schematic == null ? 0 : UndoLog.undo(schematic);

        if (restored > 0) {
            Pending.undoSwap(schematic);
            refreshLists(schematic);
        }

        return restored;
    }

    public static int redo(LitematicaSchematic schematic) {
        if (Share.idOf(schematic) != null) {
            return Share.step(schematic, false) ? SENT : 0;
        }

        int changed = schematic == null ? 0 : UndoLog.redo(schematic);

        if (changed > 0) {
            Pending.recordSwap(schematic);
            schematic.getMetadata().setModifiedSinceSaved();
            refreshLists(schematic);
        }

        return changed;
    }

    /** The material list behind each placement of the schematic, recounted. */
    static void refreshLists(LitematicaSchematic schematic) {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager()
                .getAllPlacementsOfSchematic(schematic)) {
            if (placement.getMaterialList() != null) {
                Recount.later(placement.getMaterialList());
            }
        }
    }
}
