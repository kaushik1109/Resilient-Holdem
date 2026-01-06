package networking;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

public class TcpMeshManager {
    private int myPort;
    private ServerSocket serverSocket;
    private boolean running = true;
    
    private ConcurrentHashMap<Integer, Peer> peers = new ConcurrentHashMap<>();
    
    private ElectionManager electionManager;

    private Sequencer sequencer;
    
    private HoldBackQueue holdBackQueue;

    public TcpMeshManager(int port) {
        this.myPort = port;
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
                
                // Registration: If we don't know who this is yet, save them now
                if (peerId == -1) {
                    peerId = msg.tcpPort;
                    peers.put(peerId, new Peer(socket.getInetAddress().getHostAddress(), peerId, socket, out));
                    System.out.println("[TCP] Connection Established with Node ID: " + peerId);
                    
                    // If the first message was just a handshake, we can skip processing it further
                    if ("HANDSHAKE".equals(msg.payload)) continue; 
                }

                if (msg.type == GameMessage.Type.ACTION_REQUEST) {
                    if (sequencer != null) {
                        sequencer.multicastAction(msg);
                    }
                }
                
                if (msg.type == GameMessage.Type.NACK) {
                    if (sequencer != null) {
                        sequencer.handleNack(msg, peerId);
                    }
                }
                
                if (msg.type == GameMessage.Type.ORDERED_MULTICAST) {
                    // THIS IS THE FIX:
                    // We received a re-transmission via TCP. Treat it exactly like a UDP packet.
                    if (holdBackQueue != null) {
                        System.out.println("[TCP] Received Retransmission #" + msg.sequenceNumber);
                        holdBackQueue.addMessage(msg);
                    }
                }

                // Forward normal messages to the ElectionManager
                if (electionManager != null) {
                    electionManager.handleMessage(msg);
                }
            }
        } catch (Exception e) {
            System.out.println("[TCP] Node " + peerId + " disconnected.");
            if (peerId != -1) peers.remove(peerId);
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
        if (peer != null) {
            try {
                peer.out.writeObject(msg);
                peer.out.flush();
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public Set<Integer> getConnectedPeerIds() {
        return peers.keySet();
    }
}