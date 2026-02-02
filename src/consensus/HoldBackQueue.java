package consensus;

import networking.GameMessage;
import networking.TcpMeshManager;
import game.ClientGameState;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.function.Consumer;

public class HoldBackQueue {
    private PriorityQueue<GameMessage> queue = new PriorityQueue<>(
        Comparator.comparingLong(msg -> msg.sequenceNumber)
    );
    
    private long nextExpectedSeq = 1;
    private TcpMeshManager tcpLayer; 
    private ClientGameState clientGame;
    private int leaderId = -1;
    private boolean isProcessing = false;

    private Consumer<GameMessage> onMessageReceived; 

    public void setCallback(Consumer<GameMessage> callback) {
        this.onMessageReceived = callback;
    }

    public void setClientGame(ClientGameState clientGameState) {
        this.clientGame = clientGameState;
    }

    public void setTcpLayer(TcpMeshManager tcpLayer) {
        this.tcpLayer = tcpLayer;
    }

    public void setLeaderId(int leaderId) {
        this.leaderId = leaderId;
    }

    public void setQueueAttributes(TcpMeshManager tcpLayer, ClientGameState clientGameState, Consumer<GameMessage> callback) {
        this.tcpLayer = tcpLayer;
        this.clientGame = clientGameState;
        this.onMessageReceived = callback;
    }

    public synchronized void addMessage(GameMessage msg) {
        queue.add(msg);
        processQueue();
    }

    private void processQueue() {
        if (isProcessing) return; 
        isProcessing = true;

        try {
            while (!queue.isEmpty()) {
                GameMessage head = queue.peek();
                System.out.println("[Queue] Processing #" + head.sequenceNumber + ", expecting #" + nextExpectedSeq);

                if (head.sequenceNumber == nextExpectedSeq) {
                    queue.poll();
                    
                    // Update sequence BEFORE delivering to app. This ensures if the app generates a NEW message (Seq+1), we are ready for it.
                    nextExpectedSeq++; 
                    
                    deliverToApp(head);
                } 
                else if (head.sequenceNumber < nextExpectedSeq) {
                    // Ignore duplicate
                    queue.poll();
                } 
                else {
                    sendNack(nextExpectedSeq);
                    break;
                }
            }
        } finally {
            isProcessing = false;
        }
    }
    
    private void sendNack(long missingSeq) {
        System.out.println("[Queue] Sending NACK for #" + missingSeq);
        tcpLayer.sendNack(leaderId, missingSeq);
    }

    public synchronized void forceSync(long catchUpSeq) {
        // Jump the counter to what the Leader tells us
        // We add 1 because the Leader sends the "Current" ID (e.g., 50), so we want to be ready for the NEXT one (51).
        this.nextExpectedSeq = catchUpSeq + 1;
        
        // Clear any old junk we might have buffered while waiting
        queue.clear(); 
        
        System.out.println("[Queue] Synced! Jumped to Sequence #" + nextExpectedSeq);
    }

    private void deliverToApp(GameMessage msg) {
        if (clientGame != null) {
            switch (msg.type) {
                case COMMUNITY_CARDS: 
                    clientGame.onReceiveCommunity(msg.payload); 
                    break;
                case GAME_STATE:
                case SHOWDOWN:
                    clientGame.onReceiveState(msg.payload); 
                    break;
                default:
                    break;
            }
        }

        if (onMessageReceived != null) {
            onMessageReceived.accept(msg);
        }
    }
}