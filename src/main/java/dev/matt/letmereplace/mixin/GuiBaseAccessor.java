package dev.matt.letmereplace.mixin;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read the buttons a screen has already added.
 *
 * <p>Needed to put our own button on Litematica's top row <em>after</em> its buttons: their
 * widths come from a private {@code createButton} helper and a set of translation keys, so the
 * only non-fragile way to know where that row ends is to measure the buttons that are already
 * there. The list is private on {@code GuiBase}, hence this.
 */
@Mixin(GuiBase.class)
public interface GuiBaseAccessor {

    @Accessor("buttons")
    List<ButtonBase> letmereplace$getButtons();
}
