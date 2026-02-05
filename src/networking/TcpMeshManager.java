package networking;

import game.NodeContext;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printNetworking;

/**
 * Manages TCP connections to peers, including connection handling, message sending, and heartbeat monitoring.
 */
public class TcpMeshManager {
    private static final int HEARTBEAT_INTERVAL = 2000;
    private static final int TIMEOUT_THRESHOLD = 6000;

    private final int myPort;
    private final NodeContext context; 
    private ServerSocket serverSocket;
    private boolean running = true;
    
    private ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();

    public TcpMeshManager(NodeContext context) {
        this.myPort = NetworkConfig.MY_PORT;
        this.context = context;
    }

    /**
     * Starts the TCP server and associated threads for heartbeats and connection monitoring.
     */
    public void start() {
        new Thread(this::startServer).start();
        new Thread(this::sendHeartbeats).start();
        new Thread(this::monitorConnections).start();
    }

    /**
     * Starts the TCP server to listen for incoming connections.
     */
    private void startServer() {
        try {
            serverSocket = new ServerSocket(myPort);
            printNetworking("[TCP] Listening on port " + myPort);
            while (running) {
                handleNewConnection(serverSocket.accept());
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    /**
     * Handles a new incoming TCP connection by performing a handshake and starting a listener thread for that peer.
     * @param socket The Socket object representing the new connection.
     */
    private void handleNewConnection(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            out.writeObject(new GameMessage(GameMessage.Type.HEARTBEAT));
            out.flush();

            new Thread(() -> listenToPeer(in, socket, out)).start();
        } catch (IOException e) { e.printStackTrace(); }
    }

    /**
     * Listens for messages from a connected peer and processes them.
     * @param in The ObjectInputStream to read messages from the peer.
     * @param socket The Socket representing the connection to the peer.
     * @param out The ObjectOutputStream to send messages to the peer.
     */
    private void listenToPeer(ObjectInputStream in, Socket socket, ObjectOutputStream out) {
        String peerId = null;
        try {
            while (running) {
                GameMessage msg = (GameMessage) in.readObject();
                
                if (msg.type != GameMessage.Type.HEARTBEAT) {
                    printNetworking("[TCP] Received message from " + msg.getSenderId() + " of type: " + msg.type);
                }

                if (peerId == null) {
                    peerId = msg.getSenderId();
                    peers.put(peerId, new Peer(peerId, socket, out));
                    context.onPeerConnected(peerId);
                }

                Peer p = peers.get(peerId);
                if (p != null) {
                    p.lastSeenTimestamp = System.currentTimeMillis();
                }
                
                context.routeMessage(msg);
            }
        } catch (Exception e) {
            context.onPeerDisconnected(peerId);
        }
    }

    /**
     * Initiates a TCP connection to a peer given its IP address and port.
     * @param ip The IP address of the peer.
     * @param port The port number of the peer.
     */
    public void connectToPeer(String ip, int port) {
        String peerId = ip + ":" + port;
        if (peers.containsKey(peerId)) return;
        if (ip.equals(NetworkConfig.MY_IP) && port == myPort) return;

        try {
            Socket socket = new Socket(ip, port);
            printNetworking("[TCP] Connecting to " + peerId);
            handleNewConnection(socket);
        } catch (IOException e) {
            printError("[TCP] Could not connect to peer " + peerId);
        }
    }

    /**
     * Sends a NACK message to a specific peer for a missing sequence number.
     * @param targetPeerId The ID of the target peer.
     * @param sequenceNumber The missing sequence number.
     */
    public void sendNack(String targetPeerId, long sequenceNumber) {
        sendToPeer(targetPeerId,
            new GameMessage(GameMessage.Type.NACK, String.valueOf(sequenceNumber))
        );
    }

    /**
     * Sends a GameMessage to a specific peer.
     * @param targetPeerId The ID of the target peer.
     * @param msg The GameMessage to be sent.
     */
    public void sendToPeer(String targetPeerId, GameMessage msg) {
        Peer peer = peers.get(targetPeerId);
        if (peer == null) {
            String[] parts = targetPeerId.split(":");
            String ip = parts[0];
            int port = Integer.parseInt(parts[1]);

            connectToPeer(ip, port);

            try { Thread.sleep(200); } catch (Exception e) {}
            peer = peers.get(targetPeerId);
        }

        try {       
            synchronized(peer.out) {
                peer.out.writeObject(msg);
                peer.out.flush();
            }
        } catch (IOException e) {
            peers.remove(targetPeerId);
            context.onPeerDisconnected(targetPeerId);
            printError("Could not send message to peer " + targetPeerId);
        }   
    }

    /**
     * Broadcasts a GameMessage to all connected peers.
     * @param msg The GameMessage to be broadcasted.
     */
    public void broadcastToAll(GameMessage msg) {
        peers.values().forEach(p -> sendToPeer(p.peerId, msg));
    }

    /**
     * Closes the connection to a specific peer.
     * @param peerId The ID of the peer whose connection is to be closed.
     */
    public synchronized void closeConnection(String peerId) {
        if (peerId != null && peers.containsKey(peerId)) {
            Peer p = peers.remove(peerId);
            try { p.socket.close(); } catch (Exception e) {}
        }
    }
   
    /**
     * Sends periodic heartbeat messages to all connected peers.
     */
    private void sendHeartbeats() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                
                GameMessage hb = new GameMessage(GameMessage.Type.HEARTBEAT);
                broadcastToAll(hb);
            } catch (InterruptedException e) {}
        }
    }

    /**
     * Monitors the last seen timestamps of peers and disconnects those that have timed out.
     */
    private void monitorConnections() {
        while (running) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();

                for (Peer peer : peers.values()) {
                    if (now - peer.lastSeenTimestamp > TIMEOUT_THRESHOLD) {
                        printError("[TCP] Peer " + peer.peerId + " timed out!");
                        context.onPeerDisconnected(peer.peerId);
                    }
                }
            } catch (InterruptedException e) {}
        }
    }

    /**
     * Checks if a peer is currently connected and alive.
     * @param peerId The ID of the peer to check.
     * @return True if the peer is connected, false otherwise.
     */
    public boolean isPeerAlive(String peerId) {
        return peers.containsKey(peerId);
    }
    
    /**
     * Retrieves the last seen timestamp of a specific peer.
     * @param peerId The ID of the peer.
     * @return The last seen timestamp in milliseconds, or 0 if the peer is not found.
     */
    public long getPeerLastSeen(String peerId) {
        Peer p = peers.get(peerId);
        return (p != null) ? p.lastSeenTimestamp : 0;
    }

    /**
     * Retrieves the set of currently connected peer IDs.
     * @return A Set of peer IDs.
     */
    public Set<String> getConnectedPeerIds() {
        return peers.keySet();
    }
}