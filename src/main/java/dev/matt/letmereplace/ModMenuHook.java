package dev.matt.letmereplace;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.matt.letmereplace.gui.SettingsScreen;

/** Puts the settings button on LetMeReplace's entry in Mod Menu, when Mod Menu is installed. */
public class ModMenuHook implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
