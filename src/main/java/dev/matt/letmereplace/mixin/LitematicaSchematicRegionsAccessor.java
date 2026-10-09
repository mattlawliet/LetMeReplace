package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * The seven maps a sub-region actually lives in.
 *
 * <p>Litematica exposes each region's <em>contents</em> - container, block entities, ticks,
 * entities - and its size and position, but only ever one region at a time and never the maps
 * themselves. That is enough to edit blocks in place, which is all the mod needed until
 * {@link dev.matt.letmereplace.Bounds}: resizing a region means putting a <em>different</em>
 * container under the same name, and dropping an emptied one means taking it out of all seven.
 *
 * <p>All seven are {@code private final HashMap}s built in the constructor, so no {@code @Mutable}
 * is needed - the reference never changes, only what is in it.
 */
@Mixin(LitematicaSchematic.class)
public interface LitematicaSchematicRegionsAccessor {

    @Accessor("blockContainers")
    Map<String, LitematicaBlockStateContainer> letmereplace$containers();

    @Accessor("tileEntities")
    Map<String, Map<BlockPos, CompoundData>> letmereplace$blockEntities();

    @Accessor("pendingBlockTicks")
    Map<String, Map<BlockPos, ScheduledTick<Block>>> letmereplace$blockTicks();

    @Accessor("pendingFluidTicks")
    Map<String, Map<BlockPos, ScheduledTick<Fluid>>> letmereplace$fluidTicks();

    @Accessor("entities")
    Map<String, List<LitematicaSchematic.EntityInfo>> letmereplace$entities();

    @Accessor("subRegionPositions")
    Map<String, BlockPos> letmereplace$positions();

    @Accessor("subRegionSizes")
    Map<String, BlockPos> letmereplace$sizes();
}
