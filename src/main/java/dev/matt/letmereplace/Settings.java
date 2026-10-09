package dev.matt.letmereplace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.matt.letmereplace.gui.SettingsScreen;
import dev.matt.letmereplace.gui.WorldMode;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * LetMeReplace's hotkeys, as malilib hotkeys in malilib's own config screen - the way Litematica
 * keeps its keys, asked for at the keyboard 2026-10-01 in place of vanilla Controls entries.
 *
 * <p>malilib hotkeys take combinations ({@code LEFT_CONTROL,Z}) and are read from malilib's own
 * key state, which also settles the Alt problem the vanilla keys had: a key that is part of one
 * of malilib's modifiers never reached vanilla's click counter at all.
 *
 * <p>Stack and Move are held, not pressed, so they use {@link KeybindSettings#MODIFIER_INGAME}:
 * extra keys allowed - sneak goes with Move - and nothing cancelled, so the key still does
 * whatever else it is bound to. Undo and redo default to Ctrl+Z and Ctrl+Shift+Z, set at the
 * keyboard 2026-10-01; the rest start unbound, since in the instance this was written for every
 * letter is taken.
 *
 * <p>Saved to {@code config/letmereplace.json}.
 */
public final class Settings implements IConfigHandler, IKeybindProvider {

    private static final String KEYS = "letmereplace.config.hotkeys";

    public static final ConfigHotkey OPEN_SETTINGS = new ConfigHotkey("openSettings", "").apply(KEYS);
    public static final ConfigHotkey STACK_BOX =
            new ConfigHotkey("stackBox", "", KeybindSettings.MODIFIER_INGAME).apply(KEYS);
    public static final ConfigHotkey MOVE_BOX =
            new ConfigHotkey("moveBox", "", KeybindSettings.MODIFIER_INGAME).apply(KEYS);
    public static final ConfigHotkey UNDO = new ConfigHotkey("undo", "LEFT_CONTROL,Z").apply(KEYS);
    public static final ConfigHotkey REDO =
            new ConfigHotkey("redo", "LEFT_CONTROL,LEFT_SHIFT,Z").apply(KEYS);

    public static final List<ConfigHotkey> HOTKEYS =
            List.of(OPEN_SETTINGS, STACK_BOX, MOVE_BOX, UNDO, REDO);

    private static final Settings INSTANCE = new Settings();

    private Settings() {
    }

    /** From the client entrypoint; the rest happens when malilib initialises its mods. */
    public static void register() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            ConfigManager.getInstance().registerConfigHandler(LetMeReplace.MOD_ID, INSTANCE);
            InputEventHandler.getKeybindManager().registerKeybindProvider(INSTANCE);
            INSTANCE.load();

            OPEN_SETTINGS.getKeybind().setCallback((action, key) -> {
                GuiBase.openGui(new SettingsScreen());
                return true;
            });
            UNDO.getKeybind().setCallback((action, key) -> WorldMode.undoKey());
            REDO.getKeybind().setCallback((action, key) -> WorldMode.redoKey());
        });
    }

    private static Path file() {
        return FileUtils.getConfigDirectory().resolve(LetMeReplace.MOD_ID + ".json");
    }

    @Override
    public void load() {
        Path file = file();

        if (!Files.isReadable(file)) {
            return;
        }

        JsonElement root = JsonUtils.parseJsonFile(file);

        if (root != null && root.isJsonObject()) {
            ConfigUtils.readConfigBase(root.getAsJsonObject(), "Hotkeys", HOTKEYS);
        }
    }

    @Override
    public void save() {
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
        JsonUtils.writeJsonToFile(root, file());
    }

    @Override
    public void onConfigsChanged() {
        save();
        InputEventHandler.getKeybindManager().updateUsedKeys();
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory("LetMeReplace", "letmereplace.hotkeys.category.box_tools",
                HOTKEYS);
    }
}
