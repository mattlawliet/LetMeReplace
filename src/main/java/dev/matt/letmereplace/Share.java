package dev.matt.letmereplace;

import dev.matt.letmereplace.mixin.LitematicaSchematicAccessor;
import dev.matt.letmereplace.mixin.LitematicaSchematicRegionsAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared schematics, the client half - see {@code .wayfinder/shared/map.md}.
 *
 * <p>The sync boundary is a folder: only schematics loaded from
 * {@code schematics/lmr_sync/<server>/} are ever sent or overwritten, and each one's share id is
 * in its file name ({@code farm #1a2b3c4d.litematic}), so nothing has to remember which file is
 * which share. A schematic loaded from anywhere else is never touched, whatever it is called.
 *
 * <p>A whole {@code .litematic} goes over the wire per edit (ticket 001), in 30,000-byte chunks
 * because Paper caps what a client may send at 32,767 bytes (ticket 002).
 */
public final class Share {

    record Chunk(byte[] data) implements CustomPacketPayload {
        static final Type<Chunk> TYPE = new Type<>(Identifier.fromNamespaceAndPath("letmereplace", "share"));
        static final StreamCodec<FriendlyByteBuf, Chunk> CODEC = CustomPacketPayload.codec(
                (chunk, buf) -> buf.writeBytes(chunk.data),
                buf -> {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    return new Chunk(bytes);
                });

        @Override
        public Type<Chunk> type() {
            return TYPE;
        }
    }

    /** Must match the plugin's. */
    static final int PROTOCOL = 1;
    private static final int CHUNK = 30_000;

    private static final byte SHARE = 1;
    private static final byte EDIT = 2;
    private static final byte LEAVE = 3;
    private static final byte END = 4;
    private static final byte HELLO = 10;
    private static final byte FILE = 11;
    private static final byte ENDED = 12;
    private static final byte PLACE = 7;
    private static final byte PLACED = 13;
    private static final byte MEMBERS_GET = 8;
    private static final byte MEMBERS_SET = 9;
    private static final byte MEMBERS = 14;
    private static final byte REMOVED = 15;

    private static final Pattern ID = Pattern.compile(" #([0-9a-f]{8})\\.litematic$");

    private static boolean available;
    private static int nextTransfer;
    private static final Map<Integer, byte[][]> INCOMING = new HashMap<>();
    /** Share id to owner name, from every FILE and every share made here. */
    private static final Map<String, String> OWNERS = new HashMap<>();

    private Share() {
    }

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Chunk.TYPE, Chunk.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (chunk, context) -> receiveChunk(chunk.data));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    sendLitematicaEdits();
                    watchPlacements();
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            available = false;
            INCOMING.clear();
        });
    }

    /** True once this server's plugin has said hello with our protocol. Gates the share button. */
    public static boolean available() {
        return available;
    }

    // ------------------------------------------------------------------ the boundary

    /** {@code schematics/lmr_sync/<server address>/}, or null when not on a server. */
    public static Path folder() {
        ServerData server = Minecraft.getInstance().getCurrentServer();

        if (server == null) {
            return null;
        }

        return SaveOp.directory().resolve("lmr_sync").resolve(server.ip.replaceAll("[^A-Za-z0-9.-]", "_"));
    }

    /** The share id of a schematic loaded from this server's sync folder, or null. */
    public static String idOf(LitematicaSchematic schematic) {
        Path file = schematic == null ? null : schematic.getFile();
        Path folder = folder();

        if (file == null || folder == null
                || !folder.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize().getParent())) {
            return null;
        }

        Matcher m = ID.matcher(file.getFileName().toString());
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ sharing

    /**
     * Shares a schematic with these players. The schematic is copied into the sync folder and the
     * loaded one is switched to the copy - same object, so its placements follow - which leaves
     * the original file exactly as it was (map, "Sharing copies").
     */
    public static boolean share(LitematicaSchematic schematic, SchematicPlacement placement, List<String> players) {
        Path folder = folder();

        if (!available || folder == null || placement == null) {
            return false;
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = baseName(schematic);
        byte[] file = writeInto(schematic, folder, name + " #" + id);

        if (file == null) {
            return false;
        }

        OWNERS.put(id, Minecraft.getInstance().getUser().getName());
        send(out -> {
            out.writeByte(SHARE);
            out.writeUTF(id);
            out.writeUTF(name);
            writePlacement(out, placement);
            out.writeInt(players.size());

            for (String player : players) {
                out.writeUTF(player);
            }

            out.write(file);
        });
        return true;
    }

    /** The schematic's name as the share screens show it. */
    public static String displayName(LitematicaSchematic schematic) {
        return baseName(schematic);
    }

    /** The name a share shows: the file name without folder, extension or any old share id. */
    private static String baseName(LitematicaSchematic schematic) {
        Path file = schematic.getFile();
        String name = file != null ? file.getFileName().toString() : schematic.getMetadata().getName();
        return name.replaceAll("\\.litematic$", "").replaceAll(" #[0-9a-f]{8}$", "");
    }

    /**
     * Writes the schematic as {@code folder/fileName.litematic}, points it at that file, and
     * returns the bytes - the file on disk is always the newest version, and its bytes are what
     * goes over the wire.
     */
    private static byte[] writeInto(LitematicaSchematic schematic, Path folder, String fileName) {
        try {
            Files.createDirectories(folder);

            if (!schematic.writeToFile(folder, fileName, true)) {
                return null;
            }

            Path written = folder.resolve(fileName + ".litematic");
            ((LitematicaSchematicAccessor) schematic).letmereplace$setFile(written);
            schematic.getMetadata().clearModifiedSinceSaved();
            FileGeometry.forget(schematic);
            return Files.readAllBytes(written);
        } catch (IOException e) {
            LetMeReplace.LOGGER.error("could not write shared schematic", e);
            return null;
        }
    }

    /** Called after every committed change, undo and redo. Sends it if the schematic is shared. */
    static void changed(LitematicaSchematic schematic, String label) {
        String id = idOf(schematic);

        if (!available || id == null) {
            return;
        }

        Path file = schematic.getFile();
        byte[] bytes = writeInto(schematic, file.getParent(), file.getFileName().toString().replaceAll("\\.litematic$", ""));

        if (bytes == null) {
            return;
        }

        // The file is now the newest version, so nothing is pending a save.
        Pending.clear(schematic);
        send(out -> {
            out.writeByte(EDIT);
            out.writeUTF(id);
            out.writeUTF(label == null ? "" : label);
            out.write(bytes);
        });
    }

    // ------------------------------------------------------------------ Litematica's own edits

    /**
     * Ticks of quiet before Litematica's edits go out. Schematic Edit mode is click by click, and
     * a whole file per click would make a version per block; half a second of quiet sends a run of
     * clicks as one version. ponytail: a fixed wait, make it a setting if half a second is wrong.
     */
    private static final int QUIET_TICKS = 10;
    private static final java.util.Set<LitematicaSchematic> EDITED_BY_LITEMATICA =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static int quiet;

    /** Called by {@code SchematicUtilsMixin} when Litematica changed blocks in this schematic. */
    public static void litematicaEdited(LitematicaSchematic schematic) {
        if (available && idOf(schematic) != null) {
            EDITED_BY_LITEMATICA.add(schematic);
            quiet = 0;
        }
    }

    private static void sendLitematicaEdits() {
        if (EDITED_BY_LITEMATICA.isEmpty() || ++quiet < QUIET_TICKS) {
            return;
        }

        for (LitematicaSchematic schematic : EDITED_BY_LITEMATICA) {
            changed(schematic, "Litematica edit");
            // Not in LetMeReplace's own history, so the hotkeys would otherwise point elsewhere.
            UndoLog.markEdited(schematic);
        }

        EDITED_BY_LITEMATICA.clear();
    }

    // ------------------------------------------------------------------ the shared placement

    record Placed(BlockPos origin, Rotation rotation, Mirror mirror) {
        static Placed of(SchematicPlacement placement) {
            return new Placed(placement.getOrigin(), placement.getRotation(), placement.getMirror());
        }
    }

    /** Per share: where the group last agreed the placement is. */
    private static final Map<String, Placed> AGREED = new HashMap<>();
    /** Per share: a move seen here, and how many ticks it has held still. */
    private static final Map<String, Placed> MOVING = new HashMap<>();
    private static final Map<String, Integer> STILL = new HashMap<>();

    /** The one placement that syncs: the first of the schematic's (map, round 2). */
    private static SchematicPlacement sharedPlacement(LitematicaSchematic schematic) {
        List<SchematicPlacement> placements =
                DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic);
        return placements.isEmpty() ? null : placements.get(0);
    }

    /**
     * Polls instead of hooking Litematica's setters: moving with the tool, the GUI's fields and
     * rotate hotkeys all end up in the placement, and a dragged move would fire a setter per
     * block. Sent once it has held still for {@value #QUIET_TICKS} ticks, so a drag goes as one.
     */
    private static void watchPlacements() {
        if (!available) {
            return;
        }

        for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics()) {
            String id = idOf(schematic);
            SchematicPlacement placement = id == null ? null : sharedPlacement(schematic);

            if (placement == null) {
                continue;
            }

            Placed now = Placed.of(placement);
            Placed agreed = AGREED.putIfAbsent(id, now);

            if (agreed == null || now.equals(agreed)) {
                MOVING.remove(id);
                continue;
            }

            if (!now.equals(MOVING.put(id, now))) {
                STILL.put(id, 0);
                continue;
            }

            if (STILL.merge(id, 1, Integer::sum) >= QUIET_TICKS) {
                AGREED.put(id, now);
                MOVING.remove(id);
                send(out -> {
                    out.writeByte(PLACE);
                    out.writeUTF(id);
                    writePlacement(out, placement);
                });
            }
        }
    }

    private static void movePlacement(String id, String author, BlockPos origin, Rotation rotation,
                                      Mirror mirror, String dimension) {
        for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics()) {
            SchematicPlacement placement = id.equals(idOf(schematic)) ? sharedPlacement(schematic) : null;

            if (placement == null || !dimension.equals(here())) {
                continue;
            }

            // Agreed first, so the watcher sees the move as the group's and does not send it back.
            AGREED.put(id, new Placed(origin, rotation, mirror));
            MOVING.remove(id);
            placement.setOrigin(origin, null);
            placement.setRotation(rotation, null);
            placement.setMirror(mirror, null);

            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendOverlayMessage(Component.literal(author + ": moved the placement"));
            }

            return;
        }
    }

    private static String here() {
        return Minecraft.getInstance().level == null ? ""
                : Minecraft.getInstance().level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------ undo

    private static final byte UNDO = 5;
    private static final byte REDO = 6;

    /** Asks the server to undo or redo the group's newest edit. The result arrives as a FILE. */
    static boolean step(LitematicaSchematic schematic, boolean undo) {
        String id = idOf(schematic);

        if (!available || id == null) {
            return false;
        }

        send(out -> {
            out.writeByte(undo ? UNDO : REDO);
            out.writeUTF(id);
        });
        return true;
    }

    // ------------------------------------------------------------------ members

    /** Per share: the member list the server last sent, name to state. Owner only. */
    private static final Map<String, Map<String, String>> MEMBER_LISTS = new HashMap<>();

    /** Asks for the member list; it lands in {@link #members} a moment later. */
    public static void requestMembers(LitematicaSchematic schematic) {
        String id = idOf(schematic);

        if (available && id != null) {
            MEMBER_LISTS.remove(id);
            send(out -> {
                out.writeByte(MEMBERS_GET);
                out.writeUTF(id);
            });
        }
    }

    /** Name to state (accepted, invited, ignored, left), or null until the server answers. */
    public static Map<String, String> members(LitematicaSchematic schematic) {
        String id = idOf(schematic);
        return id == null ? null : MEMBER_LISTS.get(id);
    }

    /** The owner's edit: these names are in, every other member is out. */
    public static void setMembers(LitematicaSchematic schematic, List<String> names) {
        String id = idOf(schematic);

        if (!available || id == null) {
            return;
        }

        send(out -> {
            out.writeByte(MEMBERS_SET);
            out.writeUTF(id);
            out.writeInt(names.size());

            for (String name : names) {
                out.writeUTF(name);
            }
        });
    }

    // ------------------------------------------------------------------ stopping

    /** Whether this client shared it, so it may end it for everyone. */
    public static boolean ownedHere(LitematicaSchematic schematic) {
        String id = idOf(schematic);
        return id != null && Minecraft.getInstance().getUser().getName().equals(OWNERS.get(id));
    }

    /** A member stops syncing and keeps the schematic as an ordinary one. */
    public static Path leave(LitematicaSchematic schematic) {
        String id = idOf(schematic);

        if (id == null) {
            return null;
        }

        send(out -> {
            out.writeByte(LEAVE);
            out.writeUTF(id);
        });
        return detach(id);
    }

    /** The owner ends the share; everyone, the owner included, keeps an ordinary copy. */
    public static Path end(LitematicaSchematic schematic) {
        String id = idOf(schematic);

        if (id == null) {
            return null;
        }

        send(out -> {
            out.writeByte(END);
            out.writeUTF(id);
        });
        return detach(id);
    }

    /**
     * Takes a share out of the sync folder: into {@code schematics/} under its own name, so it is
     * an ordinary schematic again and nothing will send or overwrite it. Loaded, it is rewritten
     * from memory and its placements follow; not loaded, the file is moved. Returns where it went.
     */
    private static Path detach(String id) {
        Path folder = folder();

        if (folder == null || !Files.isDirectory(folder)) {
            return null;
        }

        try (var files = Files.list(folder)) {
            Path synced = files.filter(f -> f.getFileName().toString().endsWith(" #" + id + ".litematic"))
                    .findFirst().orElse(null);

            if (synced == null) {
                return null;
            }

            String name = synced.getFileName().toString().replaceAll(" #[0-9a-f]{8}\\.litematic$", "");
            String free = name;

            for (int n = 2; SaveOp.exists(free); ++n) {
                free = name + " (" + n + ")";
            }

            Path target = SaveOp.fileFor(free);
            LitematicaSchematic live = null;

            for (LitematicaSchematic s : SchematicHolder.getInstance().getAllSchematics()) {
                if (id.equals(idOf(s))) {
                    live = s;
                    break;
                }
            }

            if (live != null) {
                if (!live.writeToFile(SaveOp.directory(), free, false)) {
                    return null;
                }

                ((LitematicaSchematicAccessor) live).letmereplace$setFile(target);
                live.getMetadata().clearModifiedSinceSaved();
                Files.deleteIfExists(synced);
            } else {
                Files.move(synced, target);
            }

            OWNERS.remove(id);
            return target;
        } catch (IOException e) {
            LetMeReplace.LOGGER.error("could not take share " + id + " out of the sync folder", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ receiving

    private static void receiveChunk(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int transfer = in.readInt();
            int index = in.readInt();
            int total = in.readInt();
            byte[][] parts = INCOMING.computeIfAbsent(transfer, k -> new byte[total][]);
            parts[index] = in.readAllBytes();

            for (byte[] part : parts) {
                if (part == null) {
                    return;
                }
            }

            INCOMING.remove(transfer);
            ByteArrayOutputStream whole = new ByteArrayOutputStream();

            for (byte[] part : parts) {
                whole.write(part);
            }

            handle(new DataInputStream(new ByteArrayInputStream(whole.toByteArray())));
        } catch (IOException | RuntimeException e) {
            LetMeReplace.LOGGER.error("bad share message", e);
        }
    }

    private static void handle(DataInputStream in) throws IOException {
        byte kind = in.readByte();

        if (kind == HELLO) {
            int protocol = in.readInt();
            available = protocol == PROTOCOL;

            if (!available) {
                say("This server's LetMeReplace speaks sharing protocol " + protocol + ", yours speaks "
                        + PROTOCOL + ". Update whichever is older to share schematics here.");
            }
        } else if (kind == FILE && available) {
            String id = in.readUTF();
            String name = in.readUTF();
            OWNERS.put(id, in.readUTF());
            in.readInt();       // version - for the history screen, not used yet
            String author = in.readUTF();
            String label = in.readUTF();
            BlockPos origin = new BlockPos(in.readInt(), in.readInt(), in.readInt());
            Rotation rotation = Rotation.valueOf(in.readUTF());
            Mirror mirror = Mirror.valueOf(in.readUTF());
            String dimension = in.readUTF();
            byte[] file = in.readAllBytes();
            receiveFile(id, name, author, label, origin, rotation, mirror, dimension, file);
        } else if (kind == MEMBERS) {
            String id = in.readUTF();
            int count = in.readInt();
            Map<String, String> members = new LinkedHashMap<>();

            for (int i = 0; i < count; i++) {
                members.put(in.readUTF(), in.readUTF());
            }

            MEMBER_LISTS.put(id, members);
        } else if (kind == REMOVED) {
            String id = in.readUTF();
            String owner = in.readUTF();
            Path kept = detach(id);

            if (kept != null) {
                say(owner + " took you off the share. Your copy is now an ordinary schematic: "
                        + kept.getFileName());
            }
        } else if (kind == PLACED && available) {
            String id = in.readUTF();
            String author = in.readUTF();
            BlockPos origin = new BlockPos(in.readInt(), in.readInt(), in.readInt());
            movePlacement(id, author, origin, Rotation.valueOf(in.readUTF()), Mirror.valueOf(in.readUTF()),
                    in.readUTF());
        } else if (kind == ENDED) {
            String id = in.readUTF();
            String owner = in.readUTF();
            Path kept = detach(id);

            if (kept != null) {
                say(owner + " ended the share. Your copy is now an ordinary schematic: "
                        + kept.getFileName());
            }
        }
    }

    private static void receiveFile(String id, String name, String author, String label, BlockPos origin,
                                    Rotation rotation, Mirror mirror, String dimension, byte[] file)
            throws IOException {
        Path folder = folder();

        if (folder == null) {
            return;
        }

        LitematicaSchematic live = null;

        for (LitematicaSchematic s : SchematicHolder.getInstance().getAllSchematics()) {
            if (id.equals(idOf(s))) {
                live = s;
                break;
            }
        }

        Files.createDirectories(folder);
        Path path = folder.resolve(name.replaceAll("[\\\\/:*?\"<>|]", "_") + " #" + id + ".litematic");
        Files.write(path, file);

        if (live != null) {
            applyInto(live, path);
            UndoLog.markEdited(live);

            // Catches a move made while this client was away. Not over a move of our own that is
            // still being made - that one is about to be sent and wins.
            SchematicPlacement placement = sharedPlacement(live);
            Placed theirs = new Placed(origin, rotation, mirror);

            if (placement != null && !MOVING.containsKey(id) && !theirs.equals(Placed.of(placement))
                    && dimension.equals(here())) {
                AGREED.put(id, theirs);
                placement.setOrigin(origin, null);
                placement.setRotation(rotation, null);
                placement.setMirror(mirror, null);
            }

            // The action bar, never chat: a burst of edits replaces itself instead of stacking.
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendOverlayMessage(Component.literal(author + ": " + label));
            }
            return;
        }

        // First time on this client, or the first since rejoining: load it and put it where the
        // owner has it.
        LitematicaSchematic loaded = LitematicaSchematic.createFromFile(folder, path.getFileName().toString());

        if (loaded == null) {
            say("Could not read the shared schematic " + name + ".");
            return;
        }

        SchematicHolder.getInstance().addSchematic(loaded, true);
        UndoLog.markEdited(loaded);
        String here = Minecraft.getInstance().level == null ? ""
                : Minecraft.getInstance().level.dimension().identifier().toString();

        if (!dimension.equals(here)) {
            say("Synced " + name + ". Its placement is in " + dimension + ", so it shows when you go there.");
            return;
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement placement = SchematicPlacement.createFor(loaded, origin, name, true, true);

        if (placement != null) {
            placement.setRotation(rotation, null);
            placement.setMirror(mirror, null);
            manager.addSchematicPlacement(placement, false);
            AGREED.put(id, Placed.of(placement));
        }

        say("Synced " + name + ". Edits from the group now arrive automatically.");
    }

    /** Swaps the incoming version's regions into the loaded schematic, so its placements follow. */
    private static void applyInto(LitematicaSchematic live, Path file) {
        LitematicaSchematic incoming = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());

        if (incoming == null) {
            return;
        }

        LitematicaSchematicRegionsAccessor from = (LitematicaSchematicRegionsAccessor) incoming;
        LitematicaSchematicRegionsAccessor to = (LitematicaSchematicRegionsAccessor) live;
        Map<String, UndoLog.Region> regions = new LinkedHashMap<>();

        for (String region : from.letmereplace$containers().keySet()) {
            regions.put(region, UndoLog.Region.take(from, region));
        }

        for (String region : to.letmereplace$containers().keySet()) {
            regions.putIfAbsent(region, new UndoLog.Region(null, null, null, null, null, null, null));
        }

        UndoLog.swapRegions(live, regions);
        // ponytail: local history indexes the old containers. Undo becomes the server's in the
        // next step (map, round 1); until then an incoming edit ends local undo.
        UndoLog.forget(live);
        Pending.clear(live);
        live.getMetadata().clearModifiedSinceSaved();
        Schematics.refreshCounts(live);
        Schematics.refreshRender(live);
        History.refreshLists(live);
    }

    // ------------------------------------------------------------------ wire

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static void send(Writer writer) {
        try {
            ByteArrayOutputStream whole = new ByteArrayOutputStream();
            writer.write(new DataOutputStream(whole));
            byte[] message = whole.toByteArray();
            int total = Math.max(1, (message.length + CHUNK - 1) / CHUNK);
            int transfer = nextTransfer++;

            for (int i = 0; i < total; i++) {
                ByteArrayOutputStream chunk = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(chunk);
                out.writeInt(transfer);
                out.writeInt(i);
                out.writeInt(total);
                int from = i * CHUNK;
                out.write(message, from, Math.min(CHUNK, message.length - from));
                ClientPlayNetworking.send(new Chunk(chunk.toByteArray()));
            }
        } catch (IOException e) {
            LetMeReplace.LOGGER.error("could not send share message", e);
        }
    }

    private static void writePlacement(DataOutputStream out, SchematicPlacement placement) throws IOException {
        BlockPos origin = placement.getOrigin();
        out.writeInt(origin.getX());
        out.writeInt(origin.getY());
        out.writeInt(origin.getZ());
        out.writeUTF(placement.getRotation().name());
        out.writeUTF(placement.getMirror().name());
        out.writeUTF(Minecraft.getInstance().level == null ? ""
                : Minecraft.getInstance().level.dimension().identifier().toString());
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }
}
