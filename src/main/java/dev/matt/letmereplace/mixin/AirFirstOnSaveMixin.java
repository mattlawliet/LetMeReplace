package dev.matt.letmereplace.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.Map;

/**
 * Never writes a palette that does not start with air.
 *
 * <p>{@link PaletteReadMixin} reads such files correctly, but plain Litematica does not: it
 * shifts every id by one and the air comes back as whatever sat at id 0's neighbour. Sponge
 * conversions are the source - their palette keeps the .schem's order. Rebuilding the container
 * puts air at 0 the way Litematica's own containers always have it, so a saved or shared file
 * opens the same with or without this mod.
 */
@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class AirFirstOnSaveMixin {

    @Inject(method = "writeToFile(Ljava/nio/file/Path;Ljava/lang/String;ZZ)Z", at = @At("HEAD"))
    private void letmereplace$airFirst(Path dir, String name, boolean override, boolean downgrade,
                                       CallbackInfoReturnable<Boolean> cir) {
        Map<String, LitematicaBlockStateContainer> containers =
                ((LitematicaSchematicRegionsAccessor) this).letmereplace$containers();

        containers.replaceAll((region, old) -> {
            if (old.getPalette().getBlockState(0) == LitematicaBlockStateContainer.AIR_BLOCK_STATE) {
                return old;
            }

            Vec3i size = old.getSize();
            LitematicaBlockStateContainer fresh =
                    new LitematicaBlockStateContainer(size.getX(), size.getY(), size.getZ());

            for (int y = 0; y < size.getY(); ++y) {
                for (int z = 0; z < size.getZ(); ++z) {
                    for (int x = 0; x < size.getX(); ++x) {
                        fresh.set(x, y, z, old.get(x, y, z));
                    }
                }
            }

            return fresh;
        });
    }
}
