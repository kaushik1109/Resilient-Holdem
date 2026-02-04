package consensus;

import networking.UdpMulticastManager;
import networking.GameMessage;
import networking.TcpMeshManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printElection;

public class Sequencer {
    private UdpMulticastManager udpLayer;
    
    private AtomicLong globalSequenceId = new AtomicLong(0);
    
    private ConcurrentHashMap<Long, GameMessage> historyBuffer = new ConcurrentHashMap<>();
    
    private TcpMeshManager tcpLayer;

    public Sequencer(UdpMulticastManager udpLayer, TcpMeshManager tcp) {
        this.udpLayer = udpLayer;
        this.tcpLayer = tcp;
    }

    public long getCurrentSeqId() {
        return globalSequenceId.get();
    }

    public void resetSeqId() {
        globalSequenceId = new AtomicLong(0);
    }

    public void multicastAction(GameMessage originalRequest) {
        long seqId = globalSequenceId.incrementAndGet();

        GameMessage.Type typeToSend = originalRequest.type;
        if (typeToSend == GameMessage.Type.ACTION_REQUEST) {
            typeToSend = GameMessage.Type.PLAYER_ACTION;
        }

        GameMessage orderedMsg = new GameMessage(typeToSend, originalRequest.payload, seqId);

        historyBuffer.put(seqId, orderedMsg);
        printElection("[Sequencer] Multicasting #" + seqId + " (" + typeToSend + ").");

        udpLayer.sendMulticast(orderedMsg); 
    }

    public void handleNack(GameMessage nackMsg, String requestorId) {
        try {
            long missingSeq = Long.parseLong(nackMsg.payload);
            printElection("[Sequencer] Node " + requestorId + "requesting retransmission of #" + missingSeq);
            
            if (historyBuffer.containsKey(missingSeq)) {
                GameMessage oldMsg = historyBuffer.get(missingSeq);
                
                printElection("[Sequencer] Resending #" + missingSeq + " to Node " + requestorId);
                
                tcpLayer.sendToPeer(requestorId, oldMsg);
            } else {
                printError("[Sequencer] Cannot repair #" + missingSeq + " (Not in history)");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}