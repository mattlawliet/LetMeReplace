package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.BoxEdit;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * One live box preview: applied for real, tinted, and taken off again with the dirty flag put
 * back. Shared by the box screen and the in-world scroll modes so the two cannot drift apart in
 * how a preview is undone - that is the part that loses work when it is wrong.
 */
final class LivePreview {

    private LitematicaSchematic schematic;
    private Runnable revert;
    private boolean dirtyBefore;

    boolean isShowing() {
        return revert != null;
    }

    /** Takes any previous preview off, then shows this one. Returns what it did. */
    BoxEdit.Plan show(LitematicaSchematic schematic, SchematicPlacement placement,
                      BlockPos pos1, BlockPos pos2, List<Vec3i> offsets, boolean move,
                      boolean airReplaces) {
        clear();

        this.schematic = schematic;
        this.dirtyBefore = schematic.getMetadata().wasModifiedSinceSaved();

        BoxEdit.Preview preview = BoxEdit.preview(schematic, placement, pos1, pos2, offsets, move,
                airReplaces);
        this.revert = preview.revert();

        if (revert != null) {
            BlockPos lo = PositionUtils.getMinCorner(pos1, pos2);
            BlockPos hi = PositionUtils.getMaxCorner(pos1, pos2);
            List<BlockPos[]> copies = new ArrayList<>();

            for (Vec3i d : offsets) {
                copies.add(new BlockPos[] {lo.offset(d), hi.offset(d)});
            }

            PreviewOverlay.show(copies, move ? new BlockPos[] {lo, hi} : null);
        }

        return preview.plan();
    }

    /** Safe to call when nothing is showing; called before anything measures the schematic. */
    void clear() {
        PreviewOverlay.clear();

        if (revert != null) {
            revert.run();
            revert = null;

            if (!dirtyBefore) {
                schematic.getMetadata().clearModifiedSinceSaved();
            }
        }
    }
}
