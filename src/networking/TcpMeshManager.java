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
    private final NodeContext context; 
    private ServerSocket serverSocket;
    private boolean running = true;
    
    private ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();

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

            out.writeObject(new GameMessage(GameMessage.Type.HEARTBEAT, myPort, context.myIp, "Pulse"));
            out.flush();

            new Thread(() -> listenToPeer(in, socket, out)).start();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void listenToPeer(ObjectInputStream in, Socket socket, ObjectOutputStream out) {
        String peerId = null;
        try {
            while (running) {
                GameMessage msg = (GameMessage) in.readObject();
                
                if (peerId == null) {
                    peerId = msg.senderId;
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

    public void connectToPeer(String ip, int port) {
        String peerId = ip + ":" + port;
        if (peers.containsKey(peerId)) return;
        try {
            Socket socket = new Socket(ip, port);
            handleNewConnection(socket);
        } catch (IOException e) {
            System.out.println("[TCP] Could not connect to peer " + peerId);
        }
    }

    public void sendNack(String targetPeerId, long sequenceNumber) {
        sendToPeer(targetPeerId,
            new GameMessage(GameMessage.Type.NACK, myPort, context.myIp, String.valueOf(sequenceNumber))
        );
    }

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

    public synchronized void closeConnection(String peerId) {
        if (peerId != null && peers.containsKey(peerId)) {
            Peer p = peers.remove(peerId);
            try { p.socket.close(); } catch (Exception e) {}
        }
    }
    
    private void sendHeartbeats() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                
                GameMessage hb = new GameMessage(GameMessage.Type.HEARTBEAT, myPort, context.myIp);
                broadcastToAll(hb);
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
                        context.onPeerDisconnected(peer.peerId);
                    }
                }
            } catch (InterruptedException e) {}
        }
    }

    public boolean isPeerAlive(String peerId) {
        return peers.containsKey(peerId);
    }
    
    public long getPeerLastSeen(String peerId) {
        Peer p = peers.get(peerId);
        return (p != null) ? p.lastSeenTimestamp : 0;
    }

    public Set<String> getConnectedPeerIds() {
        return peers.keySet();
    }
}