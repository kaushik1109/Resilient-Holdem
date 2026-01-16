package game;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;
import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import networking.GameMessage;

public class NodeContext {
    public final int myPort;
    
    // The Components
    public final TcpMeshManager tcp;
    public final UdpMulticastManager udp;
    public final ElectionManager election;
    public final Sequencer sequencer;
    public final HoldBackQueue queue;
    public final ClientGameState clientGame;
    
    // Mutable Server State (Only active if Leader)
    private TexasHoldem serverGame;

    public NodeContext(int port) {
        this.myPort = port;

        // 1. Create Components
        // We pass 'this' (the context) to components that need global access
        this.clientGame = new ClientGameState();
        this.queue = new HoldBackQueue();
        this.tcp = new TcpMeshManager(port);
        this.udp = new UdpMulticastManager(port, tcp);
        this.election = new ElectionManager(port, tcp);
        this.sequencer = new Sequencer(udp);

        // 2. Wire Dependencies (The "Spaghetti" is hidden here)
        
        // Networking -> Consensus
        tcp.setElectionManager(election);
        tcp.setSequencer(sequencer);
        tcp.setHoldBackQueue(queue);
        tcp.setClientGame(clientGame);
        
        udp.setHoldBackQueue(queue);
        
        // Consensus -> Networking
        sequencer.setTcpLayer(tcp);
        sequencer.setLocalQueue(queue);
        
        queue.setTcpLayer(tcp);
        queue.setClientGame(clientGame);
        
        // 3. Centralized Routing Logic
        setupRouting();
    }

    private void setupRouting() {
        // Route Incoming Moves -> Game Engine
        queue.setCallback(msg -> {
            if (election.iAmLeader && serverGame != null) {
                if (msg.type == GameMessage.Type.PLAYER_ACTION) {
                    serverGame.processAction(msg.tcpPort, msg.payload);
                }
            }
        });

        // Route Incoming Requests -> Validation Logic
        tcp.setRequestHandler(msg -> {
            if (election.iAmLeader && serverGame != null) {
                serverGame.handleClientRequest(msg);
            } else {
                System.out.println("Received Request but I am not Leader/Ready.");
            }
        });
    }

    public void start() {
        tcp.start();
        udp.start();
        election.startStabilizationPeriod();
    }

    // --- Server Game Management ---

    public TexasHoldem getServerGame() {
        return serverGame;
    }

    public void createServerGame() {
        // Pass 'this' context so TexasHoldem can access TCP/Sequencer/Queue cleanly
        this.serverGame = new TexasHoldem(this);
    }
    
    public void destroyServerGame() {
        this.serverGame = null;
    }
}