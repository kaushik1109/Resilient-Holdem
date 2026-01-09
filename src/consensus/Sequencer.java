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
    
    public long getCurrentSeqId() {
        return globalSequenceId.get();
    }

    public void multicastAction(GameMessage originalRequest) {
        long seqId = globalSequenceId.incrementAndGet();

        // FIX 1: Preserve the Original Type (don't force ORDERED_MULTICAST)
        // We only change it if it was a raw ACTION_REQUEST
        GameMessage.Type typeToSend = originalRequest.type;
        if (typeToSend == GameMessage.Type.ACTION_REQUEST) {
            typeToSend = GameMessage.Type.PLAYER_ACTION;
        }

        GameMessage orderedMsg = new GameMessage(
            typeToSend, // Use the smart type
            originalRequest.senderAddress,
            originalRequest.tcpPort,
            originalRequest.payload,
            seqId
        );

        historyBuffer.put(seqId, orderedMsg);
        System.out.println("[Sequencer] Broadcasting #" + seqId + " (" + typeToSend + "): " + originalRequest.payload);

        // FIX 2: Network FIRST, Local SECOND
        // This prevents "Nested Multicast" where inner messages go out before outer ones
        udpLayer.sendMulticast(orderedMsg); 
        
        if (localQueue != null) {
            localQueue.addMessage(orderedMsg);
        }
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