package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code MaterialListPlacement.placement} is private with no getter in litematica 0.28.5.
 * {@code SchematicPlacement.getSchematic()} is public, so only the field needs widening.
 */
@Mixin(MaterialListPlacement.class)
public interface MaterialListPlacementAccessor {

    @Accessor("placement")
    SchematicPlacement letmereplace$getPlacement();
}
