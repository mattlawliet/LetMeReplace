package dev.matt.letmereplace;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;

/**
 * Everything one Replace needs, gathered when the button is clicked.
 *
 * <p>Deliberately <strong>no screen reference</strong>. LMR's equivalent record carried a
 * {@code Screen materialListScreen} field and handed it forward through every step, which is how
 * a chain becomes a pile; here the parent is set once per screen from the live screen stack and
 * never travels as data. See ticket 004.
 */
public record Session(MaterialListBase materialList,
                      LitematicaSchematic schematic,
                      ItemStack rowStack,
                      Map<BlockState, Integer> sourceStates,
                      BlockState modalState,
                      int totalPositions) {

    public static Optional<Session> create(MaterialListBase materialList, MaterialListEntry entry) {
        if (materialList == null || entry == null || entry.getStack().isEmpty()) {
            return Optional.empty();
        }

        LitematicaSchematic schematic = Schematics.behind(materialList);

        if (schematic == null) {
            return Optional.empty();
        }

        ItemStack stack = entry.getStack().copy();
        stack.setCount(1);

        Map<BlockState, Integer> states = Census.of(schematic).statesForItem(stack.getItem());

        if (states.isEmpty()) {
            // With a layer selection in force this is ordinary: the rows are Litematica's, so
            // they cover the whole schematic and most of them have nothing inside the selection.
            // The row's button says so rather than doing nothing - see WidgetMaterialListEntry.
            if (!Scope.isActive(schematic)) {
                // Otherwise it should not happen: rows are generated from states that are
                // present. A bug worth a log line, not a disabled-button UI state (005 P-notes).
                LetMeReplace.LOGGER.warn("Replace row for {} resolved to no block states",
                        stack.getItem());
            }

            return Optional.empty();
        }

        return Optional.of(new Session(materialList, schematic, stack, states,
                Census.modal(states), Census.total(states)));
    }

    public String rowName() {
        return rowStack.getHoverName().getString();
    }
}
