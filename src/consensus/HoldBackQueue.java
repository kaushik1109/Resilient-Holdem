package consensus;

import networking.GameMessage;
import networking.TcpMeshManager;
import game.ClientGameState;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.function.Consumer;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printConsensus;

/**
 * Implements a hold-back queue to ensure in-order delivery of game messages.
 * Buffers out-of-order messages and requests retransmission of missing messages via NACKs.
 */
public class HoldBackQueue {
    private PriorityQueue<GameMessage> queue = new PriorityQueue<>(
        Comparator.comparingLong(msg -> msg.sequenceNumber)
    );
    
    private long nextExpectedSeq = 1;
    private TcpMeshManager tcpLayer; 
    private ClientGameState clientGame;
    private String leaderId = null;
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

    public void setLeaderId(String leaderId) {
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

    /**
     * Processes the hold-back queue to deliver messages in order.
     * Sends NACKs for any missing messages.
     */
    private void processQueue() {
        if (isProcessing) return; 
        isProcessing = true;

        try {
            while (!queue.isEmpty()) {
                GameMessage head = queue.peek();
                printConsensus("[Queue] Processing #" + head.sequenceNumber + ", expecting #" + nextExpectedSeq);

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
        printError("[Queue] Sending NACK for #" + missingSeq);
        tcpLayer.sendNack(leaderId, missingSeq);
    }

    /**
     * Forces the hold-back queue to synchronize to a specific sequence number.
     * Clears any buffered messages and updates the next expected sequence.
     * @param catchUpSeq The sequence number to synchronize to.
     */
    public synchronized void forceSync(long catchUpSeq) {
        printConsensus("[Queue] Syncing queue to Sequence #" + catchUpSeq);
        this.nextExpectedSeq = catchUpSeq + 1;
        queue.clear(); 
    }

    /**
     * Delivers a message to the application layer (ClientGameState).
     * @param msg The GameMessage being delivered.
     */
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