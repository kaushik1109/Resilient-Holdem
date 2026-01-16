import game.NodeContext;
import networking.GameMessage;
import java.util.Scanner;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        // 1. Identity
        int myPort = 5000 + new Random().nextInt(1000);
        System.out.println("--- Node Started (ID: " + myPort + ") ---");

        // 2. Initialize System (Wiring happens inside here)
        NodeContext node = new NodeContext(myPort);
        
        // 3. Start System
        node.start();

        // 4. Background Thread: Manage "Server" Role
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    // Update Queue with current Leader ID (for NACKs)
                    node.queue.setLeaderId(node.election.currentLeaderId);

                    // If I am Leader, ensure Server Logic exists
                    if (node.election.iAmLeader) {
                        if (node.getServerGame() == null) {
                            System.out.println(">>> I AM THE DEALER <<<");
                            node.createServerGame();
                            
                            // Auto-add existing peers
                            for (int peerId : node.tcp.getConnectedPeerIds()) {
                                node.getServerGame().addPlayer(peerId);
                            }
                        } else {
                            // Check for new peers periodically
                             for (int peerId : node.tcp.getConnectedPeerIds()) {
                                node.getServerGame().addPlayer(peerId);
                            }
                        }
                    } else {
                        node.destroyServerGame();
                    }
                } catch (Exception e) {}
            }
        }).start();

        // 5. COMMAND LOOP
        handleUserCommands(node);
    }

    private static void handleUserCommands(NodeContext node) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\nCOMMANDS: 'start' (Leader), 'bet <amt>', 'fold', 'check', 'allin'");
        
        while (true) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "start":
                        if (node.getServerGame() != null) {
                            node.getServerGame().startNewRound();
                        } else {
                            System.out.println("Only the Leader can start the game.");
                        }
                        break;

                    case "bet": case "fold": case "check": case "call": case "allin":
                        String payload = cmd;
                        if (parts.length > 1) payload += " " + parts[1];
                        
                        GameMessage actionMsg = new GameMessage(
                            GameMessage.Type.ACTION_REQUEST, "local", node.myPort, payload
                        );

                        if (node.election.iAmLeader) {
                            node.sequencer.multicastAction(actionMsg);
                        } else if (node.election.currentLeaderId != -1) {
                            node.tcp.sendToPeer(node.election.currentLeaderId, actionMsg);
                        } else {
                            System.out.println("No Leader found yet.");
                        }
                        break;

                    case "status":
                        System.out.println("Leader: " + node.election.currentLeaderId + " | My ID: " + node.myPort);
                        break;
                        
                    case "quit":
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Unknown command.");
                }
            } catch (Exception e) {
                System.out.println("Error processing command: " + e.getMessage());
            }
        }
    }
}