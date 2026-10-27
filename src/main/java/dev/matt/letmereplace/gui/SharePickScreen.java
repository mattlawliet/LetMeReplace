package dev.matt.letmereplace.gui;

import dev.matt.letmereplace.Share;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who a schematic is shared with: picking them for a new share (shift-click on the share icon,
 * so it never opens by accident), and changing them later (a click on the green icon, owner only).
 *
 * <p>One screen for both because they are the same question - who is ticked. Editing starts from
 * the server's member list, so it can say who synced, who has not answered, who said no and who
 * left; saving sends the ticked names and the server works out who was added and who was taken
 * off. The search box filters the list, and a name that is not in it can still be invited: the
 * server only accepts players it has seen, so a typo never becomes a member.
 *
 * <p>ponytail: no scrolling - past a screenful the search box is how you get to a name.
 */
public class SharePickScreen extends GuiBase {

    private static final int ROW_H = 22;

    private final LitematicaSchematic schematic;
    private final SchematicPlacement placement;
    private final boolean editing;
    private final Set<String> picked = new LinkedHashSet<>();

    private GuiTextFieldGeneric search;
    private String lastSearch = "";
    /** Editing: the server's list, once it has arrived and been ticked in. */
    private Map<String, String> members;

    /** A new share. */
    public SharePickScreen(LitematicaSchematic schematic, SchematicPlacement placement) {
        this(schematic, placement, false);
    }

    private SharePickScreen(LitematicaSchematic schematic, SchematicPlacement placement, boolean editing) {
        this.schematic = schematic;
        this.placement = placement;
        this.editing = editing;
        this.useTitleHierarchy = false;
        this.setTitle((editing ? "Who has " : "Share ") + Share.displayName(schematic) + (editing ? "" : " with..."));

        if (editing) {
            Share.requestMembers(schematic);
        }
    }

    /** The owner changing who an existing share goes to. */
    public static SharePickScreen edit(LitematicaSchematic schematic) {
        return new SharePickScreen(schematic, null, true);
    }

    private static String self() {
        return Minecraft.getInstance().getUser().getName();
    }

    private static List<String> onlineOthers() {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = new ArrayList<>();

        if (mc.getConnection() == null) {
            return names;
        }

        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();

            if (!name.equals(self())) {
                names.add(name);
            }
        }

