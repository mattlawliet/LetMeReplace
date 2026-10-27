package dev.matt.letmereplace;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A state-preserving material swap for Litematica: Replace on any material-list row swaps that
 * material everywhere in the loaded schematic, keeping each block's rotation and state.
 *
 * <p>Almost nothing is registered here. The mod's entry points are buttons injected into
 * Litematica's material list - see the mixin package. The exceptions are the box tools' in-world
 * half: the preview tint (malilib's world-last hook), and the Stack and Move keys with their HUD
 * panel and tick - see {@link dev.matt.letmereplace.gui.WorldMode}.
 */
public class LetMeReplace implements ClientModInitializer {

    public static final String MOD_ID = "letmereplace";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        dev.matt.letmereplace.gui.PreviewOverlay.register();
        Settings.register();
        Recount.register();
        Schematics.register();
        Share.register();
        dev.matt.letmereplace.gui.WorldMode.register();
        dev.matt.letmereplace.gui.Tour.register();
        LOGGER.info("LetMeReplace ready");
    }
}
