import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import networking.GameMessage;
import consensus.ElectionManager;
import consensus.Sequencer;
import consensus.HoldBackQueue;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        int myTcpPort = 5000 + new Random().nextInt(1000);
        System.out.println("--- Resilient Hold'em Node Started (ID: " + myTcpPort + ") ---");

        // 1. Initialize Layers
        TcpMeshManager tcpLayer = new TcpMeshManager(myTcpPort);
        UdpMulticastManager udpLayer = new UdpMulticastManager(myTcpPort, tcpLayer);

        // 2. Initialize Consensus
        ElectionManager election = new ElectionManager(myTcpPort, tcpLayer);
        Sequencer sequencer = new Sequencer(udpLayer);
        HoldBackQueue queue = new HoldBackQueue();

        // 3. Wire Dependencies
        tcpLayer.setHoldBackQueue(queue);
        sequencer.setTcpLayer(tcpLayer);
        sequencer.setLocalQueue(queue);
        tcpLayer.setElectionManager(election);
        tcpLayer.setSequencer(sequencer);
        udpLayer.setHoldBackQueue(queue);

        // 4. Start Threads
        tcpLayer.start();
        udpLayer.start();
        election.startStabilizationPeriod();

        // 5. START SIMULATION (The new part)
        // Waits for leader, then occasionally sends a move.
        new Thread(() -> startSimulation(myTcpPort, election, tcpLayer, sequencer)).start();

        // Keep main thread alive
        while(true) { try { Thread.sleep(1000); } catch (Exception e){} }
    }

    private static void startSimulation(int myPort, ElectionManager election, TcpMeshManager tcp, Sequencer sequencer) {
        Random rand = new Random();
        String[] actions = {"Bet 10", "Fold", "Check", "Call", "All-In"};

        try {
            // Wait 10s for election to settle
            Thread.sleep(10000);
            System.out.println("\n--- SIMULATION STARTED: Sending Random Moves ---\n");

            while (true) {
                // Wait a random time (3-8 seconds) so nodes don't spam all at once
                Thread.sleep(3000 + rand.nextInt(5000));

                int leaderId = election.currentLeaderId;
                if (leaderId == -1) {
                    System.out.println("[Sim] No Leader yet. Waiting...");
                    continue;
                }

                // Pick a random move
                String move = actions[rand.nextInt(actions.length)];
                String payload = "Player " + myPort + ": " + move;

                // Create the request
                GameMessage request = new GameMessage(
                    GameMessage.Type.ACTION_REQUEST, 
                    "localhost", 
                    myPort, 
                    payload
                );

                // LOGIC: specific path depending on if I am Leader or not
                if (election.iAmLeader) {
                    System.out.println("[Sim] I am Leader. Multicasting directly.");
                    sequencer.multicastAction(request);
                } else {
                    System.out.println("[Sim] Sending Request to Leader (" + leaderId + ")");
                    tcp.sendToPeer(leaderId, request);
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}