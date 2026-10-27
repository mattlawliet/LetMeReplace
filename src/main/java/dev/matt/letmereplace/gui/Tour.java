package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.LetMeReplace;
import dev.matt.letmereplace.Normalize;
import dev.matt.letmereplace.Schematics;
import dev.matt.letmereplace.Session;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Dev-only screenshot tour for UI review: every LetMeReplace screen at every GUI scale, day and
 * night, plus the in-world HUD panel. Runs once when {@code <gameDir>/lmr-tour} exists (and
 * deletes it), so it never fires in a normal install. Shots land in
 * {@code screenshots/lmr-tour/}.
 */
public final class Tour {

    private static final int SETTLE = 15;              // ticks for a screen to init and draw

    private static final Deque<Runnable> steps = new ArrayDeque<>();
    private static int wait;
    private static boolean started;

    private Tour() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Tour::tick);
    }

    private static void tick(Minecraft mc) {
        if (wait > 0) {
            --wait;
            return;
        }

        if (!started) {
            Path flag = mc.gameDirectory.toPath().resolve("lmr-tour");
            Path probeFlag = mc.gameDirectory.toPath().resolve("lmr-probe");
            boolean probe = Files.exists(probeFlag);

            if (mc.player == null || (!Files.exists(flag) && !probe) || target() == null) {
                return;
            }

            if (probe) {
                try {
                    Files.delete(probeFlag);
                } catch (Exception ignored) {
                }

                started = true;
                planProbe(mc);
                wait = 100;
                return;
            }

            MaterialListBase list = target().getMaterialList();

            if (list.getMaterialsAll().isEmpty()) {
                list.reCreateMaterialList();
                wait = 40;
                return;
            }

            try {
                Files.delete(flag);
            } catch (Exception ignored) {
            }

            started = true;
            plan(mc, list);
            wait = 60;
            return;
        }

        Runnable next = steps.poll();

        if (next != null) {
            try {
                next.run();
            } catch (Throwable t) {
                LetMeReplace.LOGGER.error("tour step failed", t);
            }
        }
    }

    private static SchematicPlacement target() {
        var manager = DataManager.getSchematicPlacementManager();

        for (SchematicPlacement p : manager.getAllSchematicsPlacements()) {
            if (p.getName().equals("TOR")) {
                return p;
            }
        }

        return manager.getSelectedSchematicPlacement();
    }

    private static void plan(Minecraft mc, MaterialListBase list) {
        SchematicPlacement placement = target();
        LitematicaSchematic schematic = Schematics.behind(list);
        GuiMaterialList gui = new GuiMaterialList(list);

        Map<String, Supplier<GuiBase>> screens = new LinkedHashMap<>();
        screens.put("01-material-list", () -> gui);
        // Stress: a long placement name against the title-row buttons. The title is read in the
        // constructor, so the real name goes straight back.
        screens.put("01b-material-list-long-name", () -> {
            String name = placement.getName();
            placement.setName("A rather long placement name for the carrot tower annex");
            GuiMaterialList longer = new GuiMaterialList(list);
            placement.setName(name);
            return longer;
        });
        screens.put("02-picker", () -> new PickerScreen(
                Session.create(list, list.getMaterialsAll().get(0)).orElseThrow()));
        screens.put("03-palette", () -> new PaletteScreen(schematic, list));
        screens.put("04-layers", () -> new LayersScreen(schematic, placement, list));
        screens.put("05-box", () -> new BoxScreen(schematic, placement, list));
        screens.put("06-confirm-normalize", () -> new ConfirmScreen(Normalize.commit(schematic, list)));
        screens.put("07-save", () -> new SaveScreen(schematic));
        screens.put("08-settings", () -> new SettingsScreen(gui));
        screens.put("09-share-pick", () -> new SharePickScreen(schematic, placement));
        screens.put("10-share-stop", () -> new ShareStopScreen(schematic));

        int original = mc.options.guiScale().get();
        int max = mc.getWindow().calculateScale(0, mc.isEnforceUnicode());
        List<Integer> scales = new ArrayList<>();

        for (int s = 1; s <= max; ++s) {
            scales.add(s);
        }

        // The one parser the tour can check without a world: hotkey names for the HUD.
        steps.add(() -> {
            String got = WorldMode.pretty("LEFT_CONTROL + LEFT_ALT + E") + " | " + WorldMode.pretty("WORLD_1")
                    + " | " + WorldMode.pretty("RIGHT_SHIFT + PAGE_UP");
            String want = "Ctrl+Alt+E | World 1 | Shift+Page up";
            LetMeReplace.LOGGER.info("tour check pretty: {}", got.equals(want) ? "ok" : "FAILED " + got);
        });
        cmd(mc, "time set noon");
        cmd(mc, "tp @s -98 92 205 facing -100 80 225");

        for (String time : List.of("day", "night")) {
            if (time.equals("night")) {
                cmd(mc, "time set midnight");
            }

            // Night only at the extremes: the panels are what changes, not the layout.
            List<Integer> pass = time.equals("day") ? scales : List.of(1, max);

            for (int scale : pass) {
                steps.add(() -> {
                    mc.options.guiScale().set(scale);
                    mc.resizeGui();
                });

                screens.forEach((name, screen) -> {
                    steps.add(() -> {
                        GuiBase next = screen.get();

                        if (next != gui) {
                            next.setParent(gui);
                        }

                        GuiBase.openGui(next);
                        wait = SETTLE;
                    });
                    shot(mc, name + "-s" + scale + "-" + time);
                });

                // The in-world HUD panel: tool held in Box in schematic mode.
                steps.add(() -> {
                    GuiBase.openGui(null);
                    mc.player.getInventory().setItem(mc.player.getInventory().getSelectedSlot(),
                            new ItemStack(Items.ARROW));          // litematica.json toolItem here
                    DataManager.setToolMode(ToolMode.GRID_PASTE);
                    wait = SETTLE;
                });
                shot(mc, "11-hud-box-mode-s" + scale + "-" + time);
            }
        }

        steps.add(() -> {
            mc.options.guiScale().set(original);
            mc.resizeGui();
            cmd(mc, "time set noon");
            LetMeReplace.LOGGER.info("tour done");
        });
    }

    /**
     * Move previews in several directions, each held long enough for Litematica to rebuild, then
     * the drawn schematic world compared with the data around the selected box - see Probe.
     */
    private static void planProbe(Minecraft mc) {
        var box = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox();
        net.minecraft.core.BlockPos lo = fi.dy.masa.litematica.util.PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
        net.minecraft.core.BlockPos hi = fi.dy.masa.litematica.util.PositionUtils.getMaxCorner(box.getPos1(), box.getPos2());
        // The placement under the box, as the in-world mode picks it.
        SchematicPlacement placement = WorldMode.placementUnderForProbe(lo, hi);
        LitematicaSchematic schematic = placement.getSchematic();
        net.minecraft.core.BlockPos areaLo = lo.offset(-6, -6, -6);
        net.minecraft.core.BlockPos areaHi = hi.offset(6, 6, 6);
        LivePreview preview = new LivePreview();

        cmd(mc, "time set noon");
        cmd(mc, "tp @s " + (hi.getX() + 8) + " " + (hi.getY() + 6) + " " + (hi.getZ() + 8)
                + " facing " + lo.getX() + " " + lo.getY() + " " + lo.getZ());
        steps.add(() -> LetMeReplace.LOGGER.info("probe: '{}' box {} .. {}, layer mode {}, air {}",
                placement.getName(), lo.toShortString(), hi.toShortString(),
                DataManager.getRenderLayerRange().getLayerMode(), BoxScreen.airReplaces));
        diff(schematic, placement, areaLo, areaHi, "baseline");

        List<net.minecraft.core.Vec3i> moves = List.of(new net.minecraft.core.Vec3i(1, 0, 0),
                new net.minecraft.core.Vec3i(2, 0, 0), new net.minecraft.core.Vec3i(3, 0, 0),
                new net.minecraft.core.Vec3i(-2, 0, 0), new net.minecraft.core.Vec3i(0, 0, 2),
                new net.minecraft.core.Vec3i(0, 2, 0), new net.minecraft.core.Vec3i(0, 0, -3));

        for (var move : moves) {
            String name = "move " + move.toShortString();
            steps.add(() -> {
                preview.show(schematic, placement, lo, hi, List.of(move), true, BoxScreen.airReplaces);
                wait = 60;
            });
            diff(schematic, placement, areaLo, areaHi, "preview " + name);
            shot(mc, "probe-" + name.replace(' ', '_').replace(",", ""));
        }

        // Scrolling for real: a notch a tick, direction changing, then left alone.
        for (int i = 0; i < 3; ++i) {
            for (int x : new int[] {1, 2, 3, 4, 3, 2, -1, -2}) {
                steps.add(() -> {
                    preview.show(schematic, placement, lo, hi,
                            List.of(new net.minecraft.core.Vec3i(x, 0, 0)), true, BoxScreen.airReplaces);
                    wait = 1;
                });
            }

            steps.add(() -> wait = 60);
            diff(schematic, placement, areaLo, areaHi, "fast scroll round " + (i + 1) + " (ends -2,0,0)");
        }

        steps.add(() -> {
            preview.clear();
            wait = 60;
        });
        diff(schematic, placement, areaLo, areaHi, "cleared");

        steps.add(() -> {
            dev.matt.letmereplace.BoxEdit.apply(schematic, placement, lo, hi,
                    List.of(new net.minecraft.core.Vec3i(2, 0, 0)), true, BoxScreen.airReplaces,
                    false, "probe");
            wait = 60;
        });
        diff(schematic, placement, areaLo, areaHi, "executed move 2,0,0");
        steps.add(() -> {
            dev.matt.letmereplace.History.undo(schematic);
            wait = 60;
        });
        diff(schematic, placement, areaLo, areaHi, "undone");
        steps.add(() -> LetMeReplace.LOGGER.info("probe done"));
    }

    private static void diff(LitematicaSchematic schematic, SchematicPlacement placement,
                             net.minecraft.core.BlockPos lo, net.minecraft.core.BlockPos hi,
                             String what) {
        steps.add(() -> {
            for (String line : dev.matt.letmereplace.Probe.diff(schematic, placement, lo, hi)) {
                LetMeReplace.LOGGER.info("probe {}: {}", what, line);
            }
        });
    }

    private static void cmd(Minecraft mc, String command) {
        steps.add(() -> {
            mc.player.connection.sendCommand(command);
            wait = 20;
        });
    }

    private static void shot(Minecraft mc, String name) {
        steps.add(() -> {
            Path out = mc.gameDirectory.toPath().resolve("screenshots/lmr-tour/" + name + ".png");

            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    Files.createDirectories(out.getParent());
                    image.writeToFile(out);
                    LetMeReplace.LOGGER.info("tour shot {}", out);
                } catch (Exception e) {
                    LetMeReplace.LOGGER.error("tour shot failed: {}", name, e);
                }
            });
            wait = 2;
        });
    }
}
