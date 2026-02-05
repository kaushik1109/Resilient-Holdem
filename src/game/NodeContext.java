package game;

import java.util.Objects;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;
import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import networking.GameMessage;
import networking.NetworkConfig;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printGame;

/**
 * Represents the context of a node in the Resilient Hold'em game.
 * Manages networking, consensus, game state, and message routing.
 */
public class NodeContext {
    public boolean dropNext;

    // This will always be of the form ip:port
    public final String myId;

    public final int myIdHash;
    
    public final TcpMeshManager tcp;
    public final UdpMulticastManager udp;
    public final ElectionManager election;
    public final Sequencer sequencer;
    public final HoldBackQueue queue;
    public final ClientGameState clientGame;
    
    // Set to private because we only want to create it in a specific way
    private TexasHoldem serverGame;

    public NodeContext() {
        this.myId = NetworkConfig.myId();
        this.myIdHash = Objects.hash(myId);

        this.clientGame = new ClientGameState();
        this.queue = new HoldBackQueue();
        
        this.tcp = new TcpMeshManager(this); 
        this.udp = new UdpMulticastManager(this);
        
        this.election = new ElectionManager(this, tcp);
        this.sequencer = new Sequencer(udp, tcp);

        queue.setQueueAttributes(tcp, clientGame, this::handleQueueDelivery);
    }

    /**
     * Starts the node by initializing the TCP and UDP managers, starting the election stabilization period, and handling user commands.
     */
    public void start() {
        tcp.start();
        udp.start();
        election.startStabilizationPeriod();
        ClientGameState.handleUserCommands(this);
    }

    /**
     * Routes incoming messages based on their type, handling consensus messages, game actions, and peer connection events accordingly.
     * @param msg The GameMessage to be routed.
     */
    public void routeMessage(GameMessage msg) {
        switch (msg.type) {
            case HEARTBEAT:
                break;

            case LEAVE:
                onPeerDisconnected(msg.getSenderId());
                break;
                
            case NACK:
                sequencer.handleNack(msg, msg.getSenderId());
                break;

            case ELECTION:
            case ELECTION_OK:
                election.handleMessage(msg);
                break;

            case HANDOVER:
                printGame("[Context] Leadership passed to me");
                PokerTable loadedTable = PokerTable.deserializeState(msg.payload);
                election.declareVictory(true);
                createServerGame(loadedTable);

            case COORDINATOR:
                election.currentLeaderId = msg.getSenderId();
                queue.setLeaderId(msg.getSenderId());
                election.handleMessage(msg);

            case ORDERED_MULTICAST:
            case PLAYER_ACTION:
            case GAME_INFO:
            case COMMUNITY_CARDS:
            case SHOWDOWN:
                if (dropNext) {
                    printError("[Context] Simulating omission. Dropped Msg #" + msg.sequenceNumber);
                    dropNext = false;
                    return;
                }

                if (msg.sequenceNumber <= 0) {
                    if (msg.type == GameMessage.Type.GAME_INFO) {
                         clientGame.onReceiveInfo(msg.payload);
                    }
                } else {
                    queue.addMessage(msg);
                }
                break;

            case GAME_STATE:
                queue.addMessage(msg);
                break;

            case SYNC:
                queue.forceSync(Long.parseLong(msg.payload));
                break;

            case YOUR_HAND:
                clientGame.onReceiveHand(msg.payload);
                break;
            
            case ACTION_REQUEST:
                if (election.iAmLeader && serverGame != null) {
                    serverGame.handleClientRequest(msg);
                }
                break;

            default:
                printError("[Context] Unknown Message Type: " + msg.type);
        }
    }

    /**
     * Handles the delivery of messages from the holdback queue to the application layer.
     * Specifically processes PLAYER_ACTION messages if the node is the leader.
     * This method is passed to the HoldBackQueue for callback upon message delivery to keep pipes dumb.
     * @param msg The GameMessage being delivered.
     */
    private void handleQueueDelivery(GameMessage msg) {
        if (election.iAmLeader && serverGame != null && msg.type == GameMessage.Type.PLAYER_ACTION) {
            serverGame.processAction(msg.getSenderId(), msg.payload);
        }
    }

    /**
     * Handles the event of a peer connecting to the node.
     * If the node is the leader, it adds the new player to the server game.
     * @param peerId The ID of the connected peer.
     */
    public void onPeerConnected(String peerId) {
        if (election.iAmLeader && serverGame != null) {
            serverGame.addPlayer(peerId);
        }
    }

    /**
     * Handles the event of a peer disconnecting from the node.
     * Closes the TCP connection to the peer 
     * and informs the election manager and server game (if I am leader) about the disconnection.
     * @param peerId The ID of the disconnected peer.
     */
    public void onPeerDisconnected(String peerId) {
        printError("[Context] Peer " + peerId + " disconnected/crashed");
        tcp.closeConnection(peerId);

        if (election != null) {
            election.handleNodeFailure(peerId);
        }

        if (election.iAmLeader && serverGame != null) {
            serverGame.handlePlayerCrash(peerId);
        }
    }

    public TexasHoldem getServerGame() { return serverGame; }

    /**
     * Creates a new server game instance for the node.
     * This method is called when the node becomes the leader.
     */
    public void createServerGame() {
        this.serverGame = new TexasHoldem(this);
    }

    /**
     * Creates a new server game instance for the node with a loaded poker table state.
     * This method is called when the node becomes the leader through a handover.
     * @param loadedTable The PokerTable state to initialize the server game with.
     */
    public void createServerGame(PokerTable loadedTable) {
        if (loadedTable == null) {
            createServerGame();
            return;
        }

        this.serverGame = new TexasHoldem(this, loadedTable);
    }

    /**
     * Destroys the current server game instance.
     * This method is called when the node is no longer the leader before handover or wants to reset the game state.
     */
    public void destroyServerGame() { this.serverGame = null; }
}