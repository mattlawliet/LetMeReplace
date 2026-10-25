package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStatePaletteHashMap;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStatePaletteLinear;
import fi.dy.masa.malilib.util.data.tag.ListData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Reads a palette whose first entry is not air without shifting every id by one.
 *
 * <p>Litematica's palettes are built with air already at id 0, and {@code readFromData} skips
 * entry 0 only when it <em>is</em> air - any other first entry is appended at id 1, the next at
 * id 2, and so on, while the block data still points at the file's ids. A Sponge schematic is
 * converted with its own palette order ({@code setMapping}), so carrot_house came in with
 * blue_wool at 0 and air at 6; saved and reloaded, every air position read id 6 = stone, and
 * every other block read its neighbour in the palette. Found 2026-10-24.
 *
 * <p>The same read runs inside {@code onResize}, so growing such a palette past its bit width
 * in memory did the same thing without a reload. Clearing first puts entry 0 back at id 0 for
 * both paths; a palette that starts with air takes Litematica's own path untouched.
 */
@Mixin(value = {LitematicaBlockStatePaletteHashMap.class, LitematicaBlockStatePaletteLinear.class},
        remap = false)
public abstract class PaletteReadMixin {

    @Inject(method = "readFromData", at = @At("HEAD"))
    private void letmereplace$keepFirstEntry(ListData tagList, CallbackInfo ci) {
        if (tagList.size() > 0
                && !"minecraft:air".equals(tagList.getCompoundAt(0).getString("Name"))) {
            ((ILitematicaBlockStatePalette) (Object) this).setMapping(List.of());
        }
    }
}
