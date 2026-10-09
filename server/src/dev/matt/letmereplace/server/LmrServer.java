package dev.matt.letmereplace.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The server half of shared schematics - see {@code .wayfinder/shared/map.md}.
 *
 * <p>The server is the single authority on each share's current version. A client sends a whole
 * {@code .litematic} per edit; the server stores it as the next version and hands it to every
 * other member who accepted and is online.
 *
 * <p>ponytail: no version check yet, so two edits at the same moment are last-write-wins. Ticket
 * 001's refusal and re-run of gestures comes next; until then the window is the ~100 ms one
 * measured in ticket 006.
 */
public final class LmrServer extends JavaPlugin implements Listener, PluginMessageListener {

    static final String CHANNEL = "letmereplace:share";
    /** Bumped whenever a message changes shape. The client refuses a server that differs. */
    static final int PROTOCOL = 1;
    static final int HISTORY = 100;
    static final int CHUNK = 30_000;

    // Client -> server
    static final byte SHARE = 1;
    static final byte EDIT = 2;
    static final byte LEAVE = 3;
    static final byte END = 4;
    static final byte UNDO = 5;
    static final byte REDO = 6;
    static final byte PLACE = 7;
    static final byte MEMBERS_GET = 8;
    static final byte MEMBERS_SET = 9;
    // Server -> client
    static final byte HELLO = 10;
    static final byte FILE = 11;
    static final byte ENDED = 12;
    static final byte PLACED = 13;
    static final byte MEMBERS = 14;
    static final byte REMOVED = 15;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Share> shares = new LinkedHashMap<>();
    private final Map<String, byte[][]> incoming = new HashMap<>();
    private int nextTransfer;

    /** One share: everything in its share.json. The versions themselves are files beside it. */
    static final class Share {
        String id;
        String name;
        UUID owner;
        String ownerName;
        Map<UUID, Member> members = new LinkedHashMap<>();
        Placement placement;
        /** The current version - what everyone has. Undo moves it down, redo back up. */
        int version;
        /** The newest version stored. Above {@link #version} is what redo can bring back. */
        int top;
        List<Entry> history = new ArrayList<>();
        /** The owner ended it. Kept only until every member who synced it has been told. */
        boolean ended;
    }

    static final class Member {
        String name;
        // invited, accepted, ignored, left, told (an ended share reached them),
        // removed (by the owner, still to be told), gone (removed and told, or never synced)
        String state;

        Member(String name, String state) {
            this.name = name;
            this.state = state;
        }
    }

    static final class Placement {
        int x, y, z;
        String rotation, mirror, dimension;
    }

    record Entry(int version, String author, String label, long time) {
    }

    @Override
    public void onEnable() {
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        registerCommand("lmr", "LetMeReplace shared schematics", new Commands());
        load();
    }

    private Path sharesDir() {
        return getDataFolder().toPath().resolve("shares");
    }

    private void load() {
        try (Stream<Path> dirs = Files.isDirectory(sharesDir()) ? Files.list(sharesDir()) : Stream.empty()) {
            for (Path dir : dirs.toList()) {
                Path json = dir.resolve("share.json");

                if (Files.exists(json)) {
                    Share share = gson.fromJson(Files.readString(json), Share.class);
                    shares.put(share.id, share);
                }
            }
        } catch (IOException e) {
            getLogger().severe("could not read shares: " + e);
        }

        getLogger().info(shares.size() + " shares loaded");
    }

    // ------------------------------------------------------------------ joining

    /**
     * Not PlayerJoinEvent: Paper drops messages on channels the client has not registered yet,
     * and the client registers after joining (ticket 002).
     */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        if (!CHANNEL.equals(event.getChannel())) {
            return;
        }

        Player player = event.getPlayer();
        send(player, out -> {
            out.writeByte(HELLO);
            out.writeInt(PROTOCOL);
        });

