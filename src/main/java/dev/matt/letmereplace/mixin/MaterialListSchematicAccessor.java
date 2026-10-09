package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code MaterialListSchematic.schematic} is private with no getter in litematica 0.28.5.
 * One of only two accessors LetMeReplace genuinely needs.
 */
@Mixin(MaterialListSchematic.class)
public interface MaterialListSchematicAccessor {

    @Accessor("schematic")
    LitematicaSchematic letmereplace$getSchematic();
}
