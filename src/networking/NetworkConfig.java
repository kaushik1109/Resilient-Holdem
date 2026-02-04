package networking;

import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Enumeration;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Properties;
import java.util.Random;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printNetworking;

public class NetworkConfig {
    public static String MULTICAST_GROUP = "239.255.1.1";
    public static int MULTICAST_PORT = 8888;
    public static int MULTICAST_TTL = 1;
    public static int MY_PORT = 5000 + new Random().nextInt(1000);
    public static String MY_IP;
    public static NetworkInterface MY_INTERFACE;

    public static void load() {
        Properties props = new Properties();

        try (FileInputStream fis = new FileInputStream("network.config")) {
            props.load(fis);
            printNetworking("[Config] Loaded network.config");
        } catch (Exception e) {
            printError("[Config] No network.config found, using defaults.");
            return;
        }

        MULTICAST_GROUP = props.getProperty("multicast.group", MULTICAST_GROUP);

        MULTICAST_PORT = Integer.parseInt(props.getProperty("multicast.port", String.valueOf(MULTICAST_PORT)));

        MULTICAST_TTL = Integer.parseInt(props.getProperty("multicast.ttl", String.valueOf(MULTICAST_TTL)));

        MY_PORT = Integer.parseInt(props.getProperty("port", String.valueOf(MY_PORT)));


        try {
            MY_INTERFACE = findValidNetworkInterface();
            MY_IP = getIpFromInterface();
        } catch (Exception e) {
            MY_IP = "unknown";
        }
    }

    private NetworkConfig() {}

    public static String getIpFromInterface() {
        if (MY_INTERFACE == null) return "127.0.0.1";

        Enumeration<InetAddress> addresses = MY_INTERFACE.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress addr = addresses.nextElement();
            
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                return addr.getHostAddress();
            }
        }

        return "127.0.0.1";
    }

    private static NetworkInterface findValidNetworkInterface() throws SocketException {
        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        NetworkInterface bestCandidate = null;

        for (NetworkInterface netIf : java.util.Collections.list(nets)) {
            if (!netIf.isUp()) continue;
            if (!netIf.supportsMulticast()) continue;
            if (netIf.isLoopback()) continue;

            String name = netIf.getName().toLowerCase();
            String displayName = netIf.getDisplayName().toLowerCase();

            if (displayName.contains("vmware") || name.contains("vmnet")) continue;
            if (displayName.contains("virtual") || name.contains("vbox")) continue;
            if (displayName.contains("pseudo") || name.contains("tunnel")) continue;
            if (displayName.contains("wsl") || displayName.contains("hyper-v")) continue;

            boolean hasIpv4 = netIf.getInterfaceAddresses().stream().anyMatch(addr -> addr.getAddress() instanceof Inet4Address);
            
            if (!hasIpv4) continue;

            if (displayName.contains("wi-fi") || displayName.contains("wlan") || name.startsWith("en")) {
                printNetworking("[UDP] Selected interface: " + displayName);
                return netIf;
            }
            
            if (bestCandidate == null) {
                bestCandidate = netIf;
            }
        }
        
        if (bestCandidate != null) {
            printNetworking("[UDP] Selected backup interface: " + bestCandidate.getDisplayName());
            return bestCandidate;
        }
        
        printError("[UDP] No valid WiFi/Ethernet found. Falling back to Loopback.");
        return NetworkInterface.getByName("127.0.0.1");
    }
    
}
