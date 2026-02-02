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
            System.out.println("[Election] Listening for authoritative Leader signals");
            currentLeaderId = -1;

            while (connectionManager.getConnectedPeerIds().size() < 2) {
                try {
                    Thread.sleep(1000); 
                } catch (InterruptedException e) { }

                if (currentLeaderId != -1) {
                    System.out.println("[Election] Respecting existing Leader: " + currentLeaderId);
                    return;
                }
            }

            System.out.println("[Election] No Leader found. Starting Election");
            startElection("Startup");
        }).start();
    }

    public void passLeadership(String serializedTablePayload) {
        if (!iAmLeader) return;

        List<Integer> allNodes = new ArrayList<>(connectionManager.getConnectedPeerIds());
        allNodes.add(myId);
        Collections.sort(allNodes);

        int myIndex = allNodes.indexOf(myId);
        int nextIndex = (myIndex + 1) % allNodes.size();
        int nextLeaderId = allNodes.get(nextIndex);

        System.out.println("[Election] Handing over leadership to Node " + nextLeaderId);

        iAmLeader = false;
        currentLeaderId = nextLeaderId;

        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR, nextLeaderId, serializedTablePayload));
    }

    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        iAmLeader = false; 
        System.out.println("[Election] Starting Election (" + reason + ")");

        boolean sentChallenge = false;
        
        for (int peerId : connectionManager.getConnectedPeerIds()) {
            if (peerId > myId) {
                System.out.println("[Election] Challenging node " + peerId);
                connectionManager.sendToPeer(peerId, new GameMessage(GameMessage.Type.ELECTION, myId));
                sentChallenge = true;
            }
        }

        if (!sentChallenge) {
            System.out.println("[Election] No higher nodes, declaring victory");
            declareVictory();
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(2000); 
                if (!electionInProgress) return;
                System.out.println("[Election] No higher nodes have responded, declaring victory");
                declareVictory();
            } catch (Exception e) {}
        }).start();
    }

    private synchronized void declareVictory() {
        if (!electionInProgress) return;
        System.out.println("[Election] I am the new Leader");
        
        iAmLeader = true;
        currentLeaderId = myId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR, myId));
    }

    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                if (msg.tcpPort < myId) {
                    connectionManager.sendToPeer(msg.tcpPort, new GameMessage(GameMessage.Type.ELECTION_OK, myId));
                    startElection("Challenged by " + msg.tcpPort);
                }
                break;

            case ELECTION_OK:
                if (electionInProgress) electionInProgress = false;
                break;

            case COORDINATOR:
                currentLeaderId = msg.tcpPort;
                iAmLeader = (currentLeaderId == myId);
                electionInProgress = false;
                System.out.println("[Election] Elected new leader: " + msg.tcpPort);
                break;
                
            default: break;
        }
    }
    
    public void handleNodeFailure(int deadNodeId) {
        System.out.println("[Election] Detected failure of Node " + deadNodeId);
        
        if (deadNodeId == currentLeaderId) {
            System.err.println("[Election] The leader has crashed, starting election again");
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}