        // Accepting is remembered: rejoining resumes silently with the newest version.
        for (Share share : new ArrayList<>(shares.values())) {   // settle() may remove one
            Member member = share.members.get(player.getUniqueId());

            if (member == null) {
                continue;
            }

            member.name = player.getName();

            if (member.state.equals("removed")) {
                tellRemoved(player, share);
            } else if (share.ended) {
                if (member.state.equals("accepted")) {
                    tellEnded(player, share);
                    settle(share);
                }
            } else {
                switch (member.state) {
                    case "accepted" -> sendFile(player, share, null);
                    case "invited" -> invite(player, share);
                    default -> { }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + "/";
        incoming.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void invite(Player player, Share share) {
        Component line = Component.text(share.ownerName + " shared ", NamedTextColor.GRAY)
                .append(Component.text(share.name, NamedTextColor.WHITE))
                .append(Component.text(" with you  ", NamedTextColor.GRAY))
                .append(Component.text("[Sync]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/lmr accept " + share.id)))
                .append(Component.text(" "))
                .append(Component.text("[Ignore]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/lmr ignore " + share.id)));
        player.sendMessage(line);
    }

    // ------------------------------------------------------------------ receiving

    @Override
    public void onPluginMessageReceived(String channel, Player from, byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int transfer = in.readInt();
            int index = in.readInt();
            int total = in.readInt();
            String key = from.getUniqueId() + "/" + transfer;
            byte[][] parts = incoming.computeIfAbsent(key, k -> new byte[total][]);
            parts[index] = in.readAllBytes();

            for (byte[] part : parts) {
                if (part == null) {
                    return;
                }
            }

            incoming.remove(key);
            ByteArrayOutputStream whole = new ByteArrayOutputStream();

            for (byte[] part : parts) {
                whole.write(part);
            }

            handle(from, new DataInputStream(new ByteArrayInputStream(whole.toByteArray())));
        } catch (IOException | RuntimeException e) {
            getLogger().warning("bad message from " + from.getName() + ": " + e);
        }
    }

