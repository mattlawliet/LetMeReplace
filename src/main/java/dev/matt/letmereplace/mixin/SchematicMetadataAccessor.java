package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.schematic.SchematicMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The two counts the metadata has getters for and no setters.
 *
 * <p>Litematica fills {@code entityCount} and {@code blockEntityCount} when a schematic is read
 * or taken from the world and never again - nothing recomputes them on save. Every other number
 * on the schematic info panel has a setter; these two are simply missing one, so an edited
 * schematic kept reporting the entity count it was loaded with.
 */
@Mixin(SchematicMetadata.class)
public interface SchematicMetadataAccessor {

    @Accessor("entityCount")
    void letmereplace$setEntityCount(int count);

    @Accessor("blockEntityCount")
    void letmereplace$setBlockEntityCount(int count);
}
