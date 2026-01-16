package networking;

import java.io.*;
import java.net.*;
import java.util.Enumeration;

import consensus.HoldBackQueue;
import game.NodeContext;

public class UdpMulticastManager {
private static final String MULTICAST_GROUP = "239.255.1.1";
    private static final int MULTICAST_PORT = 8888;
    
    private final int myTcpPort;
    private final NodeContext context; // Reference to Router
    private boolean running = true;

    public UdpMulticastManager(int tcpPort, NodeContext context) {
        this.myTcpPort = tcpPort;
        this.context = context;
    }

    public void start() {
        new Thread(this::listenForBroadcasts).start();
        
        new Thread(() -> {
            try { Thread.sleep(500); broadcastJoinRequest(); } catch (Exception e) {}
        }).start();
    }

    private void listenForBroadcasts() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            InetSocketAddress groupAddress = new InetSocketAddress(group, MULTICAST_PORT);
            
            NetworkInterface netIf = findValidNetworkInterface();
            socket.joinGroup(groupAddress, netIf);
            
            byte[] buffer = new byte[4096];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(packet.getData()));
                GameMessage msg = (GameMessage) ois.readObject();

                if (msg.tcpPort == myTcpPort && msg.sequenceNumber <= 0) continue;

                if (msg.type == GameMessage.Type.JOIN_REQUEST) {
                    context.tcp.connectToPeer(msg.senderAddress, msg.tcpPort);
                } 
                
                else {
                    context.routeMessage(msg);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void broadcastJoinRequest() {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            GameMessage msg = new GameMessage(
                GameMessage.Type.JOIN_REQUEST, 
                getPrivateIp(), 
                myTcpPort, 
                "Hello"
            );

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(msg);
            byte[] data = baos.toByteArray();

            DatagramPacket packet = new DatagramPacket(data, data.length, group, MULTICAST_PORT);
            socket.send(packet);
            System.out.println("[UDP] Broadcasted JOIN_REQUEST.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private NetworkInterface findValidNetworkInterface() throws SocketException {
        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface netIf : java.util.Collections.list(nets)) {
            // 1. Must be Up
            if (!netIf.isUp()) continue;
            
            // 2. Must support Multicast
            if (!netIf.supportsMulticast()) continue;
            
            // 3. Ignore Loopback (127.0.0.1) unless you are offline
            if (netIf.isLoopback()) continue;

            // 4. MAC SPECIFIC: Ignore "utun" (VPN) and "awdl" (AirDrop) interfaces
            // These often cause the "Can't assign requested address" error
            String name = netIf.getName();
            if (name.startsWith("utun") || name.startsWith("awdl") || name.startsWith("llw")) {
                continue;
            }

            // 5. Must have an IPv4 address
            boolean hasIpv4 = netIf.getInterfaceAddresses().stream()
                .anyMatch(addr -> addr.getAddress() instanceof Inet4Address);
                
            if (hasIpv4) {
                System.out.println("[UDP] Binding to interface: " + netIf.getName() + " (" + netIf.getDisplayName() + ")");
                return netIf;
            }
        }
        
        // Fallback: If no real interface found, use loopback (local testing only)
        System.out.println("[UDP] No valid WiFi/Ethernet found. Falling back to Loopback.");
        return NetworkInterface.getByName("lo0"); // 'lo0' is standard loopback on Mac
    }

    /**
     * Generic helper to send ANY message to the multicast group.
     * Used by the Sequencer to broadcast game actions.
     */
    public void sendMulticast(GameMessage msg) {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(msg);
            byte[] data = baos.toByteArray();

            DatagramPacket packet = new DatagramPacket(data, data.length, group, MULTICAST_PORT);
            socket.send(packet);
            
            System.out.println("[UDP] Sent Multicast: " + msg.type + " (Seq: " + msg.sequenceNumber + ")");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getPrivateIp() {
        try { return InetAddress.getLocalHost().getHostAddress(); } 
        catch (UnknownHostException e) { return "127.0.0.1"; }
    }
}