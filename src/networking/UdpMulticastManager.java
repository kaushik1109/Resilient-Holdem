package networking;

import java.io.*;
import java.net.*;

import game.NodeContext;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printNetworking;

/**
 * Manages UDP multicast for peer discovery and group messaging.
 */
public class UdpMulticastManager {
    private static final String MULTICAST_GROUP = NetworkConfig.MULTICAST_GROUP;
    private static final int MULTICAST_PORT = NetworkConfig.MULTICAST_PORT;
    
    private final NodeContext node;
    private boolean running = true;

    public UdpMulticastManager(NodeContext node) {
        this.node = node;
    }

    public void start() {
        new Thread(this::listen).start();
        new Thread(this::multicastJoinRequest).start();
    }

    /**
     * Listens for incoming multicast messages and processes them.
     */
    private void listen() {
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
                    node.tcp.connectToPeer(msg.getSenderId());
                } else {
                    node.routeMessage(msg);
                }
            }
        } catch (Exception e) {
            printError("[UDP] " + e.getMessage());
            //e.printStackTrace(); 
        }
    }

    /**
     * Multicasts a JOIN_REQUEST message to the multicast group.
    */
    public void multicastJoinRequest() {
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
            printNetworking("[UDP] Multicasted JOIN_REQUEST.");
        } catch (Exception e) {
            printError("[UDP] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sends a multicast message to all peers in the group.
     * @param msg The GameMessage to be sent to the group.
     */
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
            printError("[UDP] " + e.getMessage());
            //e.printStackTrace();
        }
    }
}