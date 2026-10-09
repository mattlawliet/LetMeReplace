package dev.matt.letmereplace;

import com.google.common.collect.BiMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A variant axis: a dimension along which a block has siblings - wood type, oxidation stage,
 * dye colour, waxed or not.
 *
 * <p>Ticket 006 A1: detection is <strong>per-axis</strong>, not one rule and not one table.
 * Copper and waxing come from Minecraft's own {@code BiMap}s, which is exact, free and survives
 * version changes; wood and colour are derived from ids because no such data exists for them.
 * An id rule for copper is provably wrong - {@code lightning_rod} oxidises and has no "copper"
 * anywhere in its name, and the plain member is {@code copper_block} while the rest are
 * {@code exposed_copper}, so a prefix rule breaks at the base too.
 */
public interface Axis {

    String name();

    /** Every value on this axis, in the order the screen should show them. */
    List<String> values();

    /** Which value this block sits at, or null when the block is not on this axis at all. */
    String valueOf(Block block);

    /** The sibling of {@code block} at {@code value}, or null when it has no counterpart. */
    Block at(Block block, String value);

    static List<Axis> all() {
        return List.of(new Wood(), new Stripped(), new Stone(), new Oxidation(), new Waxed(),
                new Colour());
    }

    // ------------------------------------------------------------------ id-derived axes

    /**
     * Values are a prefix on the id. Longest match wins, which is what keeps {@code dark_oak}
     * from being read as {@code oak} and {@code light_gray} from being read as {@code gray}.
     */
    abstract class Prefixed implements Axis {

        /**
         * Prefixes that sit <em>in front of</em> the axis value and must be carried through.
         * {@code stripped_oak_log} is an oak block; without this the wood axis would not see it
         * and an oak-to-spruce change would silently skip every stripped log.
         */
        protected List<String> modifiers() {
            return List.of();
        }

        /**
         * Roles that mean the same thing under different names. Crimson has {@code stem} where
         * oak has {@code log}, and {@code hyphae} where oak has {@code wood} - without this an
         * oak-to-crimson change silently skips every log in the build.
         */
        protected List<String> aliasesFor(String role) {
            return List.of();
        }

        private String modifierOf(String path) {
            for (String modifier : modifiers()) {
                if (path.startsWith(modifier)) {
                    return modifier;
                }
            }

            return "";
        }

        @Override
        public String valueOf(Block block) {
            String path = path(block);

            if (path == null) {
                return null;
            }

            String rest = path.substring(modifierOf(path).length());

            for (String value : values()) {                 // values() is longest-first
                // The bare value is a block in its own right on some axes - `granite` as well as
                // `granite_stairs` - so an exact match counts, with an empty role.
                if (rest.equals(value) || rest.startsWith(value + "_")) {
                    return value;
                }
            }

            return null;
        }

        @Override
        public Block at(Block block, String value) {
            String current = valueOf(block);

            if (current == null) {
                return null;
            }

            String path = path(block);
            String modifier = modifierOf(path);
            String rest = path.substring(modifier.length());
            String role = rest.length() == current.length()
                    ? ""
                    : rest.substring(current.length() + 1);

            Block direct = lookup(compose(modifier, value, role));

            if (direct != null) {
                return direct;
            }

            for (String alias : aliasesFor(role)) {
                Block aliased = lookup(compose(modifier, value, alias));

                if (aliased != null) {
                    return aliased;
                }
            }

            return null;
        }

        private static String compose(String modifier, String value, String role) {
            return role.isEmpty() ? modifier + value : modifier + value + "_" + role;
        }
    }

    /** Wood type. Twelve values, derived from every {@code *_planks} in the registry. */
    class Wood extends Prefixed {

        private static final List<String> VALUES = derive();

        private static List<String> derive() {
            List<String> out = new ArrayList<>();

            for (Block block : BuiltInRegistries.BLOCK) {
                String path = path(block);

                if (path != null && path.endsWith("_planks")) {
                    out.add(path.substring(0, path.length() - "_planks".length()));
                }
            }

            // Longest first so `dark_oak` is matched before `oak` would be.
            out.sort(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
            return List.copyOf(out);
        }

        @Override
        public String name() {
            return "Wood";
        }

        @Override
        protected List<String> modifiers() {
            return List.of("stripped_");
        }

        /**
         * The nether woods and bamboo name their log and wood forms differently: crimson has
         * `stem` and `hyphae`, bamboo has `block`. Asked for at the keyboard 2026-09-10 - a
         * hyphae is the wood form of a stem, and should convert as one.
         */
        @Override
        protected List<String> aliasesFor(String role) {
            return switch (role) {
                case "log" -> List.of("stem", "block");
                case "stem" -> List.of("log", "block");
                case "block" -> List.of("log", "stem");
                case "wood" -> List.of("hyphae");
                case "hyphae" -> List.of("wood");
                default -> List.of();
            };
        }

        @Override
        public List<String> values() {
            return VALUES;
        }
    }

