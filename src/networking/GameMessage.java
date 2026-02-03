package networking;

import java.io.Serializable;
import java.util.Objects;

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
    public int tcpPort;
    public String senderIp;
    public int senderPort;
    public String senderId;
    public int senderHash;

    public String payload;
    
    public long sequenceNumber = -1; 

    public GameMessage(Type type, int tcpPort, String senderIp) {
        this.type = type;
        this.tcpPort = tcpPort;
        this.senderIp = senderIp;
        this.senderPort = tcpPort;
        this.senderId = senderIp + ":" + tcpPort;
        this.senderHash = Objects.hash(senderId);
    }

    public GameMessage(Type type, int tcpPort, String senderIp, String payload) {
        this(type, tcpPort, senderIp);
        this.payload = payload;
    }

    public GameMessage(Type type, int tcpPort, String senderIp, String payload, long seq) {
        this(type, tcpPort, senderIp, payload);
        this.sequenceNumber = seq;
    }
}