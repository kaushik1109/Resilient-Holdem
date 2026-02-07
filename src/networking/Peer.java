package networking;

import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a peer in the TCP mesh network, encapsulating connection details and state.
 */
public class Peer {
    public String peerId;
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

    public static long getPeerHash(String peerId, String myId, Set<String> peerIds, int roundNumber) {
        String[] parts = peerId.split(":");
        long ipAsNumber = Integer.parseInt(parts[0].replaceAll("\\.", ""));
        int port = Integer.parseInt(parts[1]);

        long hash = ipAsNumber + port;

        List<String> peerIdList = new ArrayList<>(peerIds);
        peerIdList.add(myId);
        Collections.sort(peerIdList);

        int peerIdx = peerIdList.indexOf(peerId);

        if (peerIdx == (roundNumber%peerIds.size())) {
            hash += 999999999999L; 
        }

        return hash;
    }
}
