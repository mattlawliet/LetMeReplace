package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.tool.ToolMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets Grid Paste - our "Box in schematic" - into the survival tool cycle. Litematica marks it
 * creative-only because it was meant to paste into the world; ours only edits the loaded
 * schematic, which survival play needs as much as creative. {@code ToolMode.cycle} is the only
 * reader of the flag in 0.28.5.
 */
@Mixin(value = ToolMode.class, remap = false)
public interface ToolModeAccessor {

    @Mutable
    @Accessor("creativeOnly")
    void letmereplace$setCreativeOnly(boolean creativeOnly);
}
