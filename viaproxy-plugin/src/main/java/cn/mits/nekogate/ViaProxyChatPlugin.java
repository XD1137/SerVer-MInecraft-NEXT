package cn.mits.nekogate;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.VersionedPacketTransformer;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v26_2to26_3.packet.ClientboundPackets26_3;
import io.netty.channel.Channel;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViaProxyChatPlugin extends ViaProxyPlugin {
    private static final int DEFAULT_PORT = 25626;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<String> rejectedSessions = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile VersionedPacketTransformer<ClientboundPackets26_3, ?> chatTransformer;
    private volatile Connection database;
    private final Object databaseLock = new Object();

    @Override
    public void onEnable() {
        openDatabase();
        int port = readPort();
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            this.serverSocket = server;
            this.running.set(true);
            Thread control = new Thread(this::acceptLoop, "nekogate-control");
            control.setDaemon(true);
            control.start();
            Thread bans = new Thread(this::banEnforcementLoop, "nekogate-ban-enforcement");
            bans.setDaemon(true);
            bans.start();
            System.out.println("[NekoGate] Control listening on 127.0.0.1:" + port);
        } catch (IOException e) {
            closeDatabase();
            throw new IllegalStateException("Could not bind NekoGate control port " + port, e);
        }
    }

    @Override
    public void onDisable() {
        this.running.set(false);
        ServerSocket server = this.serverSocket;
        if (server != null) try { server.close(); } catch (IOException ignored) { }
        closeDatabase();
    }

    private void openDatabase() {
        try {
            // ViaProxy loads plugins with its own class loader. Explicitly initialize
            // the bundled JDBC driver instead of relying on DriverManager's
            // ServiceLoader discovery, which may not see plugin-local services.
            Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
            Path directory = Path.of("plugins", "NekoGate");
            Files.createDirectories(directory);
            String databaseUrl = "jdbc:sqlite:" + directory.resolve("bans.sqlite3");
            this.database = DriverManager.getConnection(databaseUrl);
            try (Statement statement = this.database.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT,
                        player_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                        reason TEXT NOT NULL,
                        banned_by TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_uuid ON bans(uuid)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize NekoGate SQLite ban database", e);
        }
    }

    private void closeDatabase() {
        synchronized (databaseLock) {
            Connection db = this.database;
            this.database = null;
            if (db != null) try { db.close(); } catch (SQLException ignored) { }
        }
    }

    private static int readPort() {
        String value = System.getenv("NEKOGATE_CONTROL_PORT");
        if (value == null || value.isBlank()) return DEFAULT_PORT;
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException("out of range");
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid NEKOGATE_CONTROL_PORT: " + value, e);
        }
    }

    private void acceptLoop() {
        while (this.running.get()) {
            try {
                Socket socket = this.serverSocket.accept();
                socket.setSoTimeout(5000);
                handle(socket);
            } catch (IOException e) {
                if (this.running.get()) System.err.println("[NekoGate] Control socket error: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null || line.length() > 65536) {
                writeResponse(writer, error("empty or oversized request"));
                return;
            }
            try {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String action = required(request, "action");
                JsonObject response = switch (action) {
                    case "say" -> say(required(request, "message"));
                    case "tell" -> tell(required(request, "player"), required(request, "message"));
                    case "kick" -> kick(required(request, "player"), optional(request, "reason", "Kicked by an operator"));
                    case "ban" -> ban(required(request, "player"), optional(request, "reason", "Banned by an operator"));
                    case "unban" -> unban(required(request, "player"));
                    case "banlist" -> banList();
                    case "list" -> listPlayers();
                    default -> error("unknown action: " + action);
                };
                writeResponse(writer, response);
            } catch (Exception e) {
                writeResponse(writer, error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        } catch (IOException e) {
            System.err.println("[NekoGate] Failed to handle control request: " + e);
        }
    }

    private static String required(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) throw new IllegalArgumentException("missing field: " + key);
        String value = request.get(key).getAsString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException("empty field: " + key);
        if (value.length() > 8192) throw new IllegalArgumentException(key + " is too long");
        return value;
    }

    private static String optional(JsonObject request, String key, String fallback) {
        return request.has(key) && !request.get(key).isJsonNull()
                ? request.get(key).getAsString().trim() : fallback;
    }

    private static JsonObject error(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("message", message);
        return response;
    }

    private static void writeResponse(BufferedWriter writer, JsonObject response) throws IOException {
        writer.write(response.toString());
        writer.newLine();
        writer.flush();
    }

    private JsonObject say(String message) {
        int[] result = broadcast(message);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("message", "sent " + result[0] + "/" + result[1] + " players; failed=" + result[2]);
        response.addProperty("sent", result[0]);
        response.addProperty("total", result[1]);
        response.addProperty("failed", result[2]);
        return response;
    }

    private JsonObject tell(String target, String message) {
        int sent = 0;
        for (Channel channel : ViaProxy.getConnectedClients()) {
            ProxyConnection connection = connection(channel);
            if (connection == null || !isPlayer(connection, target)) continue;
            if (sendChat(connection, message)) sent++;
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", sent > 0);
        response.addProperty("message", sent > 0 ? "message sent to " + target : "player not found: " + target);
        return response;
    }

    private JsonObject kick(String target, String reason) {
        int kicked = 0;
        for (Channel channel : ViaProxy.getConnectedClients()) {
            ProxyConnection connection = connection(channel);
            if (connection == null || !isPlayer(connection, target)) continue;
            try {
                connection.kickClient(reason);
                kicked++;
            } catch (Throwable e) {
                System.err.println("[NekoGate] Could not kick " + target + ": " + e);
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", kicked > 0);
        response.addProperty("message", kicked > 0 ? "kicked " + target : "player not found: " + target);
        response.addProperty("count", kicked);
        return response;
    }

    private JsonObject ban(String target, String reason) throws SQLException {
        String uuid = null;
        for (Channel channel : ViaProxy.getConnectedClients()) {
            ProxyConnection connection = connection(channel);
            if (connection != null && isPlayer(connection, target)) {
                var profile = connection.getGameProfile();
                if (profile != null && profile.getId() != null) uuid = profile.getId().toString();
                break;
            }
        }
        synchronized (databaseLock) {
            try (PreparedStatement statement = database.prepareStatement(
                    "INSERT INTO bans(uuid, player_name, reason, banned_by, created_at) VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT(player_name) DO UPDATE SET uuid=excluded.uuid, reason=excluded.reason, banned_by=excluded.banned_by, created_at=excluded.created_at")) {
                statement.setString(1, uuid);
                statement.setString(2, target);
                statement.setString(3, reason);
                statement.setString(4, "console");
                statement.setString(5, Instant.now().toString());
                statement.executeUpdate();
            }
        }
        JsonObject response = kick(target, "Banned: " + reason);
        response.addProperty("ok", true);
        response.addProperty("message", "ban saved for " + target + (response.get("count").getAsInt() > 0 ? "; player disconnected" : "; enforced on next connection"));
        return response;
    }

    private JsonObject unban(String target) throws SQLException {
        int removed;
        synchronized (databaseLock) {
            try (PreparedStatement statement = database.prepareStatement(
                    "DELETE FROM bans WHERE lower(player_name)=lower(?) OR uuid=?")) {
                statement.setString(1, target);
                statement.setString(2, target);
                removed = statement.executeUpdate();
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", removed > 0);
        response.addProperty("message", removed > 0 ? "unbanned " + target : "no ban entry for " + target);
        return response;
    }

    private JsonObject banList() throws SQLException {
        JsonObject response = new JsonObject();
        JsonArray entries = new JsonArray();
        synchronized (databaseLock) {
            try (Statement statement = database.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT uuid, player_name, reason, banned_by, created_at FROM bans ORDER BY created_at")) {
                while (rows.next()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("uuid", rows.getString("uuid"));
                    entry.addProperty("player", rows.getString("player_name"));
                    entry.addProperty("reason", rows.getString("reason"));
                    entry.addProperty("by", rows.getString("banned_by"));
                    entry.addProperty("created_at", rows.getString("created_at"));
                    entries.add(entry);
                }
            }
        }
        response.addProperty("ok", true);
        response.addProperty("message", entries.size() + " ban(s)");
        response.add("bans", entries);
        return response;
    }

    private JsonObject listPlayers() {
        JsonArray players = new JsonArray();
        for (Channel channel : ViaProxy.getConnectedClients()) {
            ProxyConnection connection = connection(channel);
            if (connection == null) continue;
            var profile = connection.getGameProfile();
            if (profile != null && profile.getName() != null) players.add(profile.getName());
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("message", players.size() + " player(s) online");
        response.add("players", players);
        return response;
    }

    private void banEnforcementLoop() {
        while (this.running.get()) {
            for (Channel channel : ViaProxy.getConnectedClients()) {
                ProxyConnection connection = connection(channel);
                if (connection == null || connection.isClosed()) continue;
                var profile = connection.getGameProfile();
                if (profile == null || profile.getName() == null) continue;
                String sessionKey = profile.getId() == null ? profile.getName().toLowerCase() : profile.getId().toString();
                if (this.rejectedSessions.contains(sessionKey)) continue;
                try {
                    String reason = findBanReason(profile.getId() == null ? null : profile.getId().toString(), profile.getName());
                    if (reason != null) {
                        this.rejectedSessions.add(sessionKey);
                        connection.kickClient("Banned: " + reason);
                        System.out.println("[NekoGate] Rejected banned player " + profile.getName());
                    }
                } catch (SQLException e) {
                    System.err.println("[NekoGate] Ban lookup failed: " + e);
                } catch (Throwable ignored) {
                    // A kick closes the connection and may throw ViaProxy's close signal.
                }
            }
            this.rejectedSessions.removeIf(key -> ViaProxy.getConnectedClients().stream()
                    .noneMatch(channel -> {
                        ProxyConnection connection = connection(channel);
                        if (connection == null || connection.getGameProfile() == null) return false;
                        var profile = connection.getGameProfile();
                        return profile.getName() != null && key.equals(profile.getId() == null ? profile.getName().toLowerCase() : profile.getId().toString());
                    }));
            try { Thread.sleep(250L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private String findBanReason(String uuid, String playerName) throws SQLException {
        synchronized (databaseLock) {
            try (PreparedStatement statement = database.prepareStatement(
                    "SELECT reason FROM bans WHERE (uuid IS NOT NULL AND uuid=?) OR lower(player_name)=lower(?) LIMIT 1")) {
                statement.setString(1, uuid);
                statement.setString(2, playerName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        }
    }

    private static ProxyConnection connection(Channel channel) {
        if (channel == null || !channel.isActive()) return null;
        try {
            ProxyConnection connection = ProxyConnection.fromChannel(channel);
            return connection == null || connection.isClosed() ? null : connection;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPlayer(ProxyConnection connection, String target) {
        var profile = connection.getGameProfile();
        return profile != null && profile.getName() != null && profile.getName().equalsIgnoreCase(target);
    }

    private int[] broadcast(String message) {
        int total = 0, sent = 0, failed = 0;
        for (Channel channel : ViaProxy.getConnectedClients()) {
            ProxyConnection connection = connection(channel);
            if (connection == null || connection.getC2pConnectionState() != ConnectionState.PLAY
                    || connection.getP2sConnectionState() != ConnectionState.PLAY) continue;
            total++;
            if (sendChat(connection, message)) sent++; else failed++;
        }
        System.out.println("[NekoGate] say delivered=" + sent + "/" + total + " failed=" + failed);
        return new int[]{sent, total, failed};
    }

    private boolean sendChat(ProxyConnection connection, String message) {
        try {
            if (connection.getC2pConnectionState() != ConnectionState.PLAY
                    || connection.getP2sConnectionState() != ConnectionState.PLAY) return false;
            UserConnection user = connection.getUserConnection();
            if (user == null) return false;
            CompoundTag component = new CompoundTag();
            component.putString("text", message);
            return getChatTransformer().scheduleSend(user, ClientboundPackets26_3.SYSTEM_CHAT, packet -> {
                packet.write(Types.TAG, component);
                packet.write(Types.BOOLEAN, false);
            });
        } catch (Throwable e) {
            System.err.println("[NekoGate] Failed to inject chat: " + e);
            return false;
        }
    }

    private VersionedPacketTransformer<ClientboundPackets26_3, ?> getChatTransformer() {
        VersionedPacketTransformer<ClientboundPackets26_3, ?> current = this.chatTransformer;
        if (current == null) synchronized (this) {
            current = this.chatTransformer;
            if (current == null) {
                current = Via.getManager().getProtocolManager().createPacketTransformer(
                        ProtocolVersion.v26_3, ClientboundPackets26_3.class, null);
                this.chatTransformer = current;
            }
        }
        return current;
    }
}