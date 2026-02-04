package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import game.NodeContext;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printElection;
import static util.ConsolePrint.printElectionBold;

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
            printElection("[Election] Listening for authoritative Leader signals.");
            currentLeaderId = null;

            while (connectionManager.getConnectedPeerIds().size() < 2) {
                try {
                    Thread.sleep(1000); 
                } catch (InterruptedException e) { }

                if (iAmLeader) return;

                if (currentLeaderId != null) {
                    printElection("[Election] Respecting existing Leader: " + currentLeaderId);
                    return;
                }
            }

            printElection("[Election] No Leader found. Starting Election.");
            startElection("Startup");
        }).start();
    }

    public void passLeadership(String serializedTablePayload) {
        if (!iAmLeader) return;

        List<String> allNodes = new ArrayList<>(connectionManager.getConnectedPeerIds());
        allNodes.add(context.myId);
        allNodes.sort(Comparator.comparingInt(node -> Objects.hash(node)));

        int myIndex = allNodes.indexOf(context.myId);
        int nextIndex = (myIndex + 1) % allNodes.size();
        String nextLeaderId = allNodes.get(nextIndex);

        printElection("[Election] Handing over leadership to Node " + nextLeaderId);

        iAmLeader = false;
        currentLeaderId = nextLeaderId;

        connectionManager.sendToPeer(nextLeaderId, new GameMessage(GameMessage.Type.HANDOVER, serializedTablePayload));
    }

    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        iAmLeader = false; 
        printElection("[Election] Starting Election (" + reason + ").");

        boolean sentChallenge = false;
        
        for (String peerId : connectionManager.getConnectedPeerIds()) {
            int peerHash = peerId.hashCode();
            if (peerHash > context.myIdHash) {
                printElection("[Election] Challenging node " + peerId);
                connectionManager.sendToPeer(peerId, new GameMessage(GameMessage.Type.ELECTION));
                sentChallenge = true;
            }
        }

        if (!sentChallenge) {
            printElection("[Election] No higher nodes, declaring victory.");
            declareVictory(false);
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(2000); 
                if (!electionInProgress) return;
                printElection("[Election] No higher nodes have responded, declaring victory.");
                declareVictory(false);
            } catch (Exception e) {}
        }).start();
    }

    public synchronized void declareVictory(boolean handover) {
        if (!electionInProgress && !handover) return;
        printElectionBold("[Election] I am the new Leader.");
        
        iAmLeader = true;
        currentLeaderId = context.myId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR));

        if(!handover) context.createServerGame();
    }

    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                if (msg.getSenderHash() < context.myIdHash) {
                    connectionManager.sendToPeer(msg.getSenderId(), new GameMessage(GameMessage.Type.ELECTION_OK));
                    startElection("Challenged by " + msg.getSenderId());
                }
                break;

            case ELECTION_OK:
                if (electionInProgress) electionInProgress = false;
                break;

            case COORDINATOR:
               currentLeaderId = msg.getSenderId();
               iAmLeader = currentLeaderId.equals(context.myId);
                electionInProgress = false;
                printElection("[Election] New leader: " + msg.getSenderId());
                break;
                
            default: break;
        }
    }
    
    public void handleNodeFailure(String deadNodeId) {
        printError("[Election] Detected failure of Node " + deadNodeId);
        
        if (deadNodeId == currentLeaderId) {
            printError("[Election] The leader has crashed, starting election again");
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}