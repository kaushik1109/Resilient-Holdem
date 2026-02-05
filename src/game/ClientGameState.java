package game;

import java.util.*;
import networking.GameMessage;
import static util.ConsolePrint.printError;
import static util.ConsolePrint.printBold;

/**
 * Manages the client's view of the game state, including the player's hand,
 * community cards, and game status messages.
 */
public class ClientGameState {
    public List<String> myHand = new ArrayList<>();
    public List<String> communityCards = new ArrayList<>();
    public String status = "Waiting for game";
    
    public void onReceiveHand(String payload) {
        myHand.clear();
        Collections.addAll(myHand, payload.split(","));
        System.out.println("My Hand: " + myHand);
    }
    
    public void onReceiveCommunity(String payload) {
        communityCards.clear();
        Collections.addAll(communityCards, payload.split(","));
        System.out.println("Board: " + communityCards);
    }
    
    public void onReceiveState(String msg) {
        this.status = msg;
        System.out.println(msg);
    }
    
    public void printStatus(String nodeId, String leaderId) {
        System.out.println("STATUS");
        System.out.println("My ID: " + nodeId);
        System.out.println("Leader: " + leaderId);
        if (myHand.isEmpty()) {
             System.out.println("My Hand: [Spectating / Folded]");
        } else {
             System.out.println("My Hand: " + myHand);
        }
        
        System.out.println("Board: " + communityCards);
        System.out.println("Status: " + status);
    }

    public static void printHelp() {
        printBold("\nCommands: 'start' (Leader), 'bet / raise <amt>', 'call', 'fold', 'check', 'allin', 'quit', 'dropnext', 'help'");
    }

    /**
     * Handles user input commands from the console and translates them into game actions.
     * @param node The NodeContext of the current node.
     */
    public static void handleUserCommands(NodeContext node) {
        GameMessage actionMsg;

        try (Scanner scanner = new Scanner(System.in)) {
            printHelp();
            
            while (true) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "start":
                            if (node.election.iAmLeader) {
                                if (node.getServerGame() == null) {
                                    node.createServerGame();
                                }

                                node.getServerGame().startNewRound();
                            } else {
                                printError( "Only the Leader can start the game.");
                            }
                            break;

                        case "bet": case "raise":
                            if (parts.length < 2 || !parts[1].matches("\\d+")) {
                                printError( "Enter a number to bet / raise. eg. bet 200");
                                break;
                            }
                            
                            actionMsg = new GameMessage(GameMessage.Type.ACTION_REQUEST, cmd + " " + parts[1]);

                            if (node.election.iAmLeader) {
                                node.sequencer.multicastAction(actionMsg);
                            } else if (node.election.currentLeaderId != null) {
                                node.tcp.sendToPeer(node.election.currentLeaderId, actionMsg);
                            } else {
                                printError( "No Leader found yet.");
                            }
                            break;
                        
                        case "fold": case "check": case "call": case "allin":
                            if (parts.length > 1) {
                                printError( "Enter only the command for fold / call / check / allin");
                                break;
                            }
                            
                            actionMsg = new GameMessage(GameMessage.Type.ACTION_REQUEST, cmd);

                            if (node.election.iAmLeader) {
                                node.sequencer.multicastAction(actionMsg);
                            } else if (node.election.currentLeaderId != null) {
                                node.tcp.sendToPeer(node.election.currentLeaderId, actionMsg);
                            } else {
                                printError( "No Leader found yet.");
                            }
                            break;

                        case "status":
                            node.clientGame.printStatus(node.myId, node.election.currentLeaderId);
                            break;
                            
                        case "quit":
                            node.udp.sendMulticast(new GameMessage(GameMessage.Type.LEAVE));
                            System.exit(0);
                            break;
                        
                        case "dropnext":
                            node.dropNext = true;
                            printError("The next game message will be dropped.");
                            break;
                        
                        case "help":
                        case "commands":
                            printHelp();
                            break;

                        default:
                            printError( "Unknown command.");
                    }
                } catch (Exception e) {
                    printError( "Error processing command: " + e.getMessage());
                }
            }
        }
    }
}