package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import game.NodeContext;

public class ElectionManager {
    private final NodeContext context;
    public volatile String currentLeaderId = null;
    private TcpMeshManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    public volatile boolean iAmLeader = false; 


    public ElectionManager(NodeContext context, TcpMeshManager connectionManager) {
        this.context = context;
        this.connectionManager = connectionManager;
    }

    public void startStabilizationPeriod() {
        new Thread(() -> {
            System.out.println("[Election] Listening for authoritative Leader signals");
            currentLeaderId = null;

            while (connectionManager.getConnectedPeerIds().size() < 2) {
                try {
                    Thread.sleep(1000); 
                } catch (InterruptedException e) { }

                if (currentLeaderId != null) {
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

        List<String> allNodes = new ArrayList<>(connectionManager.getConnectedPeerIds());
        allNodes.add(context.nodeId);
        allNodes.sort(String::compareTo);

        int myIndex = allNodes.indexOf(context.nodeId);
        int nextIndex = (myIndex + 1) % allNodes.size();
        String nextLeaderId = allNodes.get(nextIndex);

        System.out.println("[Election] Handing over leadership to Node " + nextLeaderId);

        iAmLeader = false;
        currentLeaderId = nextLeaderId;

        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR, context.myPort, context.myIp, serializedTablePayload));
    }

    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        iAmLeader = false; 
        System.out.println("[Election] Starting Election (" + reason + ")");

        boolean sentChallenge = false;
        
        for (String peerId : connectionManager.getConnectedPeerIds()) {
            int peerHash = peerId.hashCode();
            if (peerHash > context.nodeHash) {
                System.out.println("[Election] Challenging node " + peerId);
                connectionManager.sendToPeer(peerId, new GameMessage(GameMessage.Type.ELECTION, context.myPort, context.myIp));
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
        currentLeaderId = context.nodeId;
        electionInProgress = false;
        connectionManager.broadcastToAll(
        new GameMessage(
            GameMessage.Type.COORDINATOR,
            context.myPort,
            context.myIp,
            "Victory"
            )
        );

    }

    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                if (msg.senderHash < context.nodeHash) {
                connectionManager.sendToPeer(
                    msg.senderId,
                    new GameMessage(
                        GameMessage.Type.ELECTION_OK,
                        context.myPort,
                        context.myIp
                    )
                );
        startElection("Challenged by " + msg.senderId);
    }
                break;

            case ELECTION_OK:
                if (electionInProgress) electionInProgress = false;
                break;

            case COORDINATOR:
               currentLeaderId = msg.senderId;
               iAmLeader = currentLeaderId.equals(context.nodeId);
                electionInProgress = false;
                System.out.println("[Election] Elected new leader: " + msg.tcpPort);
                break;
                
            default: break;
        }
    }
    
    public void handleNodeFailure(String deadNodeId) {
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