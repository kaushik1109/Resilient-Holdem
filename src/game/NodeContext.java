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
    
    private TexasHoldem serverGame;

    public NodeContext() {
        this.myId = NetworkConfig.MY_IP + ":" + NetworkConfig.MY_PORT;
        this.myIdHash = Objects.hash(myId);

        this.clientGame = new ClientGameState();
        this.queue = new HoldBackQueue();
        
        this.tcp = new TcpMeshManager(this); 
        this.udp = new UdpMulticastManager(this);
        
        this.election = new ElectionManager(this, tcp);
        this.sequencer = new Sequencer(udp, tcp);

        queue.setQueueAttributes(tcp, clientGame, this::handleQueueDelivery);
    }

    public void start() {
        tcp.start();
        udp.start();
        election.startStabilizationPeriod();
        ClientGameState.handleUserCommands(this);
    }

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
                PokerTable loadedTable = TexasHoldem.deserializeState(msg.payload);
                election.declareVictory(true);
                createServerGame(loadedTable);

            case COORDINATOR:
                election.currentLeaderId = msg.getSenderId();
                queue.setLeaderId(msg.getSenderId());
                election.handleMessage(msg);

            case ORDERED_MULTICAST:
            case PLAYER_ACTION:
            case GAME_STATE:
            case COMMUNITY_CARDS:
            case SHOWDOWN:
                if (dropNext) {
                    printError("[Context] Simulating omission. Dropped Msg #" + msg.sequenceNumber);
                    dropNext = false;
                    return;
                }

                if (msg.sequenceNumber <= 0) {
                    if (msg.type == GameMessage.Type.GAME_STATE) {
                         clientGame.onReceiveState(msg.payload);
                    }
                } else {
                    queue.addMessage(msg);
                }
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

    private void handleQueueDelivery(GameMessage msg) {
        if (election.iAmLeader && serverGame != null && msg.type == GameMessage.Type.PLAYER_ACTION) {
            serverGame.processAction(msg.getSenderId(), msg.payload);
        }
    }

    public void onPeerConnected(String peerId) {
        if (election.iAmLeader && serverGame != null) {
            serverGame.addPlayer(peerId);
        }
    }

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

    public void createServerGame() {
        this.serverGame = new TexasHoldem(this);
    }

    public void createServerGame(PokerTable loadedTable) {
        this.serverGame = new TexasHoldem(this, loadedTable);
    }

    public void destroyServerGame() { this.serverGame = null; }
}