package networking;

import java.io.*;
import java.net.*;
import java.util.Enumeration;

import consensus.HoldBackQueue;

public class UdpMulticastManager {
    private static final String MULTICAST_GROUP = "239.255.1.1";
    private static final int MULTICAST_PORT = 8888;
    
    private boolean running = true;
    private int myTcpPort;

    private TcpMeshManager connectionManager;
    
    private HoldBackQueue holdBackQueue;

    public UdpMulticastManager(int tcpPort, TcpMeshManager manager) {
        this.myTcpPort = tcpPort;
        this.connectionManager = manager;
    }

    public void setHoldBackQueue(HoldBackQueue q) { this.holdBackQueue = q; }

    public void start() {
        // 1. Start listening for others
        new Thread(this::listenForBroadcasts).start();
        
        // 2. Shout "I am here" exactly ONCE. Later this should be on a loop but that would have to be reconciled
        // with game logic
        try {
            Thread.sleep(500); // Wait briefly for listener to spin up
            broadcastJoinRequest();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void listenForBroadcasts() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            InetSocketAddress groupAddress = new InetSocketAddress(group, MULTICAST_PORT);
            
            // Find a valid network interface (WiFi/Ethernet)
            NetworkInterface netIf = findValidNetworkInterface();
            socket.joinGroup(groupAddress, netIf); // Modern join

            System.out.println("[UDP] Listening on " + MULTICAST_GROUP + " via " + netIf.getName());

            byte[] buffer = new byte[4096];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(packet.getData()));
                GameMessage msg = (GameMessage) ois.readObject();

                if (msg.tcpPort == myTcpPort) continue;
                
                switch (msg.type) {
                    case JOIN_REQUEST:
                        System.out.println("[UDP] Found peer: " + msg.senderAddress + ":" + msg.tcpPort);
                        connectionManager.connectToPeer(msg.senderAddress, msg.tcpPort);
                        break;

                    case ORDERED_MULTICAST:
                        // This is a game move (e.g., "Bet 20, Seq #5")
                        // We must NOT process it yet. We give it to the queue.
                        if (holdBackQueue != null) {
                            holdBackQueue.addMessage(msg); 
                        }
                        break;
                        
                    default:
                        break;
                }
            }
            
            socket.leaveGroup(groupAddress, netIf);
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    // TODO: understand this better before evaluation; has some mac specific stuff
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