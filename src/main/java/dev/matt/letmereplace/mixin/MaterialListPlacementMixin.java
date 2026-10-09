package dev.matt.letmereplace.mixin;

import dev.matt.letmereplace.Recount;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * "Scheduled task added..." on every recount we start ourselves. Litematica says it each time a
 * placement's material list is recounted, which made sense when only a click on its refresh did
 * that; after every edit and every synced version it is a popup nobody asked for. Recounts started
 * by {@link Recount} are silent, Litematica's own refresh still speaks.
 */
@Mixin(value = MaterialListPlacement.class, remap = false)
public abstract class MaterialListPlacementMixin {

    @Redirect(method = "reCreateMaterialList", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/util/InfoUtils;showGuiOrInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;Ljava/lang/String;[Ljava/lang/Object;)V"))
    private void letmereplace$quietWhenOurs(Message.MessageType type, String key, Object[] args) {
        if (!Recount.running()) {
            InfoUtils.showGuiOrInGameMessage(type, key, args);
        }
    }
}
