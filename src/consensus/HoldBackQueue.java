package consensus;

import networking.GameMessage;
import networking.TcpMeshManager;
import game.ClientGameState;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.function.Consumer; // <--- STRICTLY IMPORT THIS

public class HoldBackQueue {
    private PriorityQueue<GameMessage> queue = new PriorityQueue<>(
        Comparator.comparingLong(msg -> msg.sequenceNumber)
    );
    
    private long nextExpectedSeq = 1;
    private TcpMeshManager tcpLayer; 
    private ClientGameState clientGame;
    private int leaderId = -1;
    private boolean isProcessing = false;

    // 1. The Callback Field (The "Slot")
    private Consumer<GameMessage> onMessageReceived; 

    // 2. The Setter (Plugging logic into the slot)
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

    public synchronized void addMessage(GameMessage msg) {
        queue.add(msg);
        processQueue();
    }

private void processQueue() {
        if (isProcessing) return; // FIX: Prevent recursive processing
        isProcessing = true;

        try {
            while (!queue.isEmpty()) {
                GameMessage head = queue.peek();

                if (head.sequenceNumber == nextExpectedSeq) {
                    queue.poll();
                    
                    // Critical: Update sequence BEFORE delivering to app
                    // This ensures if the app generates a NEW message (Seq+1),
                    // we are ready for it.
                    nextExpectedSeq++; 
                    
                    deliverToApp(head);
                } 
                else if (head.sequenceNumber < nextExpectedSeq) {
                    queue.poll(); // Ignore duplicate
                } 
                else {
                    // Real Gap detected
                    sendNack(nextExpectedSeq);
                    break;
                }
            }
        } finally {
            isProcessing = false;
        }
    }
    
    private void sendNack(long missingSeq) {
        if (tcpLayer != null && leaderId != -1) {
            System.out.println("[Queue] Sending NACK for #" + missingSeq);
            GameMessage nack = new GameMessage(
                GameMessage.Type.NACK, "local", 0, String.valueOf(missingSeq)
            );
            tcpLayer.sendToPeer(leaderId, nack);
        }
    }

    public synchronized void forceSync(long catchUpSeq) {
        // Jump the counter to what the Leader tells us
        // We add 1 because the Leader sends the "Current" ID (e.g., 50), 
        // so we want to be ready for the NEXT one (51).
        this.nextExpectedSeq = catchUpSeq + 1;
        
        // Clear any old junk we might have buffered while waiting
        queue.clear(); 
        
        System.out.println("[Queue] Synced! Jumped to Sequence #" + nextExpectedSeq);
    }

private void deliverToApp(GameMessage msg) {
        // UI Updates
        if (clientGame != null) {
            switch (msg.type) {
                case COMMUNITY_CARDS: 
                    clientGame.onReceiveCommunity(msg.payload); 
                    break;
                case GAME_STATE:      
                    clientGame.onReceiveState(msg.payload); 
                    break;
                default:
                    break;
            }
        }

        // Always trigger logic callback
        if (onMessageReceived != null) {
            onMessageReceived.accept(msg);
        }
    }
}