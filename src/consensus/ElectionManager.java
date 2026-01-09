package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;

public class ElectionManager {
    private int myId;
    private TcpMeshManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    public volatile boolean iAmLeader = false; // Made volatile for thread safety
    public int currentLeaderId = -1;

    public ElectionManager(int myId, TcpMeshManager connectionManager) {
        this.myId = myId;
        this.connectionManager = connectionManager;
    }

public void startStabilizationPeriod() {
        // NEW LOGIC: Passive Startup
        new Thread(() -> {
            System.out.println("[Election] Listening for existing Leader...");
            
            try {
                // Wait 3 seconds to receive a Heartbeat or Game State from an existing Leader
                Thread.sleep(3000); 
            } catch (InterruptedException e) { }

            if (currentLeaderId != -1) {
                // We found a leader! Do NOT start an election.
                // Even if my ID is higher, I respect the incumbent to preserve Game State.
                System.out.println("[Election] Respecting existing Leader: " + currentLeaderId);
            } else {
                // Silence... No one is in charge.
                // OK, now I can use my high ID to become the Leader.
                System.out.println("[Election] No Leader found. Starting Election...");
                startElection("Startup");
            }
        }).start();
    }

    public synchronized void startElection(String reason) {
        if (iAmLeader) {
            return;
        }
        
        if (electionInProgress) {
            return;
        }
        
        electionInProgress = true;
        iAmLeader = false; 
        System.out.println("[Election] Starting Election (" + reason + ")...");

        boolean sentChallenge = false;
        
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

        // Timeout thread
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

        System.out.println("[Election] I am the leader");
        
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
                if (msg.tcpPort < myId) {
                    connectionManager.sendToPeer(msg.tcpPort, new GameMessage(
                        GameMessage.Type.ELECTION_OK, "", myId, "Stop"
                    ));
                    startElection("Challenged by " + msg.tcpPort);
                }
                break;

            case ELECTION_OK:
                if (electionInProgress) {
                    electionInProgress = false;
                }
                break;

            case COORDINATOR:
                currentLeaderId = msg.tcpPort;
                iAmLeader = (currentLeaderId == myId);
                electionInProgress = false;
                System.out.println("[Election] New Leader: " + msg.tcpPort);
                break;
            
            default:
                break;
        }
    }
    
    /**
     * Called by TCP Layer when a node crashes or times out.
     */
    public void handleNodeFailure(int deadNodeId) {
        System.out.println("[Election] Detected failure of Node " + deadNodeId);
        
        if (deadNodeId == currentLeaderId) {
            System.err.println(">>> THE LEADER HAS DIED! STARTING ELECTION! <<<");
            
            // Wait brief moment to ensure socket cleanup, then start
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}