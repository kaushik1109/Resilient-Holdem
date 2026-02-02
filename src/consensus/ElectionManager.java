package consensus;

import networking.TcpMeshManager;
import game.NodeContext;
import networking.GameMessage;
import java.util.ArrayList;

import java.util.List;


public class ElectionManager {
    
    private final NodeContext context;
    private TcpMeshManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    public volatile boolean iAmLeader = false; 
    public volatile String currentLeaderId = null;

    public ElectionManager(NodeContext context, TcpMeshManager connectionManager) {
        this.context = context;
        this.connectionManager = connectionManager;
    }

    public void startStabilizationPeriod() {
        new Thread(() -> {
            System.out.println("[Election] Listening for authoritative Leader signals...");
            currentLeaderId = null; // Reset to unknown
            
            try {
                Thread.sleep(3000); 
            } catch (InterruptedException e) { }

            if (currentLeaderId != null) {
                System.out.println("[Election] Respecting existing Leader: " + currentLeaderId);
            } else {
                System.out.println("[Election] No Leader found. Starting Election...");
                startElection("Startup");
            }
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

        connectionManager.broadcastToAll(new GameMessage(
            GameMessage.Type.COORDINATOR,
            context.myPort,
            context.myIp,
            serializedTablePayload
        ));
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
                connectionManager.sendToPeer(
                peerId,
                new GameMessage(
                    GameMessage.Type.ELECTION,
                    context.myPort,
                    context.myIp,
                    "Election"
                    )
                );
                sentChallenge = true;
            }
        }

        if (!sentChallenge) {
            declareVictory();
            return;
        }

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
        currentLeaderId = context.nodeId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(
            GameMessage.Type.COORDINATOR, context.myPort, context.myIp, "Victory"
        ));
    }

    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                if (msg.senderHash < context.nodeHash) {
                    connectionManager.sendToPeer(msg.senderId, new GameMessage(GameMessage.Type.ELECTION_OK, context.myPort, context.myIp));
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
                System.out.println("[Election] New Leader: " + currentLeaderId);
                break;
                
            default: break;
        }
    }
    
    public void handleNodeFailure(String deadNodeId) {
        System.out.println("[Election] Detected failure of Node " + deadNodeId);
        
        if (deadNodeId.equals(currentLeaderId)) {
            System.err.println("[Election] The leader has crashed, starting election again");
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}