package networking;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ConnectionManager {
    private int myPort;
    private ServerSocket serverSocket;
    private boolean running = true;
    private ConcurrentHashMap<String, ObjectOutputStream> peers = new ConcurrentHashMap<>();

    public ConnectionManager(int port) {
        this.myPort = port;
    }

    public void start() {
        new Thread(this::startServer).start();
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(myPort);
            System.out.println("[TCP] Server listening on port " + myPort);
            while (running) {
                Socket clientSocket = serverSocket.accept();
                handleNewConnection(clientSocket, true);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public void connectToPeer(String ip, int port) {
        String peerId = ip + ":" + port;
        
        // Only skip if we are ALREADY connected
        if (peers.containsKey(peerId)) return;

        // we can maybe add a port check to prevent double simultaneous connections but then the UDP broadcasts
        // need to be repeated, which we can implement later, not now

        try {
            System.out.println("[TCP] Connecting to " + peerId);
            Socket socket = new Socket(ip, port);
            handleNewConnection(socket, false);
        } catch (IOException e) {
            System.err.println("[TCP] Failed to connect to " + peerId);
        }
    }

    private void handleNewConnection(Socket socket, boolean isIncoming) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            
            String peerId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            peers.put(peerId, out);
            
            System.out.println("[TCP] Connected: " + peerId + (isIncoming ? " (Incoming)" : " (Outgoing)"));
            
            new Thread(() -> listenToPeer(in, peerId)).start();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void listenToPeer(ObjectInputStream in, String peerId) {
        try {
            while (running) {
                GameMessage msg = (GameMessage) in.readObject();
                System.out.println("[TCP] Received " + msg.type + " from " + peerId);
            }
        } catch (Exception e) {
            System.out.println("[TCP] Peer disconnected: " + peerId);
            peers.remove(peerId);
        }
    }
}