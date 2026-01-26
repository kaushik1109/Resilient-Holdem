package networking;

import java.io.ObjectOutputStream;
import java.net.Socket;

public class Peer {
    public String ipAddress;
    public int peerId; 
    public Socket socket;
    public ObjectOutputStream out;
    
    public long lastSeenTimestamp;

    public Peer(String ipAddress, int peerId, Socket socket, ObjectOutputStream out) {
        this.ipAddress = ipAddress;
        this.peerId = peerId;
        this.socket = socket;
        this.out = out;
        this.lastSeenTimestamp = System.currentTimeMillis();
    }
}