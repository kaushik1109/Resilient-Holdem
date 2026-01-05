import networking.ConnectionManager;
import networking.DiscoveryService;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        int myTcpPort = 5000 + new Random().nextInt(1000);
        System.out.println("--- Resilient Hold'em Node Started (ID: " + myTcpPort + ") ---");

        // 1. Initialize TCP Layer
        ConnectionManager tcpLayer = new ConnectionManager(myTcpPort);
        tcpLayer.start();

        // 2. Initialize UDP Layer with reference to TCP
        DiscoveryService udpLayer = new DiscoveryService(myTcpPort, tcpLayer);
        udpLayer.start();

        // Keep alive
        while(true) { try { Thread.sleep(1000); } catch (Exception e){} }
    }
}