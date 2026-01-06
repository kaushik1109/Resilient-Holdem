package consensus;

import networking.UdpMulticastManager;
import networking.GameMessage;
import networking.TcpMeshManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

public class Sequencer {
    private UdpMulticastManager udpLayer;
    
    // Global Sequence Counter (Only meaningful if I am Leader)
    private AtomicLong globalSequenceId = new AtomicLong(0);
    
    // History Buffer for re-transmissions (Phase 4 requirement)
    private ConcurrentHashMap<Long, GameMessage> historyBuffer = new ConcurrentHashMap<>();

    private HoldBackQueue localQueue; 
    
    private TcpMeshManager tcpLayer;

    public Sequencer(UdpMulticastManager udpLayer) {
        this.udpLayer = udpLayer;
    }

    public void setLocalQueue(HoldBackQueue q) {
        this.localQueue = q;
    }
    
    public void setTcpLayer(TcpMeshManager tcp) {
        this.tcpLayer = tcp;
    }

    /**
     * Called when we (The Leader) receive a request to broadcast an action.
     * e.g. "Peer A wants to Bet 20"
     */
    public void multicastAction(GameMessage originalRequest) {
        // 1. Stamp the ticket (Assign Sequence ID)
        long seqId = globalSequenceId.incrementAndGet();
        
        // 2. Create the ORDERED packet
        // We preserve the original sender's info so peers know who bet
        GameMessage orderedMsg = new GameMessage(
            GameMessage.Type.ORDERED_MULTICAST,
            originalRequest.senderAddress,
            originalRequest.tcpPort,
            originalRequest.payload,
            seqId
        );

        // 3. Save to History (for fault tolerance later)
        historyBuffer.put(seqId, orderedMsg);
        
        System.out.println("[Sequencer] Broadcasting Action #" + seqId + ": " + originalRequest.payload);
        
        
        // 3.5 Send to self first 
        if (localQueue != null) {
            localQueue.addMessage(orderedMsg);
        }

        // 4. Send via UDP
        udpLayer.sendMulticast(orderedMsg);
    }

    public void handleNack(GameMessage nackMsg, int requestorId) {
        try {
            long missingSeq = Long.parseLong(nackMsg.payload);
            
            if (historyBuffer.containsKey(missingSeq)) {
                GameMessage oldMsg = historyBuffer.get(missingSeq);
                
                System.out.println("[Sequencer] TCP Repair: Resending #" + missingSeq + " to Node " + requestorId);
                
                // USE TCP UNICAST instead of UDP Multicast for a retransmission
                tcpLayer.sendToPeer(requestorId, oldMsg);
                
            } else {
                System.out.println("[Sequencer] Cannot repair #" + missingSeq + " (Not in history)");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}