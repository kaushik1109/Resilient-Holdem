package consensus;

import networking.ConnectionManager;
import networking.GameMessage;

public class ElectionManager {
    private int myId;
    private ConnectionManager connectionManager;
    private boolean electionInProgress = false;
    
    // We are the Leader by default until we find someone bigger
    public boolean iAmLeader = false;
    public int currentLeaderId = -1;

    public ElectionManager(int myId, ConnectionManager connectionManager) {
        this.myId = myId;
        this.connectionManager = connectionManager;
    }

    public void startStabilizationPeriod() {
        // Wait 5 seconds before checking for leader
        new Thread(() -> {
            try {
                System.out.println("[Election] Waiting 5s for peers");
                Thread.sleep(5000);
                startElection();
            } catch (InterruptedException e) { e.printStackTrace(); }
        }).start();
    }

    public synchronized void startElection() {
        electionInProgress = true;
        iAmLeader = false;
        System.out.println("[Election] Starting Election");

        boolean sentChallenge = false;
        
        // 1. Send ELECTION to all peers with higher IDs
        for (int peerId : connectionManager.getConnectedPeerIds()) {
            if (peerId > myId) {
                System.out.println("[Election] Challenging Node " + peerId);
                connectionManager.sendToPeer(peerId, new GameMessage(
                    GameMessage.Type.ELECTION, "", myId, "Election"
                ));
                sentChallenge = true;
            }
        }

        // 2. If no one is higher, I win
        if (!sentChallenge) {
            declareVictory();
            return;
        }

        // 3. Wait for replies. If no one replies "OK", I win.
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 2 second timeout
                if (electionInProgress) {
                    declareVictory();
                }
            } catch (Exception e) {}
        }).start();
    }

    private void declareVictory() {
        System.out.printf("[Election] I am the leader (ID: %s)\n", myId);
        iAmLeader = true;
        currentLeaderId = myId;
        electionInProgress = false;
        
        // Tell everyone
        connectionManager.broadcastToAll(new GameMessage(
            GameMessage.Type.COORDINATOR, "", myId, "Victory"
        ));
    }

    public synchronized void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                // Someone lower wants to be leader. Tell them NO and start our own election to assert dominance
                if (msg.tcpPort < myId) {
                    System.out.println("[Election] Rebuking Node " + msg.tcpPort);
                    connectionManager.sendToPeer(msg.tcpPort, new GameMessage(
                        GameMessage.Type.ELECTION_OK, "", myId, "Stop"
                    ));
                    
                    startElection();
                }
                break;

            case ELECTION_OK:
                // Someone higher is alive. We stop trying.
                System.out.println("[Election] Higher node responded. I yield.");
                electionInProgress = false;
                break;

            case COORDINATOR:
                // New leader announced
                System.out.printf("[Election] The new leader is node %s.\n", msg.tcpPort);
                currentLeaderId = msg.tcpPort;
                iAmLeader = false;
                electionInProgress = false;
                break;

            default:
                System.out.println("[Election] Illegal message type received.");
        }
    }
}