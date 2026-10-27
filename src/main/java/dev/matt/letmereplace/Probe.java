package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only: compares what Litematica's schematic world holds - what is drawn - against what the
 * schematic's own containers say, over a box of world positions. A mismatch is a preview that
 * draws something other than the data it will execute.
 */
public final class Probe {

    private Probe() {
    }

    public static List<String> diff(LitematicaSchematic schematic, SchematicPlacement placement,
                                    BlockPos lo, BlockPos hi) {
        var world = SchematicWorldHandler.getSchematicWorld();
        List<String> out = new ArrayList<>();
        int mismatches = 0;
        int ghosts = 0;
        int missing = 0;

        for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
            BlockState want = expected(schematic, placement, pos);
            BlockState got = world.getBlockState(pos);

            if (!want.equals(got)) {
                ++mismatches;
                ghosts += want.isAir() ? 1 : 0;
                missing += got.isAir() ? 1 : 0;

                if (out.size() < 10) {
                    out.add("  " + pos.toShortString() + " want " + name(want) + " got " + name(got));
                }
            }
        }

        out.add(0, mismatches + " mismatches (" + ghosts + " drawn where the data is air, "
                + missing + " missing where the data has a block)");
        return out;
    }

    private static String name(BlockState state) {
        return state.getBlock().getDescriptionId().replace("block.minecraft.", "");
    }

    private static BlockState expected(LitematicaSchematic schematic, SchematicPlacement placement,
                                       BlockPos pos) {
        for (String region : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region);
            Layers.ToWorld toWorld = Layers.ToWorld.forRegion(schematic, placement, region);

            if (container == null || toWorld == null) {
                continue;
            }

            int[] l = toWorld.toLocal(pos.getX(), pos.getY(), pos.getZ());
            Vec3i size = container.getSize();

            if (l[0] >= 0 && l[1] >= 0 && l[2] >= 0
                    && l[0] < size.getX() && l[1] < size.getY() && l[2] < size.getZ()) {
                return container.get(l[0], l[1], l[2]);
            }
        }

        return Blocks.AIR.defaultBlockState();
    }
}
