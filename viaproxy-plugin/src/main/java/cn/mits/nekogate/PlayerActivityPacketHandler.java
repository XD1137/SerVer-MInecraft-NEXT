package cn.mits.nekogate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import com.viaversion.nbt.io.TagRegistry;
import com.viaversion.nbt.limiter.TagLimiter;
import io.netty.channel.ChannelFutureListener;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.minecraft.item.data.ChatType;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.netminecraft.constants.ConnectionState;
import net.raphimc.netminecraft.constants.MCPackets;
import net.raphimc.netminecraft.constants.MCVersion;
import net.raphimc.netminecraft.constants.PacketDirection;
import net.raphimc.netminecraft.packet.Packet;
import net.raphimc.netminecraft.packet.PacketTypes;
import net.raphimc.netminecraft.packet.UnknownPacket;
import net.raphimc.viaproxy.proxy.packethandler.PacketHandler;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import cn.mits.nekogate.api.Component;
import cn.mits.nekogate.api.NekoGateAPI;
import cn.mits.nekogate.api.NekoGateEvents;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes player activity and movement; registered extension commands may be consumed before backend forwarding.
 */
final class PlayerActivityPacketHandler extends PacketHandler {
    private final PlayerActivityLogger logger;
    private final NekoGateApiImpl extensionApi;
    private final Map<String, String> backendPlayerNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> backendEntityIds = new ConcurrentHashMap<>();
    private final Map<Integer, String> backendEntityNames = new ConcurrentHashMap<>();
    private final AtomicBoolean joined = new AtomicBoolean();
    private final AtomicBoolean joinLogged = new AtomicBoolean();
    private volatile Instant joinedAt;
    private volatile String dimension;
    private volatile Double x;
    private volatile Double y;
    private volatile Double z;
    private volatile Float yaw;
    private volatile Float pitch;
    private volatile Instant loginAttemptAt;
    private volatile long loginDiagnosticPackets;
    private volatile long loginDiagnosticBytes;
    private volatile long lastNbtDiagnosticAt;

    private static final int MAX_SYSTEM_MESSAGE_NBT_BYTES = 64 * 1024;
    private static final int MAX_SYSTEM_MESSAGE_NBT_DEPTH = 64;
    private static final int MAX_SYSTEM_MESSAGE_NBT_TAGS = 8192;

    PlayerActivityPacketHandler(PlayerActivityLogger logger, ProxyConnection connection, NekoGateApiImpl extensionApi) {
        super(connection);
        this.logger = logger;
        this.extensionApi = extensionApi;
    }

    private NekoGateAPI.Player apiPlayer() {
        var profile = this.proxyConnection.getGameProfile();
        return profile == null ? null : new NekoGateAPI.Player(profile.getId(), profile.getName());
    }

    private void publish(Object event) {
        if (this.extensionApi != null) this.extensionApi.publish(event);
    }

    @Override
    public boolean handleC2P(Packet packet, List<ChannelFutureListener> listeners) {
        if (this.proxyConnection.getC2pConnectionState() != ConnectionState.PLAY) return true;

        if (packet instanceof UnknownPacket unknown) {
            MCPackets type = MCPackets.getPacket(
                    ConnectionState.PLAY,
                    PacketDirection.SERVERBOUND,
                    this.proxyConnection.getClientVersion().getVersion(),
                    unknown.packetId
            );
            if (type == null) return true;

            if (this.logger.isLocationLoggingEnabled()) {
                updateMovement(type, unknown.data);
            }
            if (type == MCPackets.C2S_CHAT || type == MCPackets.C2S_CHAT_COMMAND
                    || type == MCPackets.C2S_CHAT_COMMAND_SIGNED) {
                if (!logRawChat(type, unknown.data)) return false;
            }
            return true;
        }

        // Keep compatibility with packet implementations that expose decoded fields.
        String packetName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (!packetName.contains("chatcommand") && !packetName.contains("chatmessage")) return true;

        String player = playerName();
        if (player == null) return true;
        String uuid = playerUuid();

        if (packetName.contains("chatcommand")) {
            String command = readString(packet, "command");
            if (command != null && !command.isBlank()) {
                String normalized = command.startsWith("/") ? command : "/" + command;
                this.logger.logCommand(player, uuid, normalized);
                publish(new NekoGateEvents.Command(Instant.now(), apiPlayer(), normalized));
                logWhisperCommand(player, uuid, normalized);
                if (this.extensionApi != null && this.extensionApi.dispatchCommand(apiPlayer(), normalized)) return false;
            }
        } else {
            String message = readString(packet, "message");
            if (message == null || message.isBlank()) return true;
            if (message.startsWith("/")) {
                this.logger.logCommand(player, uuid, message);
                publish(new NekoGateEvents.Command(Instant.now(), apiPlayer(), message));
                logWhisperCommand(player, uuid, message);
                if (this.extensionApi != null && this.extensionApi.dispatchCommand(apiPlayer(), message)) return false;
            } else {
                this.logger.logChat(player, uuid, message);
                publish(new NekoGateEvents.Chat(Instant.now(), apiPlayer(), message));
            }
        }
        return true;
    }