    /**
     * Stone types. No shared affix and no game data, so both the values and the modifiers are
     * listed - but they were measured, not guessed: of these twelve, eleven exist as a bare
     * block, eight have slabs and stairs, seven have walls, and seven have a polished form.
     * Members without a counterpart are skipped and counted like any other (A3).
     */
    class Stone extends Prefixed {

        /**
         * Bare blocks with no stairs, so nothing derives them, but which are built with
         * constantly. Kept short on purpose - everything else comes from the registry.
         */
        private static final List<String> EXTRAS =
                List.of("deepslate", "calcite", "basalt", "dripstone_block", "netherrack");

        private static final List<String> VALUES = derive();

        /**
         * Every family that has stairs, minus the woods (they have their own axis) and minus
         * anything copper (the oxidation and waxed axes own those). That is 52 families in 26.2 -
         * a hand-written list of twelve missed prismarine, sandstone, purpur, quartz, the brick
         * families and more, which is why this is derived.
         *
         * <p>Deliberately flat: {@code polished_granite} is its own value rather than a modifier
         * on {@code granite}. It keeps the rule simple and it is what "stone variants" means when
         * you are looking at the list.
         */
        private static List<String> derive() {
            List<String> woods = new ArrayList<>();
            List<String> out = new ArrayList<>();

            for (Block block : BuiltInRegistries.BLOCK) {
                String path = path(block);

                if (path != null && path.endsWith("_planks")) {
                    woods.add(path.substring(0, path.length() - "_planks".length()));
                }
            }

            for (Block block : BuiltInRegistries.BLOCK) {
                String path = path(block);

                if (path == null || !path.endsWith("_stairs")) {
                    continue;
                }

                String root = path.substring(0, path.length() - "_stairs".length());

                if (root.contains("copper")) {
                    continue;                               // the oxidation axis owns those
                }

                boolean wooden = woods.stream()
                        .anyMatch(wood -> root.equals(wood) || root.startsWith(wood + "_"));

                if (!wooden) {
                    out.add(root);
                }
            }

            for (String extra : EXTRAS) {
                if (lookup(extra) != null && !out.contains(extra)) {
                    out.add(extra);
                }
            }

            // Longest first, so `cobbled_deepslate` is never read as `deepslate` and
            // `polished_blackstone_brick` is never read as `polished_blackstone`.
            out.sort(Comparator.comparingInt(String::length).reversed().thenComparing(v -> v));
            return List.copyOf(out);
        }

        @Override
        public String name() {
            return "Stone";
        }

        @Override
        public List<String> values() {
            return VALUES;
        }
    }

    /** Dye colour. The sixteen are fixed by the game and are not derivable from a marker block. */
    class Colour extends Prefixed {

        private static final List<String> VALUES = List.of(
                "light_gray", "light_blue", "magenta", "orange", "purple", "yellow", "black",
                "brown", "green", "white", "blue", "cyan", "gray", "lime", "pink", "red");

        @Override
        public String name() {
            return "Colour";
        }

        @Override
        public List<String> values() {
            return VALUES;                                   // already longest-first
        }
    }

    // --------------------------------------------------------------- game-data axes

    /**
     * Oxidation. Walks {@link WeatheringCopper}'s own chain rather than parsing ids.
     *
     * <p>A4: waxed-ness is preserved. A waxed block is unwaxed, moved along the chain, then
     * re-waxed - waxing is a decision the builder made, and stripping it silently would be the
     * same class of loss as dropping waterlogging.
     */
    class Oxidation implements Axis {

        private static final List<String> VALUES =
                List.of("unaffected", "exposed", "weathered", "oxidized");

        @Override
        public String name() {
            return "Oxidation";
        }

        @Override
        public List<String> values() {
            return VALUES;
        }