        return names;
    }

    /** Everyone worth listing - online players and members - filtered by the search box. */
    private List<String> rows() {
        Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(onlineOthers());

        if (members != null) {
            all.addAll(members.keySet());
        }

        all.addAll(picked);
        String needle = lastSearch.trim().toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();

        for (String name : all) {
            if (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(name);
            }
        }

        return shown;
    }

    /** A search that is a whole player name nobody listed: offered as an invite by name. */
    private String byName() {
        String name = lastSearch.trim();

        if (!name.matches("[A-Za-z0-9_]{3,16}") || name.equalsIgnoreCase(self())) {
            return null;
        }

        for (String row : rows()) {
            if (row.equalsIgnoreCase(name)) {
                return null;
            }
        }

        return name;
    }

    @Override
    public void initGui() {
        super.initGui();

        int x = boxLeft() + 8;
        int y = boxTop() + 22;
        int width = boxWidth() - 16;

        // Kept across rebuilds, so typing does not lose focus or text.
        if (search == null) {
            search = new GuiTextFieldGeneric(x, y, width, 16, this.font);
            search.setMaxLength(32);
            search.setFocusedWrapper(true);
        }

        search.setX(x);
        search.setY(y);
        // malilib's setX/setY skip EditBox's, which is what places the text and cursor - left
        // alone they sit at (0, 0), the stray cursor in the screen corner. setValue re-places
        // them through the overridden getX/getY.
        search.setValue(search.getValue());
        this.addTextField(search, field -> {
            // Rebuilt on the next tick rather than here: this runs inside malilib's own walk
            // over the text fields, which a rebuild would change under it.
            return true;
        });

        y += 22;

        if (editing && members == null) {
            return;     // the list arrives in a moment; tick() rebuilds then
        }

        int bottom = boxTop() + boxHeight() - 56;
        String byName = byName();

        if (byName != null && y <= bottom) {
            this.addButton(new CheckButton(x, y + 3, width, label(byName) + "  (invite by name)",
                    () -> picked.contains(byName)), (button, mouseButton) -> toggle(byName));
            y += ROW_H;
        }

        for (String name : rows()) {
            if (y > bottom) {
                break;
            }

            this.addButton(new CheckButton(x, y + 3, width, label(name), () -> picked.contains(name)),
                    (button, mouseButton) -> toggle(name));
            y += ROW_H;
        }

        y = boxTop() + boxHeight() - 26;
        int centreX = this.width / 2;

        if (editing) {
            this.addButton(new ButtonGeneric(centreX - 154, y, 100, 20, "Save"), this::onSave);
            this.addButton(new ButtonGeneric(centreX - 50, y, 100, 20, "End share"),
                    (button, mouseButton) -> Screens.open(new ShareStopScreen(schematic), this.getParent()));
            this.addButton(new ButtonGeneric(centreX + 54, y, 100, 20, "Cancel"),
                    (button, mouseButton) -> this.closeGui(true));
        } else {
            ButtonGeneric share = new ButtonGeneric(centreX - 104, y, 100, 20, "Share");
            share.setEnabled(!picked.isEmpty());
            share.setHoverStrings(picked.isEmpty() ? "Tick at least one player" : "Send each a [Sync] link");
            this.addButton(share, this::onShare);
            this.addButton(new ButtonGeneric(centreX + 4, y, 100, 20, "Cancel"),
                    (button, mouseButton) -> this.closeGui(true));
        }
    }

    @Override
    public void tick() {
        super.tick();
        boolean rebuild = false;

        if (search != null && !search.getValue().equals(lastSearch)) {
            lastSearch = search.getValue();
            rebuild = true;
        }

        if (editing && members == null && Share.members(schematic) != null) {
            members = Share.members(schematic);

            // Ticked: whoever is in now - synced, or invited and not yet answered.
            for (Map.Entry<String, String> m : members.entrySet()) {
                if (m.getValue().equals("accepted") || m.getValue().equals("invited")) {
                    picked.add(m.getKey());
                }
            }

            rebuild = true;
        }

        if (rebuild) {
            this.initGui();
        }
    }

    private void toggle(String name) {
        if (!picked.remove(name)) {
            picked.add(name);
        }

        this.initGui();
    }

    private String label(String name) {
        String state = members == null ? null : members.get(name);
        String note = state == null ? "" : switch (state) {
            case "accepted" -> "  - synced";
            case "invited" -> "  - invited";
            case "ignored" -> "  - ignored";
            case "left" -> "  - left";
            default -> "";
        };

        return name + note;
    }

    private void onShare(ButtonBase button, int mouseButton) {
        if (picked.isEmpty()) {
            this.addMessage(Message.MessageType.ERROR, "Tick at least one player");
            return;
        }

        if (!Share.share(schematic, placement, new ArrayList<>(picked))) {
            this.addMessage(Message.MessageType.ERROR, "Could not share - see the log");
            return;
        }

        this.closeGui(true);
    }

    private void onSave(ButtonBase button, int mouseButton) {
        Share.setMembers(schematic, new ArrayList<>(picked));
        this.closeGui(true);
    }

    // ------------------------------------------------------------------ painting

    private int boxWidth() {
        return Math.min(280, this.width - 40);
    }

    private int boxHeight() {
        return Math.min(360, this.height - 40);
    }

    private int boxLeft() {
        return (this.width - boxWidth()) / 2;
    }

    private int boxTop() {
        return (this.height - boxHeight()) / 2;
    }

    @Override
    protected void drawScreenBackground(GuiContext context, int mouseX, int mouseY) {
        RenderUtils.drawRect(context, 0, 0, this.width, this.height, Ui.SHADE);
        RenderUtils.drawRect(context, boxLeft(), boxTop(), boxWidth(), boxHeight(), Ui.PANEL);
        RenderUtils.drawOutline(context, boxLeft() - 1, boxTop() - 1, boxWidth() + 2,
                boxHeight() + 2, Ui.EDGE);
        RenderUtils.drawRect(context, boxLeft(), boxTop(), boxWidth(), Ui.TITLE_H, Ui.HEADER_BG);
    }

    @Override
    protected void drawTitle(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        this.drawString(context, this.getTitleString(), boxLeft() + 8, boxTop() + 4, Ui.WHITE);

        String note;

        if (editing && members == null) {
            note = "Asking the server who it's shared with...";
        } else if (editing) {
            note = "Unticked players stop syncing and keep their copy.";
        } else if (onlineOthers().isEmpty() && lastSearch.isBlank()) {
            note = "Nobody else is online - type a name to invite.";
        } else {
            note = "They get a [Sync] link in chat. Your file is left as it is.";
        }

        this.drawString(context, note, boxLeft() + 8, boxTop() + boxHeight() - 44, Ui.GREY);
    }
}
