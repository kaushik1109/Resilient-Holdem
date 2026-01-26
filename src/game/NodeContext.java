package game;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;
import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import networking.GameMessage;

public class NodeContext {
    public final int myPort;
    
    public final TcpMeshManager tcp;
    public final UdpMulticastManager udp;
    public final ElectionManager election;
    public final Sequencer sequencer;
    public final HoldBackQueue queue;
    public final ClientGameState clientGame;
    
    private TexasHoldem serverGame;

    public NodeContext(int port) {
        this.myPort = port;

        this.clientGame = new ClientGameState();
        this.queue = new HoldBackQueue();
        
        this.tcp = new TcpMeshManager(port, this); 
        this.udp = new UdpMulticastManager(port, this);
        
        this.election = new ElectionManager(port, tcp);
        this.sequencer = new Sequencer(udp);

        queue.setTcpLayer(tcp);
        queue.setClientGame(clientGame);
        queue.setCallback(this::handleQueueDelivery);
        
        sequencer.setTcpLayer(tcp);
        sequencer.setLocalQueue(queue);
    }

    public void start() {
        tcp.start();
        udp.start();
        election.startStabilizationPeriod();
    }

    public void routeMessage(GameMessage msg) {
        switch (msg.type) {
            case HEARTBEAT:
                break;

            case LEAVE:
                onPeerDisconnected(msg.tcpPort);
                break;
                
            case NACK:
                sequencer.handleNack(msg, msg.tcpPort);
                break;

            case ELECTION:
            case ELECTION_OK:
                election.handleMessage(msg);
                break;

            case COORDINATOR:
                election.currentLeaderId = msg.tcpPort;
                
                if (msg.tcpPort == myPort) {
                    System.out.println("[Context] Leadership passed to me");
                    
                    PokerTable loadedTable = TexasHoldem.deserializeState(msg.payload);
                    createServerGame(loadedTable);
                    this.election.iAmLeader = true;
                    
                    System.out.println(">>> Game State Loaded. Type 'start' to begin next hand");
                } else {
                    election.handleMessage(msg);
                }
                break;

            case ORDERED_MULTICAST:
            case PLAYER_ACTION:
            case GAME_STATE:
            case COMMUNITY_CARDS:
            case SHOWDOWN:
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
                System.out.println("[Context] Unknown Message Type: " + msg.type);
        }
    }

    private void handleQueueDelivery(GameMessage msg) {
        if (election.iAmLeader && serverGame != null && msg.type == GameMessage.Type.PLAYER_ACTION) {
            serverGame.processAction(msg.tcpPort, msg.payload);
        }
    }

    public void onPeerConnected(int peerId) {
        if (election.iAmLeader && serverGame != null) {
            serverGame.addPlayer(peerId);
        }
    }

    public void onPeerDisconnected(int peerId) {
        System.err.println("[Context] Peer " + peerId + " disconnected/crashed");
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
        
        System.out.println("[Context] Backfilling existing peers into the game");
        for (int peerId : tcp.getConnectedPeerIds()) {
            this.serverGame.addPlayer(peerId);
        }
    }

    public void createServerGame(PokerTable loadedTable) {
        this.serverGame = new TexasHoldem(this, loadedTable);
    }

    public void destroyServerGame() { this.serverGame = null; }
}