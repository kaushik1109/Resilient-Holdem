package consensus;

import networking.GameMessage;
import networking.TcpMeshManager;
import game.ClientGameState;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.function.Consumer;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printConsensus;

/**
 * Implements a hold-back queue to ensure in-order delivery of game messages.
 * Buffers out-of-order messages and requests retransmission of missing messages via NACKs.
 */
public class HoldBackQueue {
    private final ScheduledExecutorService nackScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> nackTimer;
    private static final int NACK_DELAY_MS = 1000;

    private PriorityQueue<GameMessage> queue = new PriorityQueue<>(
        Comparator.comparingLong(msg -> msg.sequenceNumber)
    );
    
    private long nextExpectedSeq = 1;
    private TcpMeshManager tcp; 
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

    public void setTcp(TcpMeshManager tcpLayer) {
        this.tcp = tcpLayer;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public void setQueueAttributes(TcpMeshManager tcpLayer, ClientGameState clientGameState, Consumer<GameMessage> callback) {
        this.tcp = tcpLayer;
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
                    if (nackTimer != null && !nackTimer.isDone()) {
                        nackTimer.cancel(false);
                        nackTimer = null;
                        printConsensus("[Recovered] Packet #" + nextExpectedSeq + " arrived naturally. NACK cancelled.");
                    }

                    queue.poll();
                    nextExpectedSeq++; 
                    deliverToApp(head);
                } 
                else if (head.sequenceNumber < nextExpectedSeq) {
                    queue.poll();
                } 
                else {
                    if (nackTimer == null || nackTimer.isDone()) {
                        scheduleNack(nextExpectedSeq);
                    }
                    break;
                }
            }
        } finally {
            isProcessing = false;
        }
    }

    private void scheduleNack(long missingSeq) {
        printError("[Queue] Missing #" + missingSeq + ". Scheduling NACK in " + NACK_DELAY_MS + "ms");
        
        nackTimer = nackScheduler.schedule(() -> {
            printError("[Timeout] Gap #" + missingSeq + " persisted. Sending NACK now.");
            if (tcp != null && leaderId != null) {
                tcp.sendNack(leaderId, missingSeq);
            }
            nackTimer = null;
        }, NACK_DELAY_MS, TimeUnit.MILLISECONDS);
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

                case GAME_INFO:
                case SHOWDOWN:
                    clientGame.onReceiveInfo(msg.payload); 
                    break;

                case GAME_STATE:
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