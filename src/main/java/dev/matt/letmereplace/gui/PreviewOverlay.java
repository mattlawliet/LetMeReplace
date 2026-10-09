package dev.matt.letmereplace.gui;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.List;

/**
 * Shades the boxes a live box preview has written into.
 *
 * <p>The preview is the real edit, rendered by Litematica like every other block of the
 * schematic, so on its own a previewed copy is indistinguishable from the build - asked for at
 * the keyboard 2026-10-01. A tinted volume over each copy marks it, and a move also shades the
 * hole it leaves.
 *
 * <p>Drawn from malilib's world-last hook with the same {@code RenderUtils} calls Litematica
 * draws its own selection boxes with, so there is no render code of ours to keep working across
 * versions.
 */
public final class PreviewOverlay implements IRenderer {

    private static final Color4f COPY_SIDES = new Color4f(0.45f, 0.85f, 0.95f, 0.22f);
    private static final Color4f COPY_EDGES = new Color4f(0.45f, 0.85f, 0.95f, 0.9f);
    private static final Color4f HOLE_SIDES = new Color4f(0.95f, 0.4f, 0.35f, 0.18f);
    private static final Color4f HOLE_EDGES = new Color4f(0.95f, 0.4f, 0.35f, 0.9f);

    /** Swapped whole, never mutated, so the render thread always sees a complete set. */
    private static volatile List<BlockPos[]> copies = List.of();
    private static volatile BlockPos[] hole;

    private PreviewOverlay() {
    }

    public static void register() {
        RenderEventHandler.getInstance().registerWorldLastRenderer(new PreviewOverlay());
    }

    static void show(List<BlockPos[]> boxes, BlockPos[] vacated) {
        copies = List.copyOf(boxes);
        hole = vacated;
    }

    static void clear() {
        copies = List.of();
        hole = null;
    }

    @Override
    public void onRenderWorldLast(RenderTarget target, Matrix4fc matrix, CameraRenderState camera,
                                  Frustum frustum, RenderBuffers buffers, GpuBufferSlice fog,
                                  Vector4f fogColor, ProfilerFiller profiler) {
        BlockPos[] vacated = hole;

        if (vacated != null) {
            RenderUtils.renderAreaSides(vacated[0], vacated[1], HOLE_SIDES);
            RenderUtils.renderAreaOutline(vacated[0], vacated[1], 2f, HOLE_EDGES, HOLE_EDGES, HOLE_EDGES);
        }

        for (BlockPos[] box : copies) {
            RenderUtils.renderAreaSides(box[0], box[1], COPY_SIDES);
            RenderUtils.renderAreaOutline(box[0], box[1], 2f, COPY_EDGES, COPY_EDGES, COPY_EDGES);
        }
    }
}
