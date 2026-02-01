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
    public int tcpPort;
    public String senderIp;
    public int senderPort;
    public String senderId;
    public int senderHash;          
    public String payload;
    
    public long sequenceNumber = -1; 

    public GameMessage(Type type, int tcpPort) {
        this.type = type;
        this.tcpPort = tcpPort;
    }

    public GameMessage(Type type, int tcpPort, String payload) {
        this.type = type;
        this.tcpPort = tcpPort;
        this.payload = payload;
}

    public GameMessage(Type type, int tcpPort, String ip, String payload) {
        this(type, tcpPort);

        this.senderIp = ip;
        this.senderPort = tcpPort;
        this.senderId = ip + ":" + tcpPort;
        this.senderHash = java.util.Objects.hash(ip, tcpPort);

        this.payload = payload;
    }


    public GameMessage(Type type, int tcpPort, String payload, long sequenceNumber) {
        this(type, tcpPort, payload);
        this.sequenceNumber = sequenceNumber;
    }
}