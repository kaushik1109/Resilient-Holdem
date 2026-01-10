import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import networking.GameMessage;
import consensus.ElectionManager;
import consensus.Sequencer;
import consensus.HoldBackQueue;
import game.TexasHoldem;
import game.ClientGameState;

import java.util.Scanner;
import java.util.Random;

public class Main {
    // The "Server" Logic (Only non-null if I am Leader)
    private static TexasHoldem serverGame;

    public static void main(String[] args) {
        // 1. Identity
        int myPort = 5000 + new Random().nextInt(1000);
        System.out.println("--- Node Started (ID: " + myPort + ") ---");

        // 2. Create Layers
        TcpMeshManager tcpLayer = new TcpMeshManager(myPort);
        UdpMulticastManager udpLayer = new UdpMulticastManager(myPort, tcpLayer);
        
        ElectionManager election = new ElectionManager(myPort, tcpLayer);
        Sequencer sequencer = new Sequencer(udpLayer);
        HoldBackQueue queue = new HoldBackQueue();
        
        // 3. Create Game Views
        ClientGameState clientGame = new ClientGameState(); // My View

        // 4. Wire Dependencies (The "Spaghetti" Cleanup)
        
        // Networking <-> Consensus
        tcpLayer.setElectionManager(election);
        tcpLayer.setSequencer(sequencer);
        tcpLayer.setHoldBackQueue(queue); // For incoming repairs
        
        udpLayer.setHoldBackQueue(queue); // For incoming moves
        
        // Consensus <-> Networking
        sequencer.setTcpLayer(tcpLayer);
        sequencer.setLocalQueue(queue);   // Short-circuit
        
        queue.setTcpLayer(tcpLayer);      // For sending NACKs
        
        // Networking <-> Game Logic (NEW)
        tcpLayer.setClientGame(clientGame);
        queue.setClientGame(clientGame);
        queue.setCallback(msg -> {
        if (election.iAmLeader && serverGame != null) {
            if (msg.type == GameMessage.Type.PLAYER_ACTION) {
                serverGame.processAction(msg.tcpPort, msg.payload);
            }
        }
    });

        tcpLayer.setRequestHandler(msg -> {
            // Only the Leader processes requests
            if (election.iAmLeader && serverGame != null) {
                serverGame.handleClientRequest(msg);
            } else {
                System.out.println("Received Request but I am not Leader/Ready.");
            }
        });

        // 5. Start System
        tcpLayer.start();
        udpLayer.start();
        election.startStabilizationPeriod();

        // 6. Background Thread: Manage "Server" Role
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    // Update Queue with current Leader ID (for NACKs)
                    queue.setLeaderId(election.currentLeaderId);

                    // If I am Leader, ensure Server Logic exists
                    if (election.iAmLeader) {
                        if (serverGame == null) {
                            System.out.println(">>> I AM THE DEALER <<<");
                            serverGame = new TexasHoldem(myPort, tcpLayer, sequencer, election, queue);
                            // Auto-add existing peers
                            for (int peerId : tcpLayer.getConnectedPeerIds()) {
                                serverGame.addPlayer(peerId);
                            }
                        }

                        for (int peerId : tcpLayer.getConnectedPeerIds()) {
                            serverGame.addPlayer(peerId); // This now triggers the Welcome Package!
                        }
                    } else {
                        serverGame = null; // Save memory
                    }
                } catch (Exception e) {}
            }
        }).start();

        // 7. COMMAND LOOP (User Input)
        handleUserCommands(myPort, tcpLayer, sequencer, election);
    }

    private static void handleUserCommands(int myPort, TcpMeshManager tcp, Sequencer sequencer, ElectionManager election) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\nCOMMANDS: 'start' (Leader), 'bet <amt>', 'fold', 'check', 'allin'");
        
        while (true) {
            String line = scanner.nextLine().trim(); // FIX 1: Remove spaces
            if (line.isEmpty()) continue;            // FIX 2: Ignore empty Enters

            String[] parts = line.split("\\s+");     // FIX 3: Handle multiple spaces
            String cmd = parts[0].toLowerCase();

            try {
                switch (cmd) {
                    case "start":
                        if (serverGame != null) {
                            serverGame.startNewRound();
                        } else {
                            System.out.println("Only the Leader can start the game.");
                        }
                        break;

                    case "bet":
                    case "fold":
                    case "check":
                    case "call":
                    case "allin":
                        // Construct Action Payload
                        String payload = cmd;
                        if (parts.length > 1) payload += " " + parts[1]; // e.g., "bet 20"
                        
                        GameMessage actionMsg = new GameMessage(
                            GameMessage.Type.ACTION_REQUEST, 
                            "local", 
                            myPort, 
                            payload
                        );

                        // Send to Leader (or Short-circuit if WE are Leader)
                        if (election.iAmLeader) {
                            sequencer.multicastAction(actionMsg);
                        } else if (election.currentLeaderId != -1) {
                            tcp.sendToPeer(election.currentLeaderId, actionMsg);
                        } else {
                            System.out.println("No Leader found yet.");
                        }
                        break;

                    case "status":
                        System.out.println("Leader: " + election.currentLeaderId + " | My ID: " + myPort);
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