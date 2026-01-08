package networking;

import java.io.Serializable;

public class GameMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        JOIN_REQUEST, JOIN_ACCEPT,
        HEARTBEAT,
        ELECTION, ELECTION_OK, COORDINATOR,

        LEAVE, //TODO: not actually sent
        
        ACTION_REQUEST,     // "Leader, please broadcast 'Bet 20' for me"
        ORDERED_MULTICAST,   // "Here is 'Bet 20', Sequence #5"

        NACK,          // "I missed a packet!"
        RETRANSMIT,     // "Here is the packet you missed."
        
        // SERVER -> CLIENT

        YOUR_HAND,         // "Psst, here are your two hole cards" (TCP)
        COMMUNITY_CARDS,   // "Here is the Flop/Turn/River" (UDP)
        PLAYER_ACTION,     // "Player 5001 bet 20" (UDP)
        GAME_STATE,        // "Pot is now 500, Current Player is 5002" (UDP)
        SHOWDOWN           // "Game over, here are all hands" (UDP)
    }

    public Type type;
    public String senderAddress; 
    public int tcpPort;          
    public String payload;
    
    // NEW FIELD: Global Sequence Number (Default -1 if not ordered yet)
    public long sequenceNumber = -1; 

    public GameMessage(Type type, String senderAddress, int tcpPort, String payload) {
        this.type = type;
        this.senderAddress = senderAddress;
        this.tcpPort = tcpPort;
        this.payload = payload;
    }
    
    // Helper constructor for ordered messages
    public GameMessage(Type type, String senderAddress, int tcpPort, String payload, long sequenceNumber) {
        this(type, senderAddress, tcpPort, payload);
        this.sequenceNumber = sequenceNumber;
    }
}