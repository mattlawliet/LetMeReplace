package dev.matt.letmereplace.mixin;

import com.google.gson.JsonObject;
import dev.matt.letmereplace.FileGeometry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Saved placements describe the schematic file, not unsaved edits - see {@link FileGeometry}. */
@Mixin(value = SchematicPlacement.class, remap = false)
public abstract class SchematicPlacementMixin {

    @Inject(method = "toJson", at = @At("RETURN"))
    private void letmereplace$asInFile(CallbackInfoReturnable<JsonObject> callback) {
        FileGeometry.patch((SchematicPlacement) (Object) this, callback.getReturnValue());
    }
}
