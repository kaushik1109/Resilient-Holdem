package networking;

import java.io.Serializable;
import java.util.Objects;

public class GameMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        JOIN_REQUEST,
        HEARTBEAT,
        ELECTION, 
        ELECTION_OK, 
        COORDINATOR,
        HANDOVER,
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
    public String senderIp;
    public int senderPort;

    public String payload;
    
    public long sequenceNumber = -1; 

    public GameMessage(Type type) {
        this.type = type;
        this.senderIp = NetworkConfig.MY_IP;
        this.senderPort = NetworkConfig.MY_PORT;
    }

    public GameMessage(Type type, String payload) {
        this(type);
        this.payload = payload;
    }

    public GameMessage(Type type, String payload, long seq) {
        this(type, payload);
        this.sequenceNumber = seq;
    }

    public String getSenderId() {
        return senderIp + ":" + senderPort;
    }

    public int getSenderHash() {
        return Objects.hash(getSenderId());
    }
}