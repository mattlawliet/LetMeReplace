package dev.matt.letmereplace;

import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Making the faces of a stacked or moved box agree with their new neighbours - ticket 007's seam
 * rule.
 *
 * <p>States at a box's faces were saved next to whatever stood beside them, so a copy can leave a
 * fence pointing at nothing or a torch hanging off a wall that moved away. Every position whose
 * neighbour changed is asked what it would become, face by face, with the game's own
 * {@code BlockState.updateShape} - the call the game makes when a neighbour changes - so each
 * block family brings its own rules and none of them is rewritten here:
 *
 * <ul>
 *   <li>a different state comes back: <b>fixed</b>, written into the container;
 *   <li>air comes back, or {@code canSurvive} says no: <b>flagged</b> and left exactly as it was.
 *       Removing it would be the silent loss the map forbids.
 * </ul>
 *
 * <p>Only what the edit made wrong is touched. A position that was already unsettled where it came
 * from - a fence that connected to a wall outside the schematic, a torch on a block that was never
 * saved - is left alone, because the schematic was like that before and stacking it did not do it.
 *
 * <p>The level {@code updateShape} needs is a {@link Proxy}: block and fluid lookups are answered
 * from a container, in its own coordinates, with air outside it; default methods run as written;
 * anything else goes to the client level, or gets a zero when there is none. The tick sink
 * swallows everything. ponytail: one region at a time - a seam between two sub-regions is not
 * seen. Add a cross-region view if multi-region schematics turn out to stack across regions.
 */
public final class Seams {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();

    private Seams() {
    }

    /** A block that would not survive where it now is. {@code pos} is in the new container. */
    public record Flag(BlockPos pos, BlockState state) {
    }

    public record Result(int fixed, List<Flag> flagged) {
    }

    /**
     * Settles every position in {@code shell}, in {@code after}'s coordinates. {@code origin}
     * maps a position to where its block came from in {@code before}, or {@code null} when it
     * came from nowhere that can be compared.
     */
    public static Result settle(LitematicaBlockStateContainer before,
                                LitematicaBlockStateContainer after,
                                Iterable<BlockPos> shell,
                                Function<BlockPos, BlockPos> origin) {
        LevelReader oldView = view(before);
        LevelReader newView = view(after);
        ScheduledTickAccess ticks = sink(ScheduledTickAccess.class);
        RandomSource random = RandomSource.create(0);

        int fixed = 0;
        List<Flag> flagged = new ArrayList<>();

        for (BlockPos pos : shell) {
            BlockState state = after.get(pos.getX(), pos.getY(), pos.getZ());

            if (state.isAir()) {
                continue;
            }

            BlockPos from = origin.apply(pos);

            if (from != null && get(before, from) == state) {
                BlockState there = shape(oldView, ticks, random, from, state);

                if (there != state || !survives(oldView, from, there)) {
                    continue;                     // unsettled before the edit, not ours to fix
                }
            }

            BlockState here = shape(newView, ticks, random, pos, state);

            if (!survives(newView, pos, here)) {
                flagged.add(new Flag(pos, state));
            } else if (here != state) {
                after.set(pos.getX(), pos.getY(), pos.getZ(), here);
                ++fixed;
            }
        }

        return new Result(fixed, flagged);
    }

    /** The state after every one of its six neighbours has been offered to it. */
    private static BlockState shape(LevelReader level, ScheduledTickAccess ticks,
                                    RandomSource random, BlockPos pos, BlockState state) {
        BlockState shaped = state;

        for (Direction direction : DIRECTIONS) {
            if (shaped.isAir()) {
                break;
            }

            BlockPos neighbour = pos.relative(direction);
            shaped = shaped.updateShape(level, ticks, pos, direction, neighbour,
                    level.getBlockState(neighbour), random);
        }

        return shaped;
    }

    private static boolean survives(LevelReader level, BlockPos pos, BlockState shaped) {
        return !shaped.isAir() && shaped.canSurvive(level, pos);
    }

    static BlockState get(LitematicaBlockStateContainer container, BlockPos pos) {
        Vec3i size = container.getSize();

        return pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                || pos.getX() >= size.getX() || pos.getY() >= size.getY() || pos.getZ() >= size.getZ()
                ? AIR : container.get(pos.getX(), pos.getY(), pos.getZ());
    }

    /** A {@link LevelReader} whose blocks are a container's. */
    static LevelReader view(LitematicaBlockStateContainer container) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getBlockState" -> get(container, (BlockPos) args[0]);
            case "getFluidState" -> get(container, (BlockPos) args[0]).getFluidState();
            case "getBlockEntity" -> null;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Seams.view";
            default -> method.isDefault()
                    ? InvocationHandler.invokeDefault(proxy, method, args)
                    : delegate(method, args);
        };

        return (LevelReader) Proxy.newProxyInstance(Seams.class.getClassLoader(),
                new Class<?>[] {LevelReader.class}, handler);
    }

    /** What the real level would say: dimension height, registries, feature flags. */
    private static Object delegate(Method method, Object[] args) throws Throwable {
        Minecraft minecraft = Minecraft.getInstance();
        Object level = minecraft == null ? null : minecraft.level;

        if (level == null) {
            return zero(method.getReturnType());
        }

        try {
            return method.invoke(level, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Everything returns nothing - ticks scheduled by a shape update go nowhere. */
    @SuppressWarnings("unchecked")
    static <T> T sink(Class<T> type) {
        return (T) Proxy.newProxyInstance(Seams.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getName().equals("getBlockTicks")
                        || method.getName().equals("getFluidTicks")
                        ? sink(method.getReturnType())
                        : zero(method.getReturnType()));
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }

        if (type == int.class) {
            return 0;
        }

        if (type == long.class) {
            return 0L;
        }

        if (type == float.class) {
            return 0f;
        }

        if (type == double.class) {
            return 0d;
        }

        return null;
    }
}
