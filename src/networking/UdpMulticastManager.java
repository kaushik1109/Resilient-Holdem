package networking;

import java.io.*;
import java.net.*;

import game.NodeContext;

import static util.ConsolePrint.printNetworking;

public class UdpMulticastManager {
    private static final String MULTICAST_GROUP = NetworkConfig.MULTICAST_GROUP;
    private static final int MULTICAST_PORT = NetworkConfig.MULTICAST_PORT;
    
    private final NodeContext context;
    private boolean running = true;

    public UdpMulticastManager(NodeContext context) {
        this.context = context;
    }

    public void start() {
        new Thread(this::listenForBroadcasts).start();
        new Thread(this::broadcastJoinRequest).start();
    }

    private void listenForBroadcasts() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            InetSocketAddress groupAddress = new InetSocketAddress(group, MULTICAST_PORT);
            
            NetworkInterface netIf = NetworkConfig.MY_INTERFACE;
            socket.joinGroup(groupAddress, netIf);
            
            byte[] buffer = new byte[4096];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(packet.getData()));
                GameMessage msg = (GameMessage) ois.readObject();
                
                printNetworking("[UDP] Received message from " + msg.getSenderId() + " of type: " + msg.type);

                if (msg.type == GameMessage.Type.JOIN_REQUEST) {
                    context.tcp.connectToPeer(msg.senderIp, msg.senderPort);
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
            GameMessage msg = new GameMessage(GameMessage.Type.JOIN_REQUEST);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(msg);
            byte[] data = baos.toByteArray();

            DatagramPacket packet = new DatagramPacket(data, data.length, group, MULTICAST_PORT);
            socket.send(packet);
            printNetworking("[UDP] Broadcasted JOIN_REQUEST.");
        } catch (Exception e) {
            e.printStackTrace();
        }
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
            
            printNetworking("[UDP] Sent Multicast: " + msg.type + " (Seq: " + msg.sequenceNumber + ")");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}