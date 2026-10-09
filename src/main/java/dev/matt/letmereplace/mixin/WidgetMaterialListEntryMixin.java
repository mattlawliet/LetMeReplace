package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.LetMeReplace;
import dev.matt.letmereplace.Schematics;
import dev.matt.letmereplace.Scope;
import dev.matt.letmereplace.Session;
import dev.matt.letmereplace.gui.PickerScreen;
import dev.matt.letmereplace.gui.Screens;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mod's whole entry point: a Replace button on every material-list row.
 *
 * <p>The row is Litematica's class but a malilib widget, so the button goes in through
 * {@code WidgetContainer}'s protected {@code addButton} - reachable from a mixin on the class
 * itself, no access widening needed (ticket 003).
 *
 * <p>The button is laid out right-to-left from the row's right edge, the same way Litematica
 * sizes its own Ignore button, so the two never collide whatever the GUI scale.
 */
@Mixin(WidgetMaterialListEntry.class)
public abstract class WidgetMaterialListEntryMixin extends WidgetContainer {

    @Shadow(remap = false)
    @Final
    private MaterialListBase materialList;

    @Shadow(remap = false)
    @Final
    private MaterialListEntry entry;

    @Unique
    private ButtonGeneric letmereplace$button;

    @Unique
    private boolean letmereplace$checkedForDuplicate;

    /** Static so the log line lands once a session, not once per row and again on every scroll. */
    @Unique
    private static boolean letmereplace$loggedDuplicate;

    protected WidgetMaterialListEntryMixin(int x, int y, int width, int height) {
        // Never called: mixin classes are templates. This only satisfies the superclass.
        super(x, y, width, height);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void letmereplace$addReplaceButton(int x, int y, int width, int height, boolean isOdd,
                                               MaterialListBase materialList,
                                               MaterialListEntry entry, int listIndex,
                                               WidgetListMaterialList listWidget,
                                               CallbackInfo callbackInfo) {
        if (entry == null || entry.getStack().isEmpty()) {
            return;
        }

        // Sized the way Litematica sizes the Ignore button it sits beside: construct one
        // right-aligned against the row's right edge, then right-align ours against its left.
        ButtonGeneric ignoreSizing = new ButtonGeneric(x + width, y + 1, -1, true,
                "litematica.gui.button.material_list.ignore");
        ButtonGeneric replace = new ButtonGeneric(ignoreSizing.getX(), y + 1, -1, true,
                "letmereplace.gui.button.replace");

        this.letmereplace$button = replace;

        this.addButton(replace, (button, mouseButton) -> {
            // The live screen, read at click time - never a screen carried in a data record,
            // which is the mistake that turned LMR's chain into a pile.
            Screen current = GuiUtils.getCurrentScreen();

            java.util.Optional<Session> session = Session.create(this.materialList, this.entry);

            if (session.isPresent()) {
                Screens.open(new PickerScreen(session.get()), current);
                return;
            }

            // With a layer selection in force, a row with nothing inside it is the ordinary case -
            // the rows are Litematica's and cover the whole schematic. A button that did nothing
            // would read as a broken button, so it says which of the two it is.
            if (Scope.isActive(Schematics.behind(this.materialList))) {
                InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
                        "No " + this.entry.getStack().getHoverName().getString()
                                + " inside layers " + Scope.label());
            }
        });
    }

    /**
     * Drop SchematicPreview's row Replace button when that mod is also installed.
     *
     * <p>It adds its own button from an inject on the <em>list</em> widget, which runs after this
     * widget's constructor, so the row has to be live before it can be seen - hence render and
     * not the constructor. Once is enough: rows are rebuilt, not mutated.
     *
     * <p>The two buttons do the same thing from the same place and are both right-aligned into the
     * same strip beside Ignore, so they overlap. Ours is the superset - same block-to-block swap,
     * plus the palette axes, the skip counts and undo - so theirs is the one that goes.
     *
     * <p>Matched by label because nothing else identifies it: it is a plain {@code ButtonGeneric}
     * and Mixin merges its listener lambda into Litematica's class. Ours reads "Replace" too, so
     * it is excluded by identity. With SchematicPreview absent the key stays untranslated and
     * matches nothing.
     */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void letmereplace$dropDuplicateReplaceButton(GuiContext context, int mouseX,
                                                         int mouseY, boolean selected,
                                                         CallbackInfo callbackInfo) {
        if (this.letmereplace$checkedForDuplicate) {
            return;
        }
        this.letmereplace$checkedForDuplicate = true;

        String theirs = StringUtils.translate("gui.schematicpreview.replace_block");

        boolean removed = this.subWidgets.removeIf(widget -> widget != this.letmereplace$button
                && widget instanceof ButtonBase button
                && theirs.equals(((ButtonBaseAccessor) button).letmereplace$getDisplayString()));

        if (removed && !letmereplace$loggedDuplicate) {
            letmereplace$loggedDuplicate = true;
            LetMeReplace.LOGGER.info("dropped SchematicPreview's duplicate Replace button");
        }
    }
}
