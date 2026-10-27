package dev.matt.letmereplace;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;

import java.util.List;

/**
 * Everything the confirm screen needs, whichever gesture produced it.
 *
 * <p>A single-row Replace and a whole-palette change differ only in how the block pairs were
 * chosen; by the time either reaches the confirm they are one {@link Swap.Plan} and a heading,
 * so they share a screen rather than having two that drift apart.
 */
public record Commit(String title,
                     LitematicaSchematic schematic,
                     MaterialListBase materialList,
                     Swap.Plan plan,
                     List<String> notes,
                     String action) {

    /** The confirm button's words, with %d for the count - "Replace 42" unless said otherwise. */
    public Commit(String title, LitematicaSchematic schematic, MaterialListBase materialList,
                  Swap.Plan plan, List<String> notes) {
        this(title, schematic, materialList, plan, notes, "Replace %d");
    }

    public static Commit of(String title, Session session, Swap.Plan plan) {
        return new Commit(title, session.schematic(), session.materialList(), plan, List.of());
    }
}
