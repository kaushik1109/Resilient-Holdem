package poker.node;

import java.io.IOException;
import java.net.ServerSocket;

public class Node {

    private final String nodeId;
    private final int port;

    public Node(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
    }

    public void start() throws IOException {
        System.out.println("Node " + nodeId + " starting on port " + port);
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Node " + nodeId + " listening...");
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Node <nodeId> <port>");
            return;
        }

        String nodeId = args[0];
        int port = Integer.parseInt(args[1]);

        try {
            Node node = new Node(nodeId, port);
            node.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