    @Override
    public boolean handleP2S(Packet packet, List<ChannelFutureListener> listeners) {
        if (packet instanceof UnknownPacket unknown) {
            int serverProtocol = this.proxyConnection.getServerVersion().getVersion();
            MCPackets type = MCPackets.getPacket(
                    ConnectionState.PLAY,
                    PacketDirection.CLIENTBOUND,
                    serverProtocol,
                    unknown.packetId
            );
            if (serverProtocol >= MCVersion.v26_3) {
                if (type == MCPackets.S2C_PLAYER_INFO_UPDATE) {
                    updateBackendPlayerNames(unknown.data);
                    unknown.data = rewritePlayerInfoUpdate(unknown.data);
                } else if (type == MCPackets.S2C_PLAYER_INFO_REMOVE) {
                    removeBackendPlayerNames(unknown.data);
                } else if (type == MCPackets.S2C_ADD_ENTITY) {
                    trackPlayerEntity(unknown.data, listeners);
                } else if (type == MCPackets.S2C_SET_ENTITY_DATA) {
                    reapplyPlayerHeadName(unknown.data, listeners);
                } else if (type == MCPackets.S2C_REMOVE_ENTITIES) {
                    removePlayerEntities(unknown.data);
                }
            }
            logLoginDiagnosticPacket(type, unknown.packetId, unknown.data == null ? 0 : unknown.data.length, serverProtocol);
            if (type == MCPackets.S2C_PLAYER_CHAT) {
                unknown.data = rewritePlayerChat(unknown.data, serverProtocol);
                logRawServerChat(unknown.data, serverProtocol);
            }
            if (type == MCPackets.S2C_SYSTEM_CHAT) {
                logRawSystemMessage(unknown.data);
            }
            if (this.logger.isLocationLoggingEnabled()
                    && (type == MCPackets.S2C_LOGIN || type == MCPackets.S2C_RESPAWN)) {
                updateDimension(extractDimension(type, unknown.data, serverProtocol));
            }
        }

        if (!this.joined.get() && packet.getClass().getSimpleName().contains("LoginGameProfile")) {
            String player = playerName();
            if (player != null && this.joined.compareAndSet(false, true)) {
                this.joinedAt = Instant.now();
                logLoginDiagnostic("LOGIN_ACCEPTED", "server profile established; packets=" + this.loginDiagnosticPackets
                        + " bytes=" + this.loginDiagnosticBytes);
                publish(new NekoGateEvents.PlayerJoin(Instant.now(), apiPlayer()));
                logJoinIfReady();
            }
        }
        return true;
    }

