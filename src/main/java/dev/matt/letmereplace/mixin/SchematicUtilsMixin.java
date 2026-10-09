package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.Share;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Litematica's own schematic edits - Schematic Edit mode's place, break and replace, and the
 * replace hotkey - for shared schematics. They never pass through LetMeReplace's history, so
 * without this a block broken in Litematica's edit mode stayed on one client.
 *
 * <p>Every one of them ends in one of these three private methods, which return true when a block
 * actually changed. Hooking them rather than the ten public entry points is the narrow seam;
 * hooking {@code SchematicMetadata.setModifiedSinceSaved} instead would also catch our own live
 * previews, which must never be sent.
 */
@Mixin(value = SchematicUtils.class, remap = false)
public abstract class SchematicUtilsMixin {

    @Inject(method = "setTargetedSchematicBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("RETURN"))
    private static void letmereplace$one(BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            letmereplace$editedAt(pos);
        }
    }

    @Inject(method = "setSchematicBlockStates", at = @At("RETURN"))
    private static void letmereplace$range(BlockPos pos1, BlockPos pos2, BlockState state,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            letmereplace$editedAt(pos1);
        }
    }

    @Inject(method = "replaceAllIdenticalBlocks", at = @At("RETURN"))
    private static void letmereplace$all(SchematicPlacementManager manager,
                                         SchematicPlacementManager.PlacementPart part,
                                         BlockState original, BlockState replacement,
                                         java.util.function.BiPredicate<BlockState, BlockState> test,
                                         java.util.function.BiFunction<BlockState, BlockState, BlockState> mapper,
                                         net.minecraft.world.level.Level level,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && part != null) {
            Share.litematicaEdited(part.getPlacement().getSchematic());
        }
    }

    private static void letmereplace$editedAt(BlockPos pos) {
        for (SchematicPlacementManager.PlacementPart part
                : DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(pos)) {
            if (part.getBox().contains(pos)) {
                Share.litematicaEdited(part.getPlacement().getSchematic());
            }
        }
    }
}
