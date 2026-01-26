package networking;

import java.io.Serializable;

public class GameMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        JOIN_REQUEST,
        HEARTBEAT,
        ELECTION, ELECTION_OK, COORDINATOR,
        SYNC,

        LEAVE,
        
        ACTION_REQUEST,
        ORDERED_MULTICAST,

        NACK,

        YOUR_HAND,
        COMMUNITY_CARDS,
        PLAYER_ACTION,
        GAME_STATE,
        SHOWDOWN
    }

    public Type type;
    public String senderAddress; 
    public int tcpPort;          
    public String payload;
    
    public long sequenceNumber = -1; 

    public GameMessage(Type type, String senderAddress, int tcpPort, String payload) {
        this.type = type;
        this.senderAddress = senderAddress;
        this.tcpPort = tcpPort;
        this.payload = payload;
    }
    
    public GameMessage(Type type, String senderAddress, int tcpPort, String payload, long sequenceNumber) {
        this(type, senderAddress, tcpPort, payload);
        this.sequenceNumber = sequenceNumber;
    }
}