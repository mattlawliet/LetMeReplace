package dev.matt.letmereplace.mixin;

import fi.dy.masa.malilib.gui.button.ButtonBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read a button's label.
 *
 * <p>Only used to tell SchematicPreview's row Replace button apart from ours. Its button is a
 * plain {@code ButtonGeneric} and its listener lambda is merged into Litematica's class by
 * Mixin, so the class name identifies nothing; the label is what is left. The field is
 * protected on {@code ButtonBase}, hence this.
 */
@Mixin(ButtonBase.class)
public interface ButtonBaseAccessor {

    @Accessor("displayString")
    String letmereplace$getDisplayString();
}
