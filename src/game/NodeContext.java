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

        // 1. Create Components
        this.clientGame = new ClientGameState();
        this.queue = new HoldBackQueue();
        
        // Pass 'this' so they can call routeMessage()
        this.tcp = new TcpMeshManager(port, this); 
        this.udp = new UdpMulticastManager(port, this);
        
        this.election = new ElectionManager(port, tcp);
        this.sequencer = new Sequencer(udp);

        // 2. Wire Dependencies (Queue still needs these to function)
        queue.setTcpLayer(tcp);
        queue.setClientGame(clientGame);
        queue.setCallback(this::handleQueueDelivery); // Centralized Callback
        
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
            // --- 1. Infrastructure ---
            case HEARTBEAT:
                // No action needed! TCP layer already updated timestamp.
                break;

            case LEAVE:
                tcp.closeConnection(msg.tcpPort);
                break;
                
            case NACK:
                sequencer.handleNack(msg, msg.tcpPort);
                break;

            // --- 2. Consensus ---
            case ELECTION:
            case ELECTION_OK:
                election.handleMessage(msg);
                break;

                case COORDINATOR:
                    election.currentLeaderId = msg.tcpPort;
                    
                    if (msg.tcpPort == myPort && msg.payload.length() > 20) {
                        System.out.println(">>> I HAVE BEEN CROWNED! Loading Game State...");
                        
                        PokerTable loadedTable = TexasHoldem.deserializeState(msg.payload);
                        createServerGame(loadedTable);
                        this.election.iAmLeader = true;
                        
                        System.out.println(">>> Game State Loaded. Type 'start' to begin next hand.");
                    } else {
                        election.handleMessage(msg);
                    }
                    break;

            // --- 3. Game Data ---
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
                    election.currentLeaderId = msg.tcpPort;
                    queue.addMessage(msg);
                }
                break;

            case SYNC:
                election.currentLeaderId = msg.tcpPort;
                queue.forceSync(Long.parseLong(msg.payload));
                break;

            // --- 4. Direct Client Updates (TCP Unicast) ---
            case YOUR_HAND:
                clientGame.onReceiveHand(msg.payload);
                break;

            // --- 5. Requests to Leader (If I am Leader) ---
            case ACTION_REQUEST:
                if (election.iAmLeader && serverGame != null) {
                    serverGame.handleClientRequest(msg);
                }
                break;

            default:
                System.out.println("Unknown Message Type: " + msg.type);
        }
    }

    // Callback for when HoldBackQueue releases a valid, ordered message
    private void handleQueueDelivery(GameMessage msg) {
        // If I am Leader, feed it to the Game Engine
        if (election.iAmLeader && serverGame != null) {
            if (msg.type == GameMessage.Type.PLAYER_ACTION) {
                serverGame.processAction(msg.tcpPort, msg.payload);
            }
        }
    }

    public void onPeerConnected(int peerId) {
        // If I am Leader, add them to the game immediately
        if (election.iAmLeader && serverGame != null) {
            serverGame.addPlayer(peerId);
        }
    }

    public void onPeerDisconnected(int peerId) {
        System.err.println("[Context] Peer " + peerId + " disconnected/crashed.");

        if (election != null) {
            election.handleNodeFailure(peerId);
        }

        if (election.iAmLeader && serverGame != null) {
            serverGame.handlePlayerCrash(peerId);
        }
    }

    // --- Server Game Management ---
    public TexasHoldem getServerGame() { return serverGame; }

    public void createServerGame() {
        this.serverGame = new TexasHoldem(this);
        
        System.out.println("[Context] Backfilling existing peers into the game...");
        for (int peerId : tcp.getConnectedPeerIds()) {
            this.serverGame.addPlayer(peerId);
        }
    }

    // Overload for Rotation (Migration)
    public void createServerGame(PokerTable loadedTable) {
        this.serverGame = new TexasHoldem(this, loadedTable);
    }
    public void destroyServerGame() { this.serverGame = null; }
}