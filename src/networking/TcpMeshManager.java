package networking;

import game.NodeContext;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

public class TcpMeshManager {
    private static final int HEARTBEAT_INTERVAL = 2000;
    private static final int TIMEOUT_THRESHOLD = 6000;

    private final int myPort;
    private final NodeContext context; // Reference to the Router
    private ServerSocket serverSocket;
    private boolean running = true;
    
    private ConcurrentHashMap<Integer, Peer> peers = new ConcurrentHashMap<>();

    // NEW Constructor takes Context
    public TcpMeshManager(int port, NodeContext context) {
        this.myPort = port;
        this.context = context;
    }

    public void start() {
        new Thread(this::startServer).start();
        new Thread(this::sendHeartbeats).start();
        new Thread(this::monitorConnections).start();
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(myPort);
            System.out.println("[TCP] Server listening on port " + myPort);
            while (running) {
                handleNewConnection(serverSocket.accept());
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void handleNewConnection(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            // Handshake
            GameMessage handshake = new GameMessage(
                GameMessage.Type.HEARTBEAT, socket.getLocalAddress().getHostAddress(), myPort, "HANDSHAKE"
            );
            out.writeObject(handshake);
            out.flush();

            new Thread(() -> listenToPeer(in, socket, out)).start();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void listenToPeer(ObjectInputStream in, Socket socket, ObjectOutputStream out) {
        int peerId = -1;
        try {
            while (running) {
                GameMessage msg = (GameMessage) in.readObject();
                
                if (peerId == -1) {
                    peerId = msg.tcpPort;
                    peers.put(peerId, new Peer(socket.getInetAddress().getHostAddress(), peerId, socket, out));
                }

                Peer p = peers.get(peerId);
                if (p != null) {
                    p.lastSeenTimestamp = System.currentTimeMillis();
                }
                
                context.routeMessage(msg);
            }
        } catch (Exception e) {
            closeConnection(peerId);
        }
    }

    public void connectToPeer(String ip, int port) {
        if (peers.containsKey(port)) return;
        try {
            Socket socket = new Socket(ip, port);
            handleNewConnection(socket);
        } catch (IOException e) { /* Log error */ }
    }

    public void sendToPeer(int targetPeerId, GameMessage msg) {
        Peer peer = peers.get(targetPeerId);
        if (peer == null) {
            // Auto-reconnect logic (simplified)
            connectToPeer("localhost", targetPeerId);
            try { Thread.sleep(200); } catch(Exception e){}
            peer = peers.get(targetPeerId);
        }

        if (peer != null) {
            try {
                synchronized(peer.out) {
                    peer.out.writeObject(msg);
                    peer.out.flush();
                }
            } catch (IOException e) {
                peers.remove(targetPeerId);
            }
        }
    }

    public void broadcastToAll(GameMessage msg) {
        peers.values().forEach(p -> sendToPeer(p.peerId, msg));
    }

    public synchronized void closeConnection(int peerId) {
        if (peerId != -1 && peers.containsKey(peerId)) {
            Peer p = peers.remove(peerId);
            try { p.socket.close(); } catch (Exception e) {}
            // Notify Election Manager via Router logic (or direct call if exposed)
            context.election.handleNodeFailure(peerId);
        }
    }
    
    private void sendHeartbeats() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                // Send a lightweight message to everyone
                GameMessage hb = new GameMessage(
                    GameMessage.Type.HEARTBEAT, "local", myPort, "Pulse"
                );
                broadcastToAll(hb); // Re-use your existing broadcast method
            } catch (InterruptedException e) {}
        }
    }

    private void monitorConnections() {
        while (running) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();

                for (Peer peer : peers.values()) {
                    if (now - peer.lastSeenTimestamp > TIMEOUT_THRESHOLD) {
                        System.err.println("[Heartbeat] Peer " + peer.peerId + " timed out!");
                        closeConnection(peer.peerId); // Kill the connection
                    }
                }
            } catch (InterruptedException e) {}
        }
    }
    
    // ElectionManager needs to ask: "Is the Leader dead?"
    public boolean isPeerAlive(int peerId) {
        return peers.containsKey(peerId);
    }
    
    // OR allow ElectionManager to read the timestamp directly if you prefer strict timeouts
    public long getPeerLastSeen(int peerId) {
        Peer p = peers.get(peerId);
        return (p != null) ? p.lastSeenTimestamp : 0;
    }

    public Set<Integer> getConnectedPeerIds() {
        return peers.keySet();
    }
}