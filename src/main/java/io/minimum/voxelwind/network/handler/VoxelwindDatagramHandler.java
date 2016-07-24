package io.minimum.voxelwind.network.handler;

import com.google.common.net.InetAddresses;
import io.minimum.voxelwind.VoxelwindServer;
import io.minimum.voxelwind.network.PacketRegistry;
import io.minimum.voxelwind.network.PacketType;
import io.minimum.voxelwind.network.mcpe.packets.McpeBatch;
import io.minimum.voxelwind.network.mcpe.packets.McpeLogin;
import io.minimum.voxelwind.network.mcpe.packets.McpeWrapper;
import io.minimum.voxelwind.network.raknet.RakNetPackage;
import io.minimum.voxelwind.network.raknet.datagrams.EncapsulatedRakNetPacket;
import io.minimum.voxelwind.network.raknet.enveloped.AddressedRakNetDatagram;
import io.minimum.voxelwind.network.raknet.packets.*;
import io.minimum.voxelwind.network.session.UserSession;
import io.minimum.voxelwind.network.util.EncryptionUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;

public class VoxelwindDatagramHandler extends SimpleChannelInboundHandler<AddressedRakNetDatagram> {
    private final VoxelwindServer server;
    private static final Logger LOGGER = LogManager.getLogger(VoxelwindDatagramHandler.class);

    public VoxelwindDatagramHandler(VoxelwindServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AddressedRakNetDatagram datagram) throws Exception {
        UserSession session = server.getSessionManager().get(datagram.sender());

        if (session == null)
            return;

        // Acknowledge receipt of the datagram.
        session.enqueueAck(datagram.content().getDatagramSequenceNumber());

        // Check the datagram contents.
        if (datagram.content().getFlags().isValid()) {
            for (EncapsulatedRakNetPacket packet : datagram.content().getPackets()) {
                // Try to figure out what packet got sent.
                if (packet.isHasSplit()) {
                    Optional<ByteBuf> possiblyReassembled = session.addSplitPacket(packet);
                    if (possiblyReassembled.isPresent()) {
                        ByteBuf reassembled = possiblyReassembled.get();
                        try {
                            RakNetPackage pkg = PacketRegistry.tryDecode(reassembled, PacketType.RAKNET);
                            handlePackage(pkg, session);
                        } finally {
                            reassembled.release();
                        }
                    }
                } else {
                    // Try to decode the full packet.
                    RakNetPackage pkg = PacketRegistry.tryDecode(packet.getBuffer(), PacketType.RAKNET);
                    handlePackage(pkg, session);
                }
            }
        }
    }

    private void handlePackage(RakNetPackage netPackage, UserSession session) throws Exception {
        System.out.println("[Package] " + netPackage);

        if (session.getHandler() == null) {
            LOGGER.error("Session " + session.getRemoteAddress() + " has no handler!?!?!");
            return;
        }

        // Special cases we need to handle here.
        // McpeWrapper: Encrypted packet.
        if (netPackage instanceof McpeWrapper) {
            ByteBuf cleartext = null;
            try {
                if (session.isEncrypted()) {
                    cleartext = PooledByteBufAllocator.DEFAULT.buffer();
                    session.getDecryptionCipher().cipher(((McpeWrapper) netPackage).getWrapped(), cleartext);
                } else {
                    cleartext = ((McpeWrapper) netPackage).getWrapped();
                }

                RakNetPackage pkg = PacketRegistry.tryDecode(cleartext, PacketType.MCPE);
                handlePackage(pkg, session);
            } finally {
                if (cleartext != null && cleartext != ((McpeWrapper) netPackage).getWrapped()) {
                    cleartext.release();
                }
            }
            return;
        }

        // McpeBatch: Multiple packets. This method will handle everything.
        if (netPackage instanceof McpeBatch) {
            for (RakNetPackage aPackage : ((McpeBatch) netPackage).getPackages()) {
                handlePackage(aPackage, session);
            }
            return;
        }
        // Connected Ping
        if (netPackage instanceof ConnectedPingPacket) {
            ConnectedPingPacket request = (ConnectedPingPacket) netPackage;
            ConnectedPongPacket response = new ConnectedPongPacket();
            response.setPingTime(request.getPingTime());
            response.setPongTime(System.currentTimeMillis());
            session.sendUrgentPackage(response);
            return;
        }
        // Connection Request
        if (netPackage instanceof ConnectionRequestPacket) {
            ConnectionRequestPacket request = (ConnectionRequestPacket) netPackage;
            ConnectionResponsePacket response = new ConnectionResponsePacket();
            response.setIncomingTimestamp(request.getTimestamp());
            response.setSystemTimestamp(System.currentTimeMillis());
            response.setSystemAddress(session.getRemoteAddress());
            InetSocketAddress[] addresses = new InetSocketAddress[10];
            Arrays.fill(addresses, new InetSocketAddress(InetAddresses.forString("255.255.255.255"), 19132));
            addresses[0] = new InetSocketAddress(InetAddress.getLoopbackAddress(), 19132);
            response.setSystemAddresses(addresses);
            response.setSystemIndex((short) 0);
            session.sendUrgentPackage(response);
            return;
        }
        // Disconnection
        if (netPackage instanceof DisconnectNotificationPacket) {
            session.close();
            return;
        }

        // Dispatch block...
        if (netPackage instanceof McpeLogin) {
            session.getHandler().handle((McpeLogin) netPackage);
        }
    }
}
