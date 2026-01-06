package networking;

import java.io.Serializable;

// This class defines the data packet sent between nodes
public class GameMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        JOIN_REQUEST,  
        JOIN_ACCEPT,

        ELECTION,       
        ELECTION_OK,    
        COORDINATOR, 
           
        HEARTBEAT,     
        GAME_ACTION   
    }

    public Type type;
    public String senderAddress; 
    public int tcpPort;          
    public String payload;

    public GameMessage(Type type, String senderAddress, int tcpPort, String payload) {
        this.type = type;
        this.senderAddress = senderAddress;
        this.tcpPort = tcpPort;
        this.payload = payload;
    }
}
