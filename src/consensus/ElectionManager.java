package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ElectionManager {
    private int myId;
    private TcpMeshManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    public volatile boolean iAmLeader = false; 
    public int currentLeaderId = -1;

    public ElectionManager(int myId, TcpMeshManager connectionManager) {
        this.myId = myId;
        this.connectionManager = connectionManager;
    }

    public void startStabilizationPeriod() {
        new Thread(() -> {
            System.out.println("[Election] Listening for authoritative Leader signals...");
            currentLeaderId = -1; // Reset to unknown
            
            try {
                Thread.sleep(3000); 
            } catch (InterruptedException e) { }

            if (currentLeaderId != -1) {
                System.out.println("[Election] Respecting existing Leader: " + currentLeaderId);
            } else {
                System.out.println("[Election] No Leader found. Starting Election...");
                startElection("Startup");
            }
        }).start();
    }

    public void passLeadership(String serializedTablePayload) {
        if (!iAmLeader) return;

        // 1. Get all candidates (Peers + Me)
        List<Integer> allNodes = new ArrayList<>(connectionManager.getConnectedPeerIds());
        allNodes.add(myId);
        Collections.sort(allNodes);

        // 2. Find Next Leader
        int myIndex = allNodes.indexOf(myId);
        int nextIndex = (myIndex + 1) % allNodes.size();
        int nextLeaderId = allNodes.get(nextIndex);

        System.out.println("[Election] Handing over leadership to Node " + nextLeaderId);

        // 3. Resign Locally
        iAmLeader = false;
        currentLeaderId = nextLeaderId;

        // 4. Broadcast Decree with DATA (Fix: Send the data, not just "Rotation")
        connectionManager.broadcastToAll(new GameMessage(
            GameMessage.Type.COORDINATOR, 
            "", 
            nextLeaderId, 
            serializedTablePayload // <--- PASS THE DATA
        ));
    }

    // --- Existing Bully Algorithm (For Crashes) ---
    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        iAmLeader = false; 
        System.out.println("[Election] Starting Election (" + reason + ")...");

        boolean sentChallenge = false;
        
        // Standard Bully: Only challenge higher IDs
        for (int peerId : connectionManager.getConnectedPeerIds()) {
            if (peerId > myId) {
                connectionManager.sendToPeer(peerId, new GameMessage(
                    GameMessage.Type.ELECTION, "", myId, "Election"
                ));
                sentChallenge = true;
            }
        }

        if (!sentChallenge) {
            declareVictory();
            return;
        }

        // Timeout waiting for "Stop" from higher nodes
        new Thread(() -> {
            try {
                Thread.sleep(2000); 
                if (!electionInProgress) return;
                declareVictory();
            } catch (Exception e) {}
        }).start();
    }

    private synchronized void declareVictory() {
        if (!electionInProgress) return;
        System.out.println("[Election] I am the new Leader (Victory)");
        iAmLeader = true;
        currentLeaderId = myId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(
            GameMessage.Type.COORDINATOR, "", myId, "Victory"
        ));
    }

    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                // Only respond if we have a higher ID
                if (msg.tcpPort < myId) {
                    connectionManager.sendToPeer(msg.tcpPort, new GameMessage(
                        GameMessage.Type.ELECTION_OK, "", myId, "Stop"
                    ));
                    startElection("Challenged by " + msg.tcpPort);
                }
                break;

            case ELECTION_OK:
                if (electionInProgress) electionInProgress = false;
                break;

            case COORDINATOR:
                // ACCEPT THE NEW RULER (Whether from Election or Rotation)
                currentLeaderId = msg.tcpPort;
                iAmLeader = (currentLeaderId == myId);
                electionInProgress = false;
                System.out.println("[Election] New Leader: " + msg.tcpPort + " (Reason: " + msg.payload + ")");
                break;
                
            default: break;
        }
    }
    
    public void handleNodeFailure(int deadNodeId) {
        System.out.println("[Election] Detected failure of Node " + deadNodeId);
        
        if (deadNodeId == currentLeaderId) {
            System.err.println(">>> THE LEADER HAS DIED! STARTING RECOVERY! <<<");
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}