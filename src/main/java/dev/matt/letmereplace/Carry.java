package dev.matt.letmereplace;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ticket 001's carry rule.
 *
 * <p>Two properties are the same property when they share a name <em>and</em> the old block's
 * value is legal in the target's allowed values, decided per state. That is equivalent to
 * Litematica's {@code Property}-instance test everywhere in vanilla - all 1196 blocks use 93
 * property names across 121 instances with no (name, domain) pair split between two of them -
 * but it is more forgiving of modded blocks, and it carries furnace {@code facing=north} into a
 * piston, which instance matching would silently default.
 */
public final class Carry {

    private Carry() {
    }

    /**
     * The new state for one source state, or {@code null} when this state has a property whose
     * value has nowhere legal to go (R3) - in which case every position holding it is left
     * alone and counted as skipped.
     */
    public static BlockState onto(BlockState source, Block target) {
        // Keep the mounting form the source had. Minecraft models floor and wall variants as
        // two blocks rather than two states, and both share one material-list row, so without
        // this a wall torch swapped to soul_torch became a floor soul torch attached to nothing.
        // See Forms - the property carry below then works as normal, because the wall twin has
        // the `facing` the wall source needs.
        Block formed = Forms.matching(source.getBlock(), target);
        BlockState out = formed.defaultBlockState();

        for (Property<?> sourceProp : source.getProperties()) {
            Property<?> targetProp = formed.getStateDefinition().getProperty(sourceProp.getName());

            if (targetProp == null) {
                continue;                                  // R2: the target lacks it, drop it
            }

            String value = valueName(source, sourceProp);
            BlockState carried = withValue(out, targetProp, value);

            if (carried == null) {
                carried = connectionSide(out, targetProp, value);   // R7
            }

            if (carried == null) {
                return null;                               // R3: no legal value, skip the position
            }

            out = carried;                                 // R1
        }

        return out;                                        // R4 rides along in defaultBlockState()
    }

    /** The four connection sides, the only property names whose domains mean the same thing. */
    private static final Set<String> SIDES = Set.of("north", "east", "south", "west");

    /** "Nothing on this side", and "something on this side", each domain in its own words. */
    private static final List<String> UNCONNECTED = List.of("false", "none");
    private static final List<String> CONNECTED = List.of("true", "low", "side");

    /**
     * R7, added 2026-09-14: a connection side carries across its three domains.
     *
     * <p>{@code north/east/south/west} are booleans on the 49 fences, panes and bars,
     * {@code none/low/tall} on the 33 walls, and {@code none/side/up} on redstone wire. They are
     * the same fact - is this side connected - written three ways, so R3 was reading a domain
     * difference as a value with nowhere to go: every position of a wall skipped when swapped to
     * a fence, the picker offered "0/N", and the confirm button stayed greyed out. Translating
     * between the domains is a carry under R1, not a default under R4.
     *
     * <p>Deliberately the four sides only. The other twelve clashing names disagree about more
     * than spelling - slab {@code type=double} is not chest {@code type=single}, and stair
     * {@code half=bottom} is not door {@code half=lower} - so those stay skipped and counted.
     */
    private static BlockState connectionSide(BlockState state, Property<?> property, String value) {
        if (!SIDES.contains(property.getName())) {
            return null;
        }

        for (String alias : UNCONNECTED.contains(value) ? UNCONNECTED : CONNECTED) {
            BlockState carried = withValue(state, property, alias);

            if (carried != null) {
                return carried;
            }
        }

        return null;
    }

    /** True when the source is waterlogged and the target has no {@code waterlogged} at all. */
    public static boolean dropsWater(BlockState source, Block target) {
        target = Forms.matching(source.getBlock(), target);
        Property<?> sourceProp = source.getBlock().getStateDefinition().getProperty("waterlogged");

        return sourceProp != null
                && "true".equals(valueName(source, sourceProp))
                && target.getStateDefinition().getProperty("waterlogged") == null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(BlockState state, Property<?> property) {
        return ((Property) property).getName(state.getValue(property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withValue(BlockState state, Property<?> property, String valueName) {
        Optional<? extends Comparable<?>> value = property.getValue(valueName);

        return value.isEmpty() ? null : state.setValue((Property) property, (Comparable) value.get());
    }
}
