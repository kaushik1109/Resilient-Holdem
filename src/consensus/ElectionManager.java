package consensus;

import networking.TcpMeshManager;
import networking.GameMessage;
import networking.Peer;

import java.util.Set;

import game.NodeContext;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printElection;
import static util.ConsolePrint.printElectionBold;

/**
 * Manages leader election in the Resilient Hold'em game using the Bully algorithm.
 * Handles election initiation, victory declaration, and leader failure detection.
 */
public class ElectionManager {
    private final NodeContext node;
    public volatile String currentLeaderId = null;
    private TcpMeshManager connectionManager;
    
    private volatile boolean electionInProgress = false;
    public volatile boolean iAmLeader = false; 

    public ElectionManager(NodeContext node, TcpMeshManager connectionManager) {
        this.node = node;
        this.connectionManager = connectionManager;
    }

    /**
     * Starts the stabilization period during which the node listens for existing leader signals.
     * If no leader is detected within the period, initiates a new election.
     */
    public void startStabilizationPeriod() {
        new Thread(() -> {
            currentLeaderId = null;

            while (connectionManager.getConnectedPeerIds().size() < 3) {
                try {Thread.sleep(5000);} catch (InterruptedException e) { }
                if (iAmLeader) return;
                if (currentLeaderId != null) return;
            }

            printElection("[Election] Starting Election.");
            startElection("Startup");
        }).start();
    }

    /**
     * Passes leadership to the next node in the sorted list of connected nodes.
     * @param serializedTablePayload The serialized game state to send to the new leader.
     */
    public void passLeadership(String serializedTablePayload) {
        iAmLeader = false;
        printElection("[Election] Giving up leadership");
        startElection("New Round");;
    }

    /**
     * Initiates a new election process using the Bully algorithm.
     * @param reason The reason for starting the election e.g. "Leader Crash" or "Challenged by X".
     */
    public synchronized void startElection(String reason) {
        if (iAmLeader || electionInProgress) return;
        
        electionInProgress = true;
        boolean sentChallenge = false;
        int currentRoundNumber = node.clientGame.table.roundNumber;
        Set<String> connectedPeers = connectionManager.getConnectedPeerIds();
        long myHash = Peer.getPeerHash(node.myId, node.myId, connectedPeers, currentRoundNumber);

        printElection("[Election] Starting Election (" + reason + ", my hash: " + myHash + ").");
        
        for (String peerId : connectedPeers) {
            long peerHash = Peer.getPeerHash(peerId, node.myId, connectedPeers, currentRoundNumber);
            if (peerHash > myHash) {
                printElection("[Election] Challenging node " + peerId + " (hash: " + peerHash + ").");
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
                Thread.sleep(5000); 
                if (!electionInProgress) return;
                printElection("[Election] No higher nodes have responded, declaring victory.");
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
        currentLeaderId = node.myId;
        electionInProgress = false;
        connectionManager.broadcastToAll(new GameMessage(GameMessage.Type.COORDINATOR));

        if(!handover) node.createServerGame(node.clientGame.table);
    }

    /**
     * Handles incoming election-related messages and responds according to the Bully algorithm.
     * @param msg The GameMessage containing the election message.
     */
    public void handleMessage(GameMessage msg) {
        switch (msg.type) {
            case ELECTION:
                int currentRoundNumber = node.clientGame.table.roundNumber;
                Set<String> connectedPeers = connectionManager.getConnectedPeerIds();
                long myHash = Peer.getPeerHash(node.myId, node.myId, connectedPeers, currentRoundNumber);
                long senderHash = Peer.getPeerHash(msg.getSenderId(), node.myId, connectedPeers, currentRoundNumber);

                if (senderHash < myHash) {
                    connectionManager.sendToPeer(msg.getSenderId(), new GameMessage(GameMessage.Type.ELECTION_OK));
                    startElection("Challenged by " + msg.getSenderId() + " with hash: " + senderHash);
                }
                break;

            case ELECTION_OK:
                if (electionInProgress) electionInProgress = false;
                break;

            case COORDINATOR:
                currentLeaderId = msg.getSenderId();
                iAmLeader = currentLeaderId.equals(node.myId);
                electionInProgress = false;
                printElection("[Election] New leader: " + msg.getSenderId());
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
        
        if (deadNodeId.equals(currentLeaderId)) {
            printError("[Election] The leader has crashed, starting election again.");
            new Thread(() -> {
                try { Thread.sleep(500); } catch(Exception e){}
                startElection("Leader Crash");
            }).start();
        }
    }
}