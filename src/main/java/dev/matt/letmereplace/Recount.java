package dev.matt.letmereplace;

import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Recounts a material list once Litematica has finished rebuilding the placements.
 *
 * <p>A placement's material list does not count the schematic: it counts the blocks Litematica
 * has put into its schematic world, chunk by chunk. Every change here ends by marking the
 * placements for rebuild, and that rebuild runs on Litematica's placement daemon threads - so a
 * recount started straight away counted whichever chunks happened to be rebuilt already. Seen
 * 2026-10-07 on the 82k sheep farm: 578 leaves placed, the list said 1 on one client and 155 on
 * the other, and pressing refresh a moment later gave 578 on both.
 *
 * <p>ponytail: "rebuilt" is the daemon having had no tasks for {@value #QUIET_TICKS} ticks in a row.
 * The manager hands its queue to the daemon on its own tick, so one empty look can come before the
 * work arrives. Watch a per-chunk signal instead if quiet ever comes too early.
 */
public final class Recount {

    private static final int QUIET_TICKS = 3;

    private static final Set<MaterialListBase> WAITING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int quiet;
    private static boolean running;

    private Recount() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** Recount this list once the rebuild a change just started is done. */
    public static void later(MaterialListBase list) {
        if (list != null) {
            WAITING.add(list);
            quiet = 0;
        }
    }

    /** True while a recount of ours is starting - Litematica's "task added" popup stays quiet. */
    public static boolean running() {
        return running;
    }

    private static void tick() {
        if (WAITING.isEmpty()) {
            return;
        }

        if (PlacementManagerDaemonHandler.INSTANCE.hasAnyTasks()) {
            quiet = 0;
            return;
        }

        if (++quiet < QUIET_TICKS) {
            return;
        }

        running = true;

        try {
            for (MaterialListBase list : WAITING) {
                list.reCreateMaterialList();
            }
        } finally {
            running = false;
        }

        WAITING.clear();
    }
}
