package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

/**
 * Lets a saved schematic learn where it was written.
 *
 * <p>{@code LitematicaSchematic.schematicFile} is {@code private final} and {@code writeToFile}
 * never touches it, so after a save-as the object keeps reporting the path it was <em>loaded</em>
 * from. Ticket 004 D7 accepted that as a named ceiling and worked around it locally.
 *
 * <p>It then caused three separate bugs: the save name silently losing its tag, a route to
 * overwriting the original that the mod promises never to touch, and "place the original beside
 * this" placing a second copy of the edited version - because
 * {@code SchematicHolder.getOrLoad} matches on {@code getFile()} and found the schematic we had
 * just edited. Each fix was local and none of them addressed the cause.
 *
 * <p>Hence the upgrade D7 named. {@code @Mutable} is required: the field is final.
 */
@Mixin(LitematicaSchematic.class)
public interface LitematicaSchematicAccessor {

    @Mutable
    @Accessor("schematicFile")
    void letmereplace$setFile(Path file);
}