        @Override
        public String valueOf(Block block) {
            Block bare = unwax(block);
            int stage = 0;
            Block cursor = bare;

            while (true) {
                Block previous = WeatheringCopper.getPrevious(cursor).orElse(null);

                if (previous == null) {
                    break;
                }

                cursor = previous;
                ++stage;
            }

            // A block with neither a previous nor a next is simply not on the chain.
            if (stage == 0 && WeatheringCopper.getNext(bare).isEmpty()) {
                return null;
            }

            return stage < VALUES.size() ? VALUES.get(stage) : null;
        }

        @Override
        public Block at(Block block, String value) {
            String current = valueOf(block);

            if (current == null) {
                return null;
            }

            Block cursor = unwax(block);
            int from = VALUES.indexOf(current);
            int to = VALUES.indexOf(value);

            for (int i = from; i < to; ++i) {
                cursor = WeatheringCopper.getNext(cursor).orElse(null);

                if (cursor == null) {
                    return null;
                }
            }

            for (int i = from; i > to; --i) {
                cursor = WeatheringCopper.getPrevious(cursor).orElse(null);

                if (cursor == null) {
                    return null;
                }
            }

            return isWaxed(block) ? wax(cursor) : cursor;
        }
    }

    /**
     * Stripped or not.
     *
     * <p>Unlike copper, the naming here is exactly regular - all 23 stripped blocks are
     * {@code stripped_} plus their base, and every one of those bases exists - so a prefix rule
     * is complete and {@code AxeItem.STRIPPABLES}, which is protected and would need a mixin,
     * buys nothing.
     */
    class Stripped implements Axis {

        private static final String PREFIX = "stripped_";
        private static final List<String> VALUES = List.of("unstripped", "stripped");

        @Override
        public String name() {
            return "Stripped";
        }

        @Override
        public List<String> values() {
            return VALUES;
        }

        @Override
        public String valueOf(Block block) {
            String path = path(block);

            if (path == null) {
                return null;
            }

            if (path.startsWith(PREFIX)) {
                return "stripped";
            }

            return lookup(PREFIX + path) != null ? "unstripped" : null;
        }

        @Override
        public Block at(Block block, String value) {
            String current = valueOf(block);

            if (current == null || current.equals(value)) {
                return current == null ? null : block;
            }

            String path = path(block);

            return "stripped".equals(value)
                    ? lookup(PREFIX + path)
                    : lookup(path.substring(PREFIX.length()));
        }
    }

    /** Waxed or not - its own axis, per A4. */
    class Waxed implements Axis {

        private static final List<String> VALUES = List.of("unwaxed", "waxed");

        @Override
        public String name() {
            return "Waxed";
        }

        @Override
        public List<String> values() {
            return VALUES;
        }

        @Override
        public String valueOf(Block block) {
            if (isWaxed(block)) {
                return "waxed";
            }

            return waxables().containsKey(block) ? "unwaxed" : null;
        }

        @Override
        public Block at(Block block, String value) {
            if (valueOf(block) == null) {
                return null;
            }

            return "waxed".equals(value) ? wax(unwax(block)) : unwax(block);
        }
    }

    // ------------------------------------------------------------------------ helpers

    static BiMap<Block, Block> waxables() {
        return HoneycombItem.WAXABLES.get();
    }

    static BiMap<Block, Block> waxOff() {
        return HoneycombItem.WAX_OFF_BY_BLOCK.get();
    }

    static boolean isWaxed(Block block) {
        return waxOff().containsKey(block);
    }

    static Block unwax(Block block) {
        Block bare = waxOff().get(block);
        return bare == null ? block : bare;
    }

    static Block wax(Block block) {
        Block waxed = waxables().get(block);
        return waxed == null ? block : waxed;
    }

    static String path(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : id.getPath();
    }

    /**
     * Plain registry lookup.
     *
     * <p>Deliberately <strong>not</strong> the picker's catalogue rule. The picker only offers
     * blocks with an item of their own, because you cannot sensibly choose one you could never
     * hold - but a counterpart is not chosen, it is derived from a block already in the build.
     * Applying the stricter rule here made {@code spruce_wall_sign} report no oak counterpart,
     * since wall signs have no item, and a wood change would have stranded every wall sign in
     * the schematic.
     */
    static Block lookup(String path) {
        Identifier id = Identifier.tryParse("minecraft:" + path);

        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(id);
        return block == null || block.defaultBlockState().isAir() ? null : block;
    }

    static String pretty(String value) {
        String spaced = value.replace('_', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}
