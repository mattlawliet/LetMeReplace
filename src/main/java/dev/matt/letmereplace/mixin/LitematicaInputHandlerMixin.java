package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.gui.WorldMode;
import fi.dy.masa.litematica.event.InputHandler;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The wheel and the mouse buttons belong to {@link WorldMode} while it is armed.
 *
 * <p>malilib asks its mouse handlers in registration order and stops at the first that says
 * {@code true}, and Fabric does not order entrypoints by dependency, so a handler of our own could
 * run before or after Litematica's - which eats the wheel whenever its tool is held. Taking the
 * head of Litematica's own handler settles the order. Returning {@code true} there also keeps
 * vanilla from changing the hotbar slot. When no mode is armed both injects return at once and
 * Litematica behaves exactly as before.
 */
@Mixin(value = InputHandler.class, remap = false)
public abstract class LitematicaInputHandlerMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void letmereplace$scroll(double mouseX, double mouseY, double amount,
                                     CallbackInfoReturnable<Boolean> callback) {
        if (WorldMode.onScroll(amount)) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "onMouseClick", at = @At("HEAD"), cancellable = true)
    private void letmereplace$click(MouseButtonEvent event, boolean pressed,
                                    CallbackInfoReturnable<Boolean> callback) {
        if (WorldMode.onClick(event.button(), pressed)) {
            callback.setReturnValue(true);
        }
    }
}
