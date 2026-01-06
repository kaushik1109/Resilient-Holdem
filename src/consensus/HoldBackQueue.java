package consensus;

import networking.GameMessage;
import networking.TcpMeshManager; 
import java.util.PriorityQueue;
import java.util.Comparator;

public class HoldBackQueue {
    private PriorityQueue<GameMessage> queue = new PriorityQueue<>(
        Comparator.comparingLong(msg -> msg.sequenceNumber)
    );
    
    private long nextExpectedSeq = 1;
    private TcpMeshManager tcpLayer; // Reference to send NACKs
    private int leaderId = -1;       // Who do we complain to?

    public void setTcpLayer(TcpMeshManager tcp) { this.tcpLayer = tcp; }
    public void setLeaderId(int id) { this.leaderId = id; }

    public synchronized void addMessage(GameMessage msg) {
        // If this is a Retransmission, handle it like a normal multicast
        queue.add(msg);
        processQueue();
    }

    private void processQueue() {
        while (!queue.isEmpty()) {
            GameMessage head = queue.peek();

            if (head.sequenceNumber == nextExpectedSeq) {
                queue.poll();
                deliverToApp(head);
                nextExpectedSeq++;
            } 
            else if (head.sequenceNumber < nextExpectedSeq) {
                queue.poll(); // Duplicate, ignore
            } 
            else {
                // GAP DETECTED!
                // Head is #5, we expect #4. 
                // We should ask for #4.
                sendNack(nextExpectedSeq);
                break; // Stop processing
            }
        }
    }
    
    private void sendNack(long missingSeq) {
        if (tcpLayer != null && leaderId != -1) {
            System.out.println("[Queue] Sending NACK for #" + missingSeq + " to Leader " + leaderId);
            GameMessage nack = new GameMessage(
                GameMessage.Type.NACK, 
                "local", 
                0, 
                String.valueOf(missingSeq) // Payload = ID we are missing
            );
            tcpLayer.sendToPeer(leaderId, nack);
        }
    }

    private void deliverToApp(GameMessage msg) {
        System.out.println(">>> GAME ENGINE: Processing " + msg.payload + " (Seq: " + msg.sequenceNumber + ")");
    }
}