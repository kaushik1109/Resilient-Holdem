package game;

import java.util.*;
import networking.GameMessage;
import networking.NetworkConfig;

import static util.ConsolePrint.printNormal;
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
    public PokerTable table = new PokerTable();
    int myChips = 1000;
    
    public void onReceiveHand(String payload) {
        myHand.clear();
        Collections.addAll(myHand, payload.split(","));
        printNormal("My Hand: " + myHand);
    }
    
    public void onReceiveCommunity(String payload) {
        communityCards.clear();
        Collections.addAll(communityCards, payload.split(","));
        printNormal("Board: " + communityCards);
    }

    public void onReceiveState(String payload) {
        table = PokerTable.deserializeState(payload);

        myChips = table.players.stream()
            .filter(player -> player.id.equals(NetworkConfig.myId()))
            .map(player -> player.chips)
            .findFirst()
            .orElse(1000);
    }
    
    public void onReceiveInfo(String msg) {
        this.status = msg;
        printNormal(msg);
    }
    
    public void printStatus(String nodeId, String leaderId) {
        printBold("\nGame Status");
        printNormal("My ID: " + nodeId);
        printNormal("Leader: " + leaderId);

        if (myHand.isEmpty()) {
            printNormal("My Hand: [Spectating / Folded]");
        } else {
            printNormal("My Hand: " + myHand);
            printChips();
        }
        
        printNormal("Board: " + communityCards);
        printNormal("Status: " + status);
        printPlayerRoster(table);
    }

    private void printPlayerRoster(PokerTable table) {
        if (table == null) {
            printError("No game in progress.");
            return;
        }

        if (table.players.size() < 1) {
            printNormal("Player list currently unavailable");
            return;
        }

        printBold("Current Players:");
        for (Player player : table.players) {
            printNormal(player.name + ": Chips = " + player.chips + ", Bet = " + player.currentBet + ", Folded = " + player.folded);
        }
    }

    private void printChips() {
        printNormal("My Chips: " + myChips);
    }

    public static void printHelp() {
        List<String> commands = Arrays.asList(
            "start - Start the game (Leader only)",
            "add <amt> - Add chips to your stack",
            "chips - Show your current chip count",
            "bet / raise <amt> - Bet or raise by a certain amount",
            "call - Call the current bet",
            "fold - Fold your hand",
            "check - Check (if no bet to call)",
            "allin - Go all-in with your remaining chips",
            "status - Print current game status",
            "dropnext - Drop the next incoming game message (for testing)",
            "players - Show current player roster and statuses",
            "help / commands - Show this help message",
            "quit - Leave the game"
        );
        printBold("\nAvailable Commands:");
        commands.forEach(cmd -> printNormal(cmd));
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

                        case "bet": case "raise": case "pay":
                            if (parts.length < 2 || !parts[1].matches("\\d+")) {
                                printError( "Enter a number to bet / raise / pay. eg. bet 200");
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

                        case "chips":
                            node.clientGame.printChips();
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

                        case "players":
                            node.clientGame.printPlayerRoster(node.getServerGame() != null ? node.getServerGame().table : node.clientGame.table);
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