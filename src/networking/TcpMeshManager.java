package networking;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;
import game.ClientGameState;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

public class TcpMeshManager {
    private static final int HEARTBEAT_INTERVAL = 2000; // Send every 2s
    private static final int TIMEOUT_THRESHOLD = 6000;  // Die after 6s silence

    private int myPort;
    private ServerSocket serverSocket;
    private boolean running = true;
    
    private ConcurrentHashMap<Integer, Peer> peers = new ConcurrentHashMap<>();
    
    private ElectionManager electionManager;

    private Sequencer sequencer;
    
    private HoldBackQueue holdBackQueue;
    
    private ClientGameState clientGame; 

    public TcpMeshManager(int port) {
        this.myPort = port;
    }
    
    public void setClientGame(ClientGameState game) {
        this.clientGame = game;
    }

    public void setHoldBackQueue(HoldBackQueue q) {
        this.holdBackQueue = q;
    }

    public void setElectionManager(ElectionManager em) {
        this.electionManager = em;
    }

    public void setSequencer(Sequencer s) { this.sequencer = s; }

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
                Socket clientSocket = serverSocket.accept();
                handleNewConnection(clientSocket);
            }
        } catch (IOException e) { e.printStackTrace(); }
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

    public void connectToPeer(String ip, int port) {
        System.out.println("[TCP] Connecting to Peer: " + ip + ":" + Integer.toString(port));
        if (peers.containsKey(port)) return;

        try {
            Socket socket = new Socket(ip, port);
            handleNewConnection(socket);
        } catch (IOException e) {
            System.err.println("[TCP] Failed to connect to " + ip + ":" + port);
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

            // Tell the other side who WE are right now
            GameMessage handshake = new GameMessage(
                GameMessage.Type.HEARTBEAT, // Use HEARTBEAT or create a HANDSHAKE type
                socket.getLocalAddress().getHostAddress(), 
                myPort, 
                "HANDSHAKE"
            );
            out.writeObject(handshake);
            out.flush();

            // Now start listening for their handshake
            new Thread(() -> listenToPeer(in, socket, out)).start();

        } catch (IOException e) { e.printStackTrace(); }
    }

private void listenToPeer(ObjectInputStream in, Socket socket, ObjectOutputStream out) {
        int peerId = -1;
        try {
            while (running) {
                GameMessage msg = (GameMessage) in.readObject();
                
                // 1. Registration (Same as before)
                if (peerId == -1) {
                    peerId = msg.tcpPort;
                    peers.put(peerId, new Peer(socket.getInetAddress().getHostAddress(), peerId, socket, out));
                }

                // 2. Update Heartbeat Timestamp (Same as before)
                Peer p = peers.get(peerId);
                if (p != null) p.lastSeenTimestamp = System.currentTimeMillis();

                // 3. Handle Message Types
                switch (msg.type) {
                    case HEARTBEAT:
                        break; // Timestamp already updated above
                        
                    case LEAVE:
                        System.out.println("[TCP] Peer " + peerId + " is shutting down gracefully.");
                        // We close the connection explicitly.
                        // This triggers 'handleNodeFailure' inside closeConnection()
                        closeConnection(peerId);
                        return; // Stop the thread
                        
                    case NACK:
                        if (sequencer != null) sequencer.handleNack(msg, peerId);
                        break;
                        
                    case ORDERED_MULTICAST:
                    case PLAYER_ACTION: // Handle both just in case
                        if (holdBackQueue != null) holdBackQueue.addMessage(msg);
                        break;

                    case YOUR_HAND:
                        // "Psst, here are your cards" (TCP Private Message)
                        if (clientGame != null) {
                            clientGame.onReceiveHand(msg.payload);
                        }
                        break;

                    case ACTION_REQUEST:
                        if (sequencer != null) {
                            // Success! We are the Leader, so we order the message.
                            System.out.println("[TCP] Received Action Request: " + msg.payload);
                            sequencer.multicastAction(msg);
                        } else {
                            // Failure! We received a request but we aren't the Leader/Sequencer.
                            System.err.println("[TCP] Received ACTION_REQUEST but I am not the Sequencer!");
                        }
                        break;
                    // Forward Election/Coordinator messages
                    case ELECTION:
                    case ELECTION_OK:
                    case COORDINATOR:
                        if (electionManager != null) electionManager.handleMessage(msg);
                        break;
                        
                    default:
                        System.out.println("[TCP] IGNORED Unknown Message Type: " + msg.type);
                        break;
                }
            }
        } catch (Exception e) {
            // This catches "Connection Reset" (Hard Crash)
            closeConnection(peerId);
        }
    }

    public void broadcastToAll(GameMessage msg) {
        for (Peer peer : peers.values()) {
            try {
                peer.out.writeObject(msg);
                peer.out.flush();
            } catch (IOException e) {
                System.out.println("Failed to send to Node " + peer.peerId);
            }
        }
    }
    
    public void sendToPeer(int targetPeerId, GameMessage msg) {
        Peer peer = peers.get(targetPeerId);

        if (peer == null) {
            connectToPeer("localhost", targetPeerId); 
            try { Thread.sleep(500); } catch(Exception e){} // Increased wait to 500ms
            peer = peers.get(targetPeerId);
        }

        if (peer != null) {
            try {
                synchronized(peer.out) {
                    peer.out.writeObject(msg);
                    peer.out.flush();
                }
            } catch (IOException e) {
                System.err.println("[TCP ERROR] Write failed: " + e.getMessage());
                peers.remove(targetPeerId);
            }
        } else {
            System.err.println("[TCP ERROR] Connection failed. Peer " + targetPeerId + " is not in map.");
        }
    }

    public Set<Integer> getConnectedPeerIds() {
        return peers.keySet();
    }

    private synchronized void closeConnection(int peerId) {
        if (peerId == -1 || !peers.containsKey(peerId)) return;
        
        System.out.println("[TCP] Closing connection to Node " + peerId);
        Peer p = peers.remove(peerId);
        try { p.socket.close(); } catch (Exception e) {}

        // CRITICAL: Notify ElectionManager if a node dies!
        if (electionManager != null) {
            electionManager.handleNodeFailure(peerId);
        }
    }
}