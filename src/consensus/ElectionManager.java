package consensus;

import networking.ConnectionManager;
import networking.GameMessage;

public class ElectionManager {
    private int myId;
    private ConnectionManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    private volatile boolean iAmLeader = false; // Made volatile for thread safety
    public int currentLeaderId = -1;

    public ElectionManager(int myId, ConnectionManager connectionManager) {
        this.myId = myId;
        this.connectionManager = connectionManager;
    }

    public void startStabilizationPeriod() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                startElection("Startup");
            } catch (InterruptedException e) { e.printStackTrace(); }
        }).start();
    }

    public synchronized void startElection(String reason) {
        // FIX 1: If we are already the leader, ignore the "Startup" timer
        if (iAmLeader) {
            return;
        }
        
        // FIX 2: If an election is already running, don't start another
        if (electionInProgress) {
            return;
        }
        
        electionInProgress = true;
        iAmLeader = false; // We are not leader while fighting
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
                if (!electionInProgress) return; // Was cancelled
                declareVictory();
            } catch (Exception e) {}
        }).start();
    }

    private synchronized void declareVictory() {
        if (!electionInProgress) return;

        // FIX 3: Simpler Log Message
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
            
            // FIX 4: Default case for Linter
            default:
                break;
        }
    }
}