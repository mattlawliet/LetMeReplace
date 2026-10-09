package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.gui.WorldMode;
import fi.dy.masa.litematica.tool.ToolMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Grid Paste is shown as LetMeReplace's box mode. Its name is the only thing to change: the slot
 * is unimplemented in Litematica 0.28.5 and the behaviour lives in {@link WorldMode}. A mixin
 * rather than a lang entry, because which mod's language file wins for another mod's key is load
 * order, not something to rely on.
 */
@Mixin(value = ToolMode.class, remap = false)
public abstract class ToolModeMixin {

    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void letmereplace$boxModeName(CallbackInfoReturnable<String> callback) {
        if ((Object) this == ToolMode.GRID_PASTE) {
            callback.setReturnValue(WorldMode.MODE_NAME);
        }
    }
}
