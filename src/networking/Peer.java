package networking;

import java.io.ObjectOutputStream;
import java.net.Socket;

public class Peer {
    public String ipAddress;
    public int peerId; // This is the remote TCP Port (Process ID)
    public Socket socket;
    public ObjectOutputStream out;

    public Peer(String ipAddress, int peerId, Socket socket, ObjectOutputStream out) {
        this.ipAddress = ipAddress;
        this.peerId = peerId;
        this.socket = socket;
        this.out = out;
    }
}