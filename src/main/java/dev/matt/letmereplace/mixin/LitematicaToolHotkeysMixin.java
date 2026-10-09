package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.gui.WorldMode;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Litematica's corner and Execute operation hotkeys, for our "Box in schematic" mode.
 *
 * <p>Grid Paste is flagged as a mode that uses a schematic, so in it Litematica's corner keys
 * move the selected <em>placement</em> to wherever the player aims - reported 2026-10-01, a click
 * in the air sent the build millions of blocks away. In our mode the same keys set our box's
 * corners on the schematic block aimed at, and Litematica never sees them. Execute operation
 * prints a pending stack or move into the schematic, the way it runs Litematica's own Fill and
 * Paste. With nothing of ours pending, Execute and every other hotkey reach Litematica untouched.
 */
@Mixin(targets = "fi.dy.masa.litematica.event.KeyCallbacks$KeyCallbackHotkeys", remap = false)
public abstract class LitematicaToolHotkeysMixin {

    @Inject(method = "onKeyAction", at = @At("HEAD"), cancellable = true)
    private void letmereplace$boxCorners(KeyAction action, IKeybind key,
                                         CallbackInfoReturnable<Boolean> callback) {
        // Execute first and without the tool check: a stack started from the box screen is
        // pending without the tool in hand, and Execute is still how it is kept.
        if (key == Hotkeys.EXECUTE_OPERATION.getKeybind() && WorldMode.execute()) {
            callback.setReturnValue(true);
            return;
        }

        if (!WorldMode.inMode()) {
            return;
        }

        if (key == Hotkeys.TOOL_PLACE_CORNER_1.getKeybind()) {
            WorldMode.setCorner(Corner.CORNER_1);
            callback.setReturnValue(true);
        } else if (key == Hotkeys.TOOL_PLACE_CORNER_2.getKeybind()) {
            WorldMode.setCorner(Corner.CORNER_2);
            callback.setReturnValue(true);
        }
    }
}
