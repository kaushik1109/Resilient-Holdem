import game.NodeContext;
import networking.GameMessage;
import java.util.Scanner;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        int myPort = 5000 + new Random().nextInt(1000);
        NodeContext node = new NodeContext(myPort);
        node.start();

        new Thread(() -> {
            try { Thread.sleep(5000); } catch (Exception e) {}
            if (node.election.iAmLeader && node.getServerGame() == null) {
                node.createServerGame();
            }
        }).start();

        handleUserCommands(node);
    }

    private static void handleUserCommands(NodeContext node) {
        try (Scanner scanner = new Scanner(System.in)) {
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
                                GameMessage.Type.ACTION_REQUEST, node.myPort, payload
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
                            node.clientGame.printStatus();
                            break;
                            
                        case "quit":
                            node.udp.sendMulticast(new GameMessage(GameMessage.Type.LEAVE, node.myPort));
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
}