package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import game.NodeContext;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printConsensus;
import static util.ConsolePrint.printElectionBold;

/**
 * Manages leader election in the Resilient Hold'em game using the Bully algorithm.
 * Handles election initiation, victory declaration, and leader failure detection.
 */
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

    /**
     * Starts the stabilization period during which the node listens for existing leader signals.
     * If no leader is detected within the period, initiates a new election.
     */
    public void startStabilizationPeriod() {
        new Thread(() -> {
            printConsensus("[Election] Listening for authoritative Leader signals.");
            currentLeaderId = null;

            while (connectionManager.getConnectedPeerIds().size() < 2) {
                try {
                    Thread.sleep(1000); 
                } catch (InterruptedException e) { }

                if (iAmLeader) return;

                if (currentLeaderId != null) {
                    printConsensus("[Election] Respecting existing Leader: " + currentLeaderId);
                    return;
                }
            }

            printConsensus("[Election] No Leader found. Starting Election.");
            startElection("Startup");
        }).start();
    }

    /**
     * Passes leadership to the next node in the sorted list of connected nodes.
     * @param serializedTablePayload The serialized game state to send to the new leader.
     */
    public void passLeadership(String serializedTablePayload) {
        if (!iAmLeader) return;

        List<String> allNodes = new ArrayList<>(connectionManager.getConnectedPeerIds());
        allNodes.add(context.myId);
        allNodes.sort(Comparator.comparingInt(node -> Objects.hash(node)));

        int myIndex = allNodes.indexOf(context.myId);
        int nextIndex = (myIndex + 1) % allNodes.size();
        String nextLeaderId = allNodes.get(nextIndex);

        printConsensus("[Election] Handing over leadership to Node " + nextLeaderId);

        iAmLeader = false;
        currentLeaderId = nextLeaderId;

        connectionManager.sendToPeer(nextLeaderId, new GameMessage(GameMessage.Type.HANDOVER, serializedTablePayload));
    }

    /**
     * Initiates a new election process using the Bully algorithm.
     * @param reason The reason for starting the election e.g. "Leader Crash" or "Challenged by X".
     */
    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        iAmLeader = false; 
        printConsensus("[Election] Starting Election (" + reason + ").");

        boolean sentChallenge = false;
        
        for (String peerId : connectionManager.getConnectedPeerIds()) {
            int peerHash = peerId.hashCode();
            if (peerHash > context.myIdHash) {
                printConsensus("[Election] Challenging node " + peerId);
                connectionManager.sendToPeer(peerId, new GameMessage(GameMessage.Type.ELECTION));
                sentChallenge = true;
            }
        }

        if (!sentChallenge) {
            printConsensus("[Election] No higher nodes, declaring victory.");
            declareVictory(false);
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(2000); 
                if (!electionInProgress) return;
                printConsensus("[Election] No higher nodes have responded, declaring victory.");
                declareVictory(false);
            } catch (Exception e) {}
        }).start();
    }

    /**
     * Declares this node as the new leader and broadcasts the COORDINATOR message.
     * @param handover Indicates if this declaration is part of a leadership handover.
     */
    public synchronized void declareVictory(boolean handover) {
        if (!electionInProgress && !handover) return;
        printElectionBold("[Election] I am the new Leader.");
        
        iAmLeader = true;
        currentLeaderId = context.myId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR));

        if(!handover) context.createServerGame();
    }

    /**
     * Handles incoming election-related messages and responds according to the Bully algorithm.
     * @param msg The GameMessage containing the election message.
     */
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
                printConsensus("[Election] New leader: " + msg.getSenderId());
                break;
                
            default: break;
        }
    }
    
    /**
     * Handles the failure of a node by checking if it was the leader and initiating a new election if necessary.
     * @param deadNodeId The ID of the node that has failed.
     */
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