    void onDisconnect() {
        if (this.loginAttemptAt != null) {
            logLoginDiagnostic("CONNECTION_CLOSED", "packets=" + this.loginDiagnosticPackets
                    + " bytes=" + this.loginDiagnosticBytes);
        }
        Instant start = this.joinedAt;
        if (start == null || !this.joined.compareAndSet(true, false)) return;

        String player = playerName();
        if (player == null) return;
        publish(new NekoGateEvents.PlayerQuit(Instant.now(), apiPlayer()));
        if (this.joinLogged.compareAndSet(false, true)) {
            this.logger.logJoin(player, playerUuid(), start, this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
        }
        this.logger.logLeave(player, playerUuid(), start, Instant.now(),
                this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    private void logRawServerChat(byte[] data, int protocolVersion) {
        if (data == null || data.length == 0) return;
        try {
            ByteBuf buffer = Unpooled.wrappedBuffer(data);
            try {
                // Minecraft 1.21.9 added a per-client global chat index before the sender UUID.
                if (protocolVersion >= MCVersion.v1_21_9) {
                    PacketTypes.readVarInt(buffer);
                }
                UUID sender = PacketTypes.readUuid(buffer);
                // The sender's own connection is already covered by the client-to-proxy chat log.
                // Another tunnel client's connection can still observe and record this broadcast.
                String receivingPlayerUuid = playerUuid();
                if (sender.toString().equalsIgnoreCase(receivingPlayerUuid == null ? "" : receivingPlayerUuid)) return;
                PacketTypes.readVarInt(buffer); // Sender message index
                if (buffer.readBoolean()) {
                    buffer.skipBytes(256); // Optional message signature
                }
                String message = PacketTypes.readString(buffer, 256);
                long messageTimestamp = buffer.readLong();
                String senderName = this.backendPlayerNames.get(sender.toString());
                this.logger.logServerChat(sender.toString(), senderName, message, messageTimestamp);
                publish(new NekoGateEvents.ServerChat(Instant.now(), apiPlayer(), message, sender.toString(), senderName));
            } finally {
                buffer.release();
            }
        } catch (RuntimeException ignored) {
            // Unsupported or malformed backend chat payloads must never affect forwarding.
        }
    }


    /**
     * Minecraft 26.3 player_info_update: action flags (byte), entry count,
     * then UUID and the payloads selected by each action bit in ordinal order.
     */
    private void updateBackendPlayerNames(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int actions = buffer.readUnsignedByte();
            int count = PacketTypes.readVarInt(buffer);
            if (count < 0 || count > 4096) return;

            for (int i = 0; i < count; i++) {
                UUID playerId = PacketTypes.readUuid(buffer);
                if ((actions & 0x01) != 0) {
                    String name = PacketTypes.readString(buffer, 16);
                    if (name != null && !name.isBlank()) {
                        this.backendPlayerNames.put(playerId.toString(), name);
                    }
                    int properties = PacketTypes.readVarInt(buffer);
                    if (properties < 0 || properties > 1024) return;
                    for (int property = 0; property < properties; property++) {
                        PacketTypes.readString(buffer, 64); // Property name
                        PacketTypes.readString(buffer, 32767); // Property value
                        if (buffer.readBoolean()) {
                            PacketTypes.readString(buffer, 1024); // Optional signature
                        }
                    }
                }
                if ((actions & 0x02) != 0 && buffer.readBoolean()) {
                    PacketTypes.readUuid(buffer); // Chat session ID
                    buffer.readLong(); // Public key expiry
                    skipLengthPrefixedBytes(buffer); // Encoded public key
                    skipLengthPrefixedBytes(buffer); // Public key signature
                }
                if ((actions & 0x04) != 0) PacketTypes.readVarInt(buffer); // Game mode
                if ((actions & 0x08) != 0) buffer.readBoolean(); // Listed
                if ((actions & 0x10) != 0) PacketTypes.readVarInt(buffer); // Latency
                if ((actions & 0x20) != 0) Types.OPTIONAL_TAG.read(buffer); // Display name component
                if ((actions & 0x40) != 0) PacketTypes.readVarInt(buffer); // List order
                if ((actions & 0x80) != 0) buffer.readBoolean(); // Show hat
            }
        } catch (RuntimeException ignored) {
            // Never interfere with forwarding if a backend sends an unknown packet layout.
        } finally {
            buffer.release();
        }
    }

    /**
     * Rewrites the profile name used for the in-world player name tag and the optional
     * display-name component used by the player list. UUIDs, skin properties, chat
     * sessions, and all unrelated fields are preserved for ViaVersion translation.
     */
    private byte[] rewritePlayerInfoUpdate(byte[] data) {
        if (data == null || data.length == 0 || this.extensionApi == null) return data;
        ByteBuf input = Unpooled.wrappedBuffer(data);
        ByteBuf output = Unpooled.buffer(data.length + 128);
        try {
            int actions = input.readUnsignedByte();
            int count = PacketTypes.readVarInt(input);
            if (count < 0 || count > 4096) return data;
            copyConsumed(input, output, 0);
            for (int i = 0; i < count; i++) {
                int fieldStart = input.readerIndex();
                UUID playerId = PacketTypes.readUuid(input);
                copyConsumed(input, output, fieldStart);

                if ((actions & 0x01) != 0) {
                    fieldStart = input.readerIndex();
                    String username = PacketTypes.readString(input, 16);
                    if (username != null && !username.isBlank()) {
                        this.backendPlayerNames.put(playerId.toString(), username);
                    }
                    // GameProfile.name is the real backend username. Never rewrite it:
                    // command suggestions and commands such as /tell use this identity.
                    copyConsumed(input, output, fieldStart);

                    fieldStart = input.readerIndex();
                    int properties = PacketTypes.readVarInt(input);
                    if (properties < 0 || properties > 1024) return data;
                    for (int property = 0; property < properties; property++) {
                        PacketTypes.readString(input, 64);
                        PacketTypes.readString(input, 32767);
                        if (input.readBoolean()) PacketTypes.readString(input, 1024);
                    }
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x02) != 0) {
                    fieldStart = input.readerIndex();
                    if (input.readBoolean()) {
                        PacketTypes.readUuid(input);
                        input.readLong();
                        skipLengthPrefixedBytes(input);
                        skipLengthPrefixedBytes(input);
                    }
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x04) != 0) {
                    fieldStart = input.readerIndex();
                    PacketTypes.readVarInt(input);
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x08) != 0) {
                    fieldStart = input.readerIndex();
                    input.readBoolean();
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x10) != 0) {
                    fieldStart = input.readerIndex();
                    PacketTypes.readVarInt(input);
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x20) != 0) {
                    fieldStart = input.readerIndex();
                    Types.OPTIONAL_TAG.read(input);
                    String username = this.backendPlayerNames.get(playerId.toString());
                    if (username != null && this.extensionApi.displayNames().find(username).isPresent()) {
                        Tag displayName = toTag(this.extensionApi.displayNames().tabName(username));
                        Types.OPTIONAL_TAG.write(output, displayName);
                    } else {
                        copyConsumed(input, output, fieldStart);
                    }
                }
                if ((actions & 0x40) != 0) {
                    fieldStart = input.readerIndex();
                    PacketTypes.readVarInt(input);
                    copyConsumed(input, output, fieldStart);
                }
                if ((actions & 0x80) != 0) {
                    fieldStart = input.readerIndex();
                    input.readBoolean();
                    copyConsumed(input, output, fieldStart);
                }
            }
            if (input.isReadable()) return data;
            byte[] rewritten = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), rewritten);
            return rewritten;
        } catch (RuntimeException ignored) {
            // Malformed or unsupported packet layouts are forwarded unchanged.
            return data;
        } finally {
            input.release();
            output.release();
        }
    }

    /**
     * In 26.3 PLAYER_CHAT carries senderName as an NBT component. Replacing that
     * presentation field changes the chat label without touching the sender UUID,
     * message signature, body, chat type, or target name.
     */
    private byte[] rewritePlayerChat(byte[] data, int protocolVersion) {
        if (data == null || data.length == 0 || this.extensionApi == null
                || protocolVersion < MCVersion.v26_3) return data;
        ByteBuf input = Unpooled.wrappedBuffer(data);
        ByteBuf output = Unpooled.buffer(data.length + 128);
        try {
            PacketTypes.readVarInt(input); // Global chat index (26.3)
            UUID sender = PacketTypes.readUuid(input);
            PacketTypes.readVarInt(input); // Sender message index
            if (input.readBoolean()) input.skipBytes(256); // Optional signature
            PacketTypes.readString(input, 256); // Signed message
            input.readLong(); // Timestamp
            input.readLong(); // Salt
            int previousMessages = PacketTypes.readVarInt(input);
            if (previousMessages < 0 || previousMessages > 1024) return data;
            for (int i = 0; i < previousMessages; i++) {
                if (PacketTypes.readVarInt(input) == 0) input.skipBytes(256);
            }
            Types.OPTIONAL_TAG.read(input); // Unsigned content
            int filterType = PacketTypes.readVarInt(input);
            if (filterType == 2) Types.BIT_SET.read(input); // Partially filtered message
            ChatType.TYPE.read(input); // Holder<ChatType>

            int senderNameStart = input.readerIndex();
            Types.TAG.read(input); // Sender name component
            String username = this.backendPlayerNames.get(sender.toString());
            if (username == null || this.extensionApi.displayNames().find(username).isEmpty()) return data;

            Tag displayName = toTag(this.extensionApi.displayNames().chatName(username));
            output.writeBytes(input, 0, senderNameStart);
            Types.TAG.write(output, displayName);
            output.writeBytes(input, input.readerIndex(), input.readableBytes());

            byte[] rewritten = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), rewritten);
            return rewritten;
        } catch (RuntimeException ignored) {
            // Never let a malformed chat payload interfere with normal forwarding.
            return data;
        } finally {
            input.release();
            output.release();
        }
    }

    private static Tag toTag(Component component) {
        CompoundTag tag = new CompoundTag();
        tag.putString("text", component.text());
        if (component.color() != null) tag.putString("color", component.color());
        if (component.bold()) tag.putBoolean("bold", true);
        if (component.italic()) tag.putBoolean("italic", true);
        if (!component.children().isEmpty()) {
            List<Tag> children = new java.util.ArrayList<>(component.children().size());
            for (Component child : component.children()) children.add(toTag(child));
            tag.put("extra", new ListTag<>(children));
        }
        return tag;
    }

    private static void copyConsumed(ByteBuf input, ByteBuf output, int startIndex) {
        int length = input.readerIndex() - startIndex;
        if (length > 0) output.writeBytes(input, startIndex, length);
    }

    private void removeBackendPlayerNames(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int count = PacketTypes.readVarInt(buffer);
            if (count < 0 || count > 4096) return;
            for (int i = 0; i < count; i++) {
                String uuid = PacketTypes.readUuid(buffer).toString();
                this.backendPlayerNames.remove(uuid);
                Integer entityId = this.backendEntityIds.remove(uuid);
                if (entityId != null) this.backendEntityNames.remove(entityId);
            }
        } catch (RuntimeException ignored) {
            // Ignore malformed player-list updates; packet forwarding remains untouched.
        } finally {
            buffer.release();
        }
    }

    /**
     * Tracks backend player entity IDs without changing their GameProfile names.
     * In 26.3 player entities are spawned through Add Entity, which carries the
     * same UUID that was announced in Player Info Update.
     */
    private void trackPlayerEntity(byte[] data, List<ChannelFutureListener> listeners) {
        if (data == null || data.length == 0 || this.extensionApi == null) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int entityId = PacketTypes.readVarInt(buffer);
            UUID playerId = PacketTypes.readUuid(buffer);
            String username = this.backendPlayerNames.get(playerId.toString());
            if (username == null) return;

            this.backendEntityIds.put(playerId.toString(), entityId);
            this.backendEntityNames.put(entityId, username);
            queuePlayerHeadName(entityId, username, listeners);
        } catch (RuntimeException ignored) {
            // Unknown or malformed spawn packets must not affect forwarding.
        } finally {
            buffer.release();
        }
    }

    /**
     * Server metadata updates can overwrite an entity's custom name. Re-send
     * the configured value after the backend update so it remains presentation-only.
     */
    private void reapplyPlayerHeadName(byte[] data, List<ChannelFutureListener> listeners) {
        if (data == null || data.length == 0 || this.extensionApi == null) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int entityId = PacketTypes.readVarInt(buffer);
            String username = this.backendEntityNames.get(entityId);
            if (username != null) queuePlayerHeadName(entityId, username, listeners, protocolVersion);
        } catch (RuntimeException ignored) {
            // Ignore malformed metadata packets.
        } finally {
            buffer.release();
        }
    }

    private void queuePlayerHeadName(int entityId, String username,
                                     List<ChannelFutureListener> listeners) {
        var displayName = this.extensionApi.displayNames().find(username).orElse(null);
        if (displayName == null) return;

        // This packet is written directly to the downstream client channel, so its ID
        // must be resolved using the client's protocol, not the backend's protocol.
        int clientProtocolVersion = this.proxyConnection.getClientVersion().getVersion();
        int packetId = MCPackets.S2C_SET_ENTITY_DATA.getId(clientProtocolVersion);
        if (packetId < 0) return;
        ByteBuf output = Unpooled.buffer();
        try {
            PacketTypes.writeVarInt(output, entityId);
            output.writeByte(2); // Entity custom_name metadata index
            PacketTypes.writeVarInt(output, 6); // Optional component serializer
            Types.OPTIONAL_TAG.write(output, toTag(Component.join(
                    displayName.prefix(), displayName.name(), displayName.suffix())));
            output.writeByte(3); // Entity custom_name_visible metadata index
            PacketTypes.writeVarInt(output, 8); // Boolean serializer
            output.writeBoolean(true);
            output.writeByte(0xFF); // Metadata terminator

            byte[] metadata = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), metadata);
            listeners.add(future -> future.channel().writeAndFlush(new UnknownPacket(packetId, metadata)));
        } catch (RuntimeException ignored) {
            // Never let an invalid component prevent the original packet from forwarding.
        } finally {
            output.release();
        }
    }

    private void removePlayerEntities(byte[] data) {
        if (data == null || data.length == 0) return;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            int count = PacketTypes.readVarInt(buffer);
            if (count < 0 || count > 4096) return;
            for (int i = 0; i < count; i++) {
                int entityId = PacketTypes.readVarInt(buffer);
                String username = this.backendEntityNames.remove(entityId);
                if (username != null) {
                    this.backendEntityIds.entrySet().removeIf(entry ->
                            entry.getValue() != null && entry.getValue() == entityId);
                }
            }
        } catch (RuntimeException ignored) {
            // Ignore malformed entity-removal packets.
        } finally {
            buffer.release();
        }
    }

    private static void skipLengthPrefixedBytes(ByteBuf buffer) {
        int length = PacketTypes.readVarInt(buffer);
        if (length < 0 || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid length-prefixed byte array");
        }
        buffer.skipBytes(length);
    }


    private void logRawSystemMessage(byte[] data) {
        if (data == null || data.length == 0) return;
        if (data.length > MAX_SYSTEM_MESSAGE_NBT_BYTES) {
            logNbtDiagnostic(data.length, "packet_too_large");
            return;
        }

        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            // Do not use Types.TAG here: its default 2 MiB budget is shared with
            // neither this audit path nor the available heap. A smaller per-message
            // limiter prevents a single system-chat packet from allocating a huge array.
            int tagId = buffer.readUnsignedByte();
            int payloadStart = buffer.readerIndex();
            if (tagId != 0) {
                // Validate every declared collection length before ViaNBT can allocate
                // an array or collection from an untrusted length prefix.
                scanNbtPayload(buffer, tagId, 0, new int[]{0});
                buffer.readerIndex(payloadStart);
            }
            Tag component = tagId == 0 ? null : TagRegistry.read(
                    tagId,
                    new ByteBufInputStream(buffer),
                    TagLimiter.create(
                            MAX_SYSTEM_MESSAGE_NBT_BYTES,
                            MAX_SYSTEM_MESSAGE_NBT_DEPTH,
                            MAX_SYSTEM_MESSAGE_NBT_TAGS
                    ),
                    0
            );
            boolean overlay = buffer.readBoolean();
            if (buffer.isReadable()) {
                logNbtDiagnostic(data.length, "trailing_data");
                return;
            }

            String serialized = component == null ? "" : component.toString();
            if (!serialized.isBlank()) {
                this.logger.logSystemMessage(playerName(), playerUuid(), serialized, overlay);
                publish(new NekoGateEvents.SystemMessage(Instant.now(), apiPlayer(), serialized, overlay));
                // Vanilla /msg, /tell and /w responses use these translation keys.
                if (serialized.contains("commands.message.display.incoming")) {
                    this.logger.logReceivedWhisper(playerName(), playerUuid(), serialized);
                    publish(new NekoGateEvents.Whisper(Instant.now(), apiPlayer(), "received", null, null, serialized));
                }
            }
        } catch (java.io.IOException | RuntimeException exception) {
            logNbtDiagnostic(data.length, "invalid_nbt_" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : "_" + exception.getMessage()));
            // Audit parsing is best-effort and must never affect packet forwarding.
        } finally {
            buffer.release();
        }
    }

    /**
     * Allocation-free NBT preflight. ViaNBT's limiter accounts for declared array
     * bytes, but this scan makes the length checks explicit before any Tag objects
     * or backing arrays are created by the real parser.
     *
     * The input is the network NBT form used by Minecraft components: root tag ID,
     * followed by the root payload (without a root name).
     */
    private static void scanNbtPayload(ByteBuf input, int tagId, int depth, int[] tagCount) {
        if (depth > MAX_SYSTEM_MESSAGE_NBT_DEPTH) {
            throw new IllegalArgumentException("nbt_depth_limit");
        }
        if (++tagCount[0] > MAX_SYSTEM_MESSAGE_NBT_TAGS) {
            throw new IllegalArgumentException("nbt_tag_limit");
        }

        switch (tagId) {
            case 1 -> input.skipBytes(1); // Byte
            case 2 -> input.skipBytes(2); // Short
            case 3, 5 -> input.skipBytes(4); // Int / Float
            case 4, 6 -> input.skipBytes(8); // Long / Double
            case 7 -> { // Byte array
                int length = input.readInt();
                checkNbtArrayLength(length, 1, input.readableBytes());
                input.skipBytes(length);
            }
            case 8 -> skipNbtString(input);
            case 9 -> { // List
                int childType = input.readUnsignedByte();
                int length = input.readInt();
                if (length < 0 || length > MAX_SYSTEM_MESSAGE_NBT_TAGS - tagCount[0]) {
                    throw new IllegalArgumentException("nbt_list_length_" + length);
                }
                if (length > 0 && (childType < 1 || childType > 12)) {
                    throw new IllegalArgumentException("nbt_list_type_" + childType);
                }
                for (int i = 0; i < length; i++) {
                    scanNbtPayload(input, childType, depth + 1, tagCount);
                }
            }
            case 10 -> { // Compound
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == 0) break;
                    if (childType < 1 || childType > 12) {
                        throw new IllegalArgumentException("nbt_tag_type_" + childType);
                    }
                    skipNbtString(input);
                    scanNbtPayload(input, childType, depth + 1, tagCount);
                }
            }
            case 11 -> { // Int array
                int length = input.readInt();
                checkNbtArrayLength(length, Integer.BYTES, input.readableBytes());
                input.skipBytes(length * Integer.BYTES);
            }
            case 12 -> { // Long array
                int length = input.readInt();
                checkNbtArrayLength(length, Long.BYTES, input.readableBytes());
                input.skipBytes(length * Long.BYTES);
            }
            default -> throw new IllegalArgumentException("nbt_tag_type_" + tagId);
        }
    }

    private static void checkNbtArrayLength(int length, int elementBytes, int readableBytes) {
        if (length < 0 || length > MAX_SYSTEM_MESSAGE_NBT_BYTES / elementBytes
                || (long) length * elementBytes > readableBytes) {
            throw new IllegalArgumentException("nbt_array_length_" + length);
        }
    }

    private static void skipNbtString(ByteBuf input) {
        int length = input.readUnsignedShort();
        input.skipBytes(length);
    }

    private void logNbtDiagnostic(int packetBytes, String reason) {
        if (!this.logger.isNbtDiagnosticsEnabled()) return;
        long now = System.currentTimeMillis();
        long previous = this.lastNbtDiagnosticAt;
        if (now - previous < 10_000) return;
        this.lastNbtDiagnosticAt = now;
        System.err.println("[NekoGate][NBT-DIAG] time=" + Instant.now()
                + " player=" + (playerName() == null ? "<unknown>" : playerName())
                + " packetBytes=" + packetBytes + " reason=" + reason);
    }

    private void logWhisperCommand(String sender, String senderUuid, String command) {
        String value = command.startsWith("/") ? command.substring(1) : command;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)^(?:minecraft:)?(?:msg|tell|w|whisper)\\s+(\\S+)\\s+(.+)$"
        ).matcher(value);
        if (matcher.matches()) {
            this.logger.logWhisper(sender, senderUuid, matcher.group(1), matcher.group(2), "sent");
            publish(new NekoGateEvents.Whisper(Instant.now(), apiPlayer(), "sent",
                    matcher.group(1), matcher.group(2), null));
        }
    }

    private boolean logRawChat(MCPackets type, byte[] data) {
        if (data == null || data.length == 0) return true;
        try {
            ByteBuf buffer = Unpooled.wrappedBuffer(data);
            String value;
            try {
                value = PacketTypes.readString(buffer, 32767);
            } finally {
                buffer.release();
            }
            if (value == null || value.isBlank()) return true;

            String player = playerName();
            if (player == null) return true;
            if (type == MCPackets.C2S_CHAT && !value.startsWith("/")) {
                this.logger.logChat(player, playerUuid(), value);
                publish(new NekoGateEvents.Chat(Instant.now(), apiPlayer(), value));
                return true;
            }

            String command = value.startsWith("/") ? value : "/" + value;
            String safeCommand = safeCommandForLog(command);
            this.logger.logCommand(player, playerUuid(), safeCommand);
            publish(new NekoGateEvents.Command(Instant.now(), apiPlayer(), safeCommand));
            markLoginAttempt(command);
            logWhisperCommand(player, playerUuid(), command);
            return this.extensionApi == null || !this.extensionApi.dispatchCommand(apiPlayer(), command);
        } catch (RuntimeException ignored) {
            // Malformed or version-specific payloads must never affect packet forwarding.
            return true;
        }
    }

    private void markLoginAttempt(String command) {
        if (command == null || !command.stripLeading().matches("(?i)^/?login(?:\\s+.*)?$")) return;
        this.loginAttemptAt = Instant.now();
        this.loginDiagnosticPackets = 0;
        this.loginDiagnosticBytes = 0;
        logLoginDiagnostic("LOGIN_COMMAND", "command received; arguments redacted; clientProtocol="
                + this.proxyConnection.getClientVersion().getVersion()
                + " serverProtocol=" + this.proxyConnection.getServerVersion().getVersion());
    }

    private static String safeCommandForLog(String command) {
        if (command != null && command.stripLeading().matches("(?i)^/?login(?:\\s+.*)?$")) {
            return "/login <redacted>";
        }
        return command;
    }

    private void logLoginDiagnosticPacket(MCPackets type, int packetId, int bytes, int serverProtocol) {
        Instant started = this.loginAttemptAt;
        if (started == null) return;
        long count = ++this.loginDiagnosticPackets;
        this.loginDiagnosticBytes += bytes;
        if (count <= 250 || count % 100 == 0) {
            String packetName = type == null ? "UNKNOWN" : type.name();
            logLoginDiagnostic("S2C_PACKET", "index=" + count + " type=" + packetName
                    + " id=0x" + Integer.toHexString(packetId) + " bytes=" + bytes
                    + " totalBytes=" + this.loginDiagnosticBytes
                    + " elapsedMs=" + (System.currentTimeMillis() - started.toEpochMilli())
                    + " serverProtocol=" + serverProtocol);
        }
    }

    private void logLoginDiagnostic(String event, String details) {
        String player = playerName();
        System.err.println("[NekoGate][LOGIN-DIAG] time=" + Instant.now()
                + " event=" + event + " player=" + (player == null ? "<unknown>" : player)
                + " client=" + this.proxyConnection.getClientVersion().getVersion()
                + " server=" + this.proxyConnection.getServerVersion().getVersion()
                + " " + details);
    }

    private void updateMovement(MCPackets type, byte[] data) {
        if (data == null) return;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            switch (type) {
                case C2S_MOVE_PLAYER_POS -> {
                    if (buffer.remaining() < 24) return;
                    this.x = buffer.getDouble();
                    this.y = buffer.getDouble();
                    this.z = buffer.getDouble();
                }
                case C2S_MOVE_PLAYER_POS_ROT -> {
                    if (buffer.remaining() < 32) return;
                    this.x = buffer.getDouble();
                    this.y = buffer.getDouble();
                    this.z = buffer.getDouble();
                    this.yaw = buffer.getFloat();
                    this.pitch = buffer.getFloat();
                }
                case C2S_MOVE_PLAYER_ROT -> {
                    if (buffer.remaining() < 8) return;
                    this.yaw = buffer.getFloat();
                    this.pitch = buffer.getFloat();
                }
                default -> {
                    return;
                }
            }
            logJoinIfReady();
        } catch (RuntimeException ignored) {
            // Ignore malformed or unsupported movement payloads.
        }
    }

    private void updateDimension(String nextDimension) {
        if (nextDimension == null || nextDimension.isBlank()) return;
        String previous = this.dimension;
        if (nextDimension.equals(previous)) return;
        this.dimension = nextDimension;

        if (previous != null && this.joined.get()) {
            String player = playerName();
            if (player != null) {
                this.logger.logDimensionChange(player, playerUuid(), previous, nextDimension,
                        this.x, this.y, this.z, this.yaw, this.pitch);
            }
        }
    }

    private void logJoinIfReady() {
        Instant start = this.joinedAt;
        if (start == null || !this.joined.get() || !this.joinLogged.compareAndSet(false, true)) return;
        String player = playerName();
        if (player != null) {
            this.logger.logJoin(player, playerUuid(), start,
                    this.dimension, this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }

    private static String extractDimension(MCPackets type, byte[] data, int protocolVersion) {
        if (data == null || data.length == 0 || protocolVersion < MCVersion.v1_20_2) return null;
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        try {
            if (type == MCPackets.S2C_LOGIN) {
                buffer.readInt(); // Entity ID
                buffer.readBoolean(); // Hardcore
                int dimensionCount = PacketTypes.readVarInt(buffer);
                if (dimensionCount < 0 || dimensionCount > 4096) return null;
                for (int i = 0; i < dimensionCount; i++) {
                    PacketTypes.readString(buffer, 32767);
                }
                PacketTypes.readVarInt(buffer); // Max players
                PacketTypes.readVarInt(buffer); // View distance
                PacketTypes.readVarInt(buffer); // Simulation distance
                buffer.readBoolean(); // Reduced debug info
                buffer.readBoolean(); // Show respawn screen
                buffer.readBoolean(); // Do limited crafting
                PacketTypes.readVarInt(buffer); // Dimension type
                return PacketTypes.readString(buffer, 32767); // Current dimension identifier
            }
            if (type == MCPackets.S2C_RESPAWN) {
                PacketTypes.readVarInt(buffer); // Dimension type
                return PacketTypes.readString(buffer, 32767); // Current dimension identifier
            }
        } catch (RuntimeException ignored) {
            // Older or non-standard packet layouts are left without a dimension value.
        } finally {
            buffer.release();
        }
        return null;
    }

    private String playerName() {
        var profile = this.proxyConnection.getGameProfile();
        return profile == null ? null : profile.getName();
    }

    private String playerUuid() {
        var profile = this.proxyConnection.getGameProfile();
        return profile == null || profile.getId() == null ? null : profile.getId().toString();
    }

    private static String readString(Packet packet, String preferredField) {
        String fallback = null;
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    if (!field.trySetAccessible()) continue;
                    Object value = field.get(packet);
                    if (!(value instanceof String text)) continue;
                    if (field.getName().equalsIgnoreCase(preferredField)) return text;
                    if (fallback == null) fallback = text;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Packet implementations may hide fields; try the remaining fields.
                }
            }
        }
        return fallback;
    }
}
