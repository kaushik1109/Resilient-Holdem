import networking.ConnectionManager;
import networking.DiscoveryService;
import consensus.ElectionManager;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        // Random ID for testing
        int myTcpPort = 5000 + new Random().nextInt(1000);
        System.out.println("--- Node Started (ID: " + myTcpPort + ") ---");

        ConnectionManager tcpLayer = new ConnectionManager(myTcpPort);
        tcpLayer.start();

        // Pass Election Logic into TCP Layer
        ElectionManager election = new ElectionManager(myTcpPort, tcpLayer);
        tcpLayer.setElectionManager(election);

        DiscoveryService udpLayer = new DiscoveryService(myTcpPort, tcpLayer);
        udpLayer.start();

        // Start the process
        election.startStabilizationPeriod();

        while(true) { try { Thread.sleep(1000); } catch (Exception e){} }
    }
}