    private void handle(Player from, DataInputStream in) throws IOException {
        byte kind = in.readByte();

        if (kind == SHARE) {
            onShare(from, in);
        } else if (kind == EDIT) {
            onEdit(from, in);
        } else if (kind == LEAVE) {
            onLeave(from, in.readUTF());
        } else if (kind == END) {
            onEnd(from, in.readUTF());
        } else if (kind == MEMBERS_GET) {
            sendMembers(from, shares.get(in.readUTF()));
        } else if (kind == MEMBERS_SET) {
            String id = in.readUTF();
            int count = in.readInt();
            List<String> names = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                names.add(in.readUTF());
            }

            onMembersSet(from, id, names);
        } else if (kind == PLACE) {
            onPlace(from, in.readUTF(), readPlacement(in));
        } else if (kind == UNDO || kind == REDO) {
            onStep(from, in.readUTF(), kind == UNDO);
        }
    }

    private void onShare(Player from, DataInputStream in) throws IOException {
        if (!from.hasPermission("lmr.share")) {
            from.sendMessage(Component.text("You can't share schematics here.", NamedTextColor.RED));
            return;
        }

        Share share = new Share();
        share.id = in.readUTF();
        share.name = in.readUTF();
        share.owner = from.getUniqueId();
        share.ownerName = from.getName();
        share.placement = readPlacement(in);
        int count = in.readInt();
        List<String> names = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            names.add(in.readUTF());
        }

        byte[] file = in.readAllBytes();

        if (!share.id.matches("[0-9a-f]{8}") || shares.containsKey(share.id)) {
            from.sendMessage(Component.text("Share refused: bad or duplicate id.", NamedTextColor.RED));
            return;
        }

        share.members.put(from.getUniqueId(), new Member(from.getName(), "accepted"));
        List<String> invited = new ArrayList<>();

        shares.put(share.id, share);
        store(share, from.getName(), "Shared", file);

        for (String name : names) {
            String added = addMember(from, share, name);

            if (added != null) {
                invited.add(added);
            }
        }

        from.sendMessage(Component.text("Shared " + share.name + " with "
                + (invited.isEmpty() ? "nobody yet" : String.join(", ", invited)) + ".",
                NamedTextColor.GREEN));
        getLogger().info(from.getName() + " shared '" + share.name + "' (" + share.id + ", "
                + file.length + " bytes) with " + invited);
    }

    private void onEdit(Player from, DataInputStream in) throws IOException {
        String id = in.readUTF();
        String label = in.readUTF();
        byte[] file = in.readAllBytes();
        Share share = shares.get(id);
        Member member = share == null ? null : share.members.get(from.getUniqueId());

        if (member == null || share.ended || !member.state.equals("accepted") || !from.hasPermission("lmr.edit")) {
            return;
        }

        store(share, from.getName(), label, file);

        for (Map.Entry<UUID, Member> other : share.members.entrySet()) {
            Player player = getServer().getPlayer(other.getKey());

            if (player != null && !player.equals(from) && other.getValue().state.equals("accepted")) {
                sendFile(player, share, file);
            }
        }
    }

    /** A member stops syncing. Their copy stays on their side as an ordinary schematic. */
    private void onLeave(Player from, String id) {
        Share share = shares.get(id);
        Member member = share == null ? null : share.members.get(from.getUniqueId());

        if (member != null && !share.owner.equals(from.getUniqueId())) {
            member.state = "left";
            saveJson(share);
            tellOwner(share, from.getName() + " stopped syncing " + share.name + ".");
        }
    }

    /**
     * The owner ends the share. Everyone keeps the last version as an ordinary schematic and the
     * history is deleted (map, round 1). Members who are offline are told at their next login,
     * which is the only reason the share.json outlives its versions.
     */
    private void onEnd(Player from, String id) {
        Share share = shares.get(id);

        if (share == null || share.ended || !share.owner.equals(from.getUniqueId())) {
            return;
        }

        share.ended = true;
        share.members.get(share.owner).state = "told";
        newest.remove(share.id);

        for (Member member : share.members.values()) {
            if (member.state.equals("invited")) {
                member.state = "ignored";
            }
        }

        for (Map.Entry<UUID, Member> other : share.members.entrySet()) {
            Player player = getServer().getPlayer(other.getKey());

            if (player != null && other.getValue().state.equals("accepted")) {
                tellEnded(player, share);
            }
        }

        settle(share);
        from.sendMessage(Component.text("Ended the share of " + share.name + ". Everyone keeps their copy.",
                NamedTextColor.GRAY));
        getLogger().info(from.getName() + " ended '" + share.name + "' (" + share.id + ")");
    }

    private void tellEnded(Player player, Share share) {
        send(player, out -> {
            out.writeByte(ENDED);
            out.writeUTF(share.id);
            out.writeUTF(share.ownerName);
        });
        share.members.get(player.getUniqueId()).state = "told";
    }

    /**
     * After an ended share told someone: its versions go at once, and the share.json goes too once
     * nobody who synced it is left to tell.
     */
    private void settle(Share share) {
        boolean done = share.members.values().stream().noneMatch(m -> m.state.equals("accepted"));
        String json = gson.toJson(share);
        Path dir = sharesDir().resolve(share.id);

        if (done) {
            shares.remove(share.id);
        }

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (Stream<Path> files = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
                for (Path file : files.toList()) {
                    if (done || file.toString().endsWith(".litematic")) {
                        Files.deleteIfExists(file);
                    }
                }

                if (done) {
                    Files.deleteIfExists(dir);
                } else {
                    Files.writeString(dir.resolve("share.json"), json);
                }
            } catch (IOException e) {
                getLogger().severe("could not settle " + share.id + ": " + e);
            }
        });
    }

    private void tellOwner(Share share, String text) {
        Player owner = getServer().getPlayer(share.owner);

        if (owner != null) {
            owner.sendMessage(Component.text(text, NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ storing

    /**
     * Adds the next version. The bytes are kept in memory as the newest version so sending never
     * waits on disk; the write itself goes off the main thread, because Paper hands plugin
     * messages to us on it (ticket 006).
     */
    private void store(Share share, String author, String label, byte[] file) {
        // A new edit after an undo ends the branch redo could have brought back.
        int oldTop = Math.max(share.top, share.version);
        int head = share.version;
        share.history.removeIf(e -> e.version() > head);
        share.version++;
        share.top = share.version;
        share.history.add(new Entry(share.version, author, label, System.currentTimeMillis()));
        newest.put(share.id, file);

        int dropBelow = share.version - HISTORY;

        while (!share.history.isEmpty() && share.history.get(0).version() <= dropBelow) {
            share.history.remove(0);
        }

        String json = gson.toJson(share);
        int version = share.version;
        Path dir = sharesDir().resolve(share.id);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve(String.format("v%04d.litematic", version)), file);
                Files.writeString(dir.resolve("share.json"), json);
                Files.deleteIfExists(dir.resolve(String.format("v%04d.litematic", dropBelow)));

                for (int v = version + 1; v <= oldTop; v++) {
                    Files.deleteIfExists(dir.resolve(String.format("v%04d.litematic", v)));
                }
            } catch (IOException e) {
                getLogger().severe("could not store " + share.id + " v" + version + ": " + e);
            }
        });
    }

    private final Map<String, byte[]> newest = new HashMap<>();

    /**
     * The shared placement moved (map, round 2): not part of the undo history, newest move wins,
     * and it goes to everyone else in the group straight away.
     */
    private void onPlace(Player from, String id, Placement placement) {
        Share share = shares.get(id);
        Member member = share == null ? null : share.members.get(from.getUniqueId());

        if (member == null || share.ended || !member.state.equals("accepted") || !from.hasPermission("lmr.edit")) {
            return;
        }

        share.placement = placement;
        saveJson(share);

        for (Map.Entry<UUID, Member> other : share.members.entrySet()) {
            Player player = getServer().getPlayer(other.getKey());

            if (player != null && !player.equals(from) && other.getValue().state.equals("accepted")) {
                send(player, out -> {
                    out.writeByte(PLACED);
                    out.writeUTF(share.id);
                    out.writeUTF(from.getName());
                    writePlacement(out, placement);
                });
            }
        }
    }

    // ------------------------------------------------------------------ members

    /**
     * Invites one name: online or not, as long as the server has seen that player, so a typo can
     * never become a member. Returns the name as the server spells it, or null.
     */
    private String addMember(Player owner, Share share, String name) {
        Player online = getServer().getPlayerExact(name);
        org.bukkit.OfflinePlayer target = online != null ? online : getServer().getOfflinePlayerIfCached(name);

        if (target == null || target.getUniqueId().equals(share.owner)) {
            owner.sendMessage(Component.text("No player called " + name + " has been on this server.",
                    NamedTextColor.RED));
            return null;
        }

        Member member = share.members.get(target.getUniqueId());

        if (member != null && (member.state.equals("accepted") || member.state.equals("invited"))) {
            return null;
        }

        String spelled = target.getName() != null ? target.getName() : name;
        share.members.put(target.getUniqueId(), new Member(spelled, "invited"));

        if (online != null && online.getListeningPluginChannels().contains(CHANNEL)) {
            invite(online, share);
        } else if (online != null) {
            online.sendMessage(Component.text(share.ownerName + " shared " + share.name
                    + " with you, but you need LetMeReplace to sync it.", NamedTextColor.GRAY));
        }

        return spelled;
    }

    private void sendMembers(Player to, Share share) {
        if (share == null || share.ended || !share.owner.equals(to.getUniqueId())) {
            return;
        }

        List<Member> listed = new ArrayList<>();

        for (Map.Entry<UUID, Member> m : share.members.entrySet()) {
            if (!m.getKey().equals(share.owner) && !m.getValue().state.equals("gone")
                    && !m.getValue().state.equals("removed")) {
                listed.add(m.getValue());
            }
        }

        send(to, out -> {
            out.writeByte(MEMBERS);
            out.writeUTF(share.id);
            out.writeInt(listed.size());

            for (Member m : listed) {
                out.writeUTF(m.name);
                out.writeUTF(m.state);
            }
        });
    }

    /**
     * The owner's picker, saved: whoever is ticked is in, whoever is not is out. A removed member
     * keeps their copy as an ordinary schematic, the same as when a share ends.
     */
    private void onMembersSet(Player from, String id, List<String> names) {
        Share share = shares.get(id);

        if (share == null || share.ended || !share.owner.equals(from.getUniqueId())) {
            return;
        }

        java.util.Set<String> wanted = new java.util.HashSet<>();

        for (String name : names) {
            wanted.add(name.toLowerCase(java.util.Locale.ROOT));
        }

        List<String> removed = new ArrayList<>();

        for (Map.Entry<UUID, Member> m : share.members.entrySet()) {
            Member member = m.getValue();

            if (m.getKey().equals(share.owner) || wanted.contains(member.name.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }

            if (member.state.equals("accepted")) {
                member.state = "removed";
                removed.add(member.name);
                Player player = getServer().getPlayer(m.getKey());

                if (player != null) {
                    tellRemoved(player, share);
                }
            } else if (member.state.equals("invited")) {
                member.state = "gone";
                removed.add(member.name);
            }
        }

        List<String> added = new ArrayList<>();

        for (String name : names) {
            String spelled = addMember(from, share, name);

            if (spelled != null) {
                added.add(spelled);
            }
        }

        saveJson(share);
        sendMembers(from, share);

        if (!added.isEmpty() || !removed.isEmpty()) {
            from.sendMessage(Component.text(share.name + ": "
                    + (added.isEmpty() ? "" : "invited " + String.join(", ", added))
                    + (!added.isEmpty() && !removed.isEmpty() ? "; " : "")
                    + (removed.isEmpty() ? "" : "removed " + String.join(", ", removed)) + ".",
                    NamedTextColor.GRAY));
        }
    }

    private void tellRemoved(Player player, Share share) {
        send(player, out -> {
            out.writeByte(REMOVED);
            out.writeUTF(share.id);
            out.writeUTF(share.ownerName);
        });
        share.members.get(player.getUniqueId()).state = "gone";
        saveJson(share);
    }

    private static Entry entry(Share share, int version) {
        for (Entry e : share.history) {
            if (e.version() == version) {
                return e;
            }
        }

        return null;
    }

    /**
     * Undo or redo for the whole group (map, round 1): the newest edit is taken back whoever made
     * it, and everyone - the one who pressed it included - gets the resulting version. Version 1 is
     * the share itself and cannot be undone.
     */
    private void onStep(Player from, String id, boolean undo) {
        Share share = shares.get(id);
        Member member = share == null ? null : share.members.get(from.getUniqueId());

        if (member == null || share.ended || !member.state.equals("accepted") || !from.hasPermission("lmr.edit")) {
            return;
        }

        int to = undo ? share.version - 1 : share.version + 1;
        Entry step = entry(share, undo ? share.version : to);
        Path file = sharesDir().resolve(share.id).resolve(String.format("v%04d.litematic", to));

        if (to < 1 || to > Math.max(share.top, share.version) || step == null || !Files.exists(file)) {
            from.sendActionBar(Component.text(undo ? "Nothing to undo" : "Nothing to redo", NamedTextColor.GRAY));
            return;
        }

        byte[] bytes;

        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            getLogger().severe("could not read " + file + ": " + e);
            return;
        }

        share.top = Math.max(share.top, share.version);
        share.version = to;
        newest.put(share.id, bytes);
        saveJson(share);
        String label = (undo ? "Undo " : "Redo ") + step.label();

        for (Map.Entry<UUID, Member> other : share.members.entrySet()) {
            Player player = getServer().getPlayer(other.getKey());

            if (player != null && other.getValue().state.equals("accepted")) {
                sendFile(player, share, bytes, from.getName(), label);
            }
        }
    }

    private byte[] newestFile(Share share) throws IOException {
        byte[] file = newest.get(share.id);

        if (file == null) {
            file = Files.readAllBytes(sharesDir().resolve(share.id)
                    .resolve(String.format("v%04d.litematic", share.version)));
            newest.put(share.id, file);
        }

        return file;
    }

    private void saveJson(Share share) {
        String json = gson.toJson(share);
        Path file = sharesDir().resolve(share.id).resolve("share.json");

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.writeString(file, json);
            } catch (IOException e) {
                getLogger().severe("could not save " + file + ": " + e);
            }
        });
    }

    // ------------------------------------------------------------------ sending

    private void sendFile(Player player, Share share, byte[] file) {
        Entry head = entry(share, share.version);
        sendFile(player, share, file, head == null ? "" : head.author(), head == null ? "" : head.label());
    }

    private void sendFile(Player player, Share share, byte[] file, String author, String label) {
        try {
            byte[] bytes = file != null ? file : newestFile(share);

            send(player, out -> {
                out.writeByte(FILE);
                out.writeUTF(share.id);
                out.writeUTF(share.name);
                out.writeUTF(share.ownerName);
                out.writeInt(share.version);
                out.writeUTF(author);
                out.writeUTF(label);
                writePlacement(out, share.placement);
                out.write(bytes);
            });
        } catch (IOException e) {
            getLogger().severe("could not send " + share.id + " to " + player.getName() + ": " + e);
        }
    }

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    /** Every message goes through the same chunker: 32 KB is the cap towards the server. */
    private void send(Player player, Writer writer) {
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
                player.sendPluginMessage(this, CHANNEL, chunk.toByteArray());
            }
        } catch (IOException e) {
            getLogger().severe("could not send to " + player.getName() + ": " + e);
        }
    }

    static Placement readPlacement(DataInputStream in) throws IOException {
        Placement p = new Placement();
        p.x = in.readInt();
        p.y = in.readInt();
        p.z = in.readInt();
        p.rotation = in.readUTF();
        p.mirror = in.readUTF();
        p.dimension = in.readUTF();
        return p;
    }

    static void writePlacement(DataOutputStream out, Placement p) throws IOException {
        out.writeInt(p.x);
        out.writeInt(p.y);
        out.writeInt(p.z);
        out.writeUTF(p.rotation);
        out.writeUTF(p.mirror);
        out.writeUTF(p.dimension);
    }

    // ------------------------------------------------------------------ /lmr

    private final class Commands implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Players only.");
                return;
            }

            String sub = args.length > 0 ? args[0] : "";
            Share share = args.length > 1 ? shares.get(args[1]) : null;
            Member member = share == null ? null : share.members.get(player.getUniqueId());

            switch (sub) {
                case "accept" -> {
                    if (member == null || share.ended || !player.hasPermission("lmr.sync")) {
                        player.sendMessage(Component.text("No such share for you.", NamedTextColor.RED));
                        return;
                    }

                    member.state = "accepted";
                    saveJson(share);
                    sendFile(player, share, null);
                }
                case "ignore" -> {
                    if (member != null && !share.owner.equals(player.getUniqueId())) {
                        member.state = "ignored";
                        saveJson(share);
                        player.sendMessage(Component.text("Ignored " + share.name + ".", NamedTextColor.GRAY));
                    }
                }
                default -> player.sendMessage(Component.text(
                        "Share from the material list: shift-click the share button.", NamedTextColor.GRAY));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return args.length <= 1 ? List.of("accept", "ignore") : List.of();
        }
    }
}
