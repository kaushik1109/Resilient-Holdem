package networking;

import java.io.FileInputStream;
import java.util.Properties;
import java.util.Random;

public class NetworkConfig {
    public static String MULTICAST_GROUP = "239.255.1.1";
    public static int MULTICAST_PORT = 8888;
    public static int MULTICAST_TTL = 1;
    public static int MY_PORT = 5000 + new Random().nextInt(1000);

    public static void load() {
        Properties props = new Properties();

        try (FileInputStream fis = new FileInputStream("network.config")) {
            props.load(fis);
            System.out.println("[Config] Loaded network.config");
        } catch (Exception e) {
            System.out.println("[Config] No network.config found, using defaults.");
            return;
        }

        MULTICAST_GROUP = props.getProperty("multicast.group", MULTICAST_GROUP);

        MULTICAST_PORT = Integer.parseInt(props.getProperty("multicast.port", String.valueOf(MULTICAST_PORT)));

        MULTICAST_TTL = Integer.parseInt(props.getProperty("multicast.ttl", String.valueOf(MULTICAST_TTL)));

        MY_PORT = Integer.parseInt(props.getProperty("port", String.valueOf(MY_PORT)));
    }

    private NetworkConfig() {}
    
}
