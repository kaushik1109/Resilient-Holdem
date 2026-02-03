package game;

import java.util.*;
import networking.GameMessage;
import networking.NetworkConfig;

public class ClientGameState {
    public List<String> myHand = new ArrayList<>();
    public List<String> communityCards = new ArrayList<>();
    public String status = "Waiting for game";
    
    public void onReceiveHand(String payload) {
        myHand.clear();
        Collections.addAll(myHand, payload.split(","));
        System.out.println(">>> MY HAND: " + myHand);
    }
    
    public void onReceiveCommunity(String payload) {
        communityCards.clear();
        Collections.addAll(communityCards, payload.split(","));
        System.out.println(">>> BOARD: " + communityCards);
    }
    
    public void onReceiveState(String msg) {
        this.status = msg;
        System.out.println(">>> GAME INFO: " + msg);
    }
    
    public void printStatus(String nodeId, String leaderId) {
        System.out.println("\n>>> CURRENT STATUS");
        System.out.println(">>> NODE: " + nodeId);
        System.out.println(">>> LEADER: " + leaderId);
        if (myHand.isEmpty()) {
             System.out.println(">>> HAND: [Spectating / Folded]");
        } else {
             System.out.println(">>> HAND: " + myHand);
        }
        
        System.out.println(">>> BOARD: " + communityCards);
        System.out.println(">>> GAME STATUS:  " + status);
    }

    public static void printHelp() {
        System.out.println("\nCOMMANDS: 'start' (Leader), 'bet / raise <amt>', 'call', 'fold', 'check', 'allin', 'quit', 'dropnext', 'help'");
    }

    public static void handleUserCommands(NodeContext node) {
        String payload;
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
                                System.out.println("Only the Leader can start the game.");
                            }
                            break;

                        case "bet": case "raise":
                            if (parts.length < 2 || !parts[1].matches("\\d+")) {
                                System.out.println("Enter a number to bet / raise. eg. bet 200");
                                break;
                            }
                            
                            actionMsg = new GameMessage(GameMessage.Type.ACTION_REQUEST, node.myPort, node.myIp, cmd + " " + parts[1]);

                            if (node.election.iAmLeader) {
                                node.sequencer.multicastAction(actionMsg);
                            } else if (node.election.currentLeaderId != null) {
                                node.tcp.sendToPeer(node.election.currentLeaderId, actionMsg);
                            } else {
                                System.out.println("No Leader found yet.");
                            }
                            break;
                        
                        case "fold": case "check": case "call": case "allin":
                            if (parts.length > 1) {
                                System.out.println("Enter only the command for fold / call / check / allin");
                                break;
                            }
                            
                            actionMsg = new GameMessage(GameMessage.Type.ACTION_REQUEST, node.myPort, node.myIp, cmd);

                            if (node.election.iAmLeader) {
                                node.sequencer.multicastAction(actionMsg);
                            } else if (node.election.currentLeaderId != null) {
                                node.tcp.sendToPeer(node.election.currentLeaderId, actionMsg);
                            } else {
                                System.out.println("No Leader found yet.");
                            }
                            break;

                        case "status":
                            node.clientGame.printStatus(node.nodeId, node.election.currentLeaderId);
                            break;
                            
                        case "quit":
                            node.udp.sendMulticast(new GameMessage(GameMessage.Type.LEAVE, node.myPort, node.myIp));
                            System.exit(0);
                            break;
                        
                        case "dropnext":
                            node.dropNext = true;
                            System.out.println("The next game message will be dropped");
                            break;
                        
                        case "help":
                        case "commands":
                            printHelp();
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