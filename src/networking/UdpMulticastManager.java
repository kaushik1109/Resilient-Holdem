package networking;

import java.io.*;
import java.net.*;
import java.util.Enumeration;

import game.NodeContext;

public class UdpMulticastManager {
    private static final String MULTICAST_GROUP = NetworkConfig.MULTICAST_GROUP;
    private static final int MULTICAST_PORT = NetworkConfig.MULTICAST_PORT;
    
    private final int myTcpPort;
    private final NodeContext context;
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
                
                String senderIp = packet.getAddress().getHostAddress();
                //System.out.println("[UDP DEBUG] Received packet from " + senderIp + ":" + msg.tcpPort + " | Type: " + msg.type);

                if (msg.senderId != null &&
                    msg.senderId.equals(context.nodeId) &&
                    msg.sequenceNumber <= 0) {
                        continue;
                }

                if (msg.type == GameMessage.Type.JOIN_REQUEST) {
                    context.tcp.connectToPeer(senderIp, msg.tcpPort);
                } else {
                    context.routeMessage(msg);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void broadcastJoinRequest() {
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(NetworkConfig.MULTICAST_TTL);
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            GameMessage msg = new GameMessage(GameMessage.Type.JOIN_REQUEST, myTcpPort, context.myIp);

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
        NetworkInterface bestCandidate = null;

        for (NetworkInterface netIf : java.util.Collections.list(nets)) {
            if (!netIf.isUp()) continue;
            if (!netIf.supportsMulticast()) continue;
            if (netIf.isLoopback()) continue;

            String name = netIf.getName().toLowerCase();
            String displayName = netIf.getDisplayName().toLowerCase();

            if (displayName.contains("vmware") || name.contains("vmnet")) continue;
            if (displayName.contains("virtual") || name.contains("vbox")) continue;
            if (displayName.contains("pseudo") || name.contains("tunnel")) continue;
            if (displayName.contains("wsl") || displayName.contains("hyper-v")) continue;

            boolean hasIpv4 = netIf.getInterfaceAddresses().stream().anyMatch(addr -> addr.getAddress() instanceof Inet4Address);
            
            if (!hasIpv4) continue;

            if (displayName.contains("wi-fi") || displayName.contains("wlan") || name.startsWith("en")) {
                System.out.println("[UDP] Selected High-Priority Interface: " + displayName);
                return netIf;
            }
            
            if (bestCandidate == null) {
                bestCandidate = netIf;
            }
        }
        
        if (bestCandidate != null) {
             System.out.println("[UDP] Selected Backup Interface: " + bestCandidate.getDisplayName());
             return bestCandidate;
        }
        
        System.out.println("[UDP] No valid WiFi/Ethernet found. Falling back to Loopback.");
        return NetworkInterface.getByName("127.0.0.1");
    }

    public void sendMulticast(GameMessage msg) {
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(NetworkConfig.MULTICAST_TTL);
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
}