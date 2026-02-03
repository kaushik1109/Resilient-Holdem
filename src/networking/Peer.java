package networking;

import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;

public class Peer {
    public String peerId;          // EXACTLY "ip:port"
    public Socket socket;
    public ObjectOutputStream out;
    public int peerHash;
    public long lastSeenTimestamp;

    public Peer(String peerId, Socket socket, ObjectOutputStream out) {
        this.peerId = peerId;
        this.peerHash = Objects.hash(peerId);
        this.socket = socket;
        this.out = out;
        this.lastSeenTimestamp = System.currentTimeMillis();
    }
}
