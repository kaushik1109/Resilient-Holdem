import game.NodeContext;
import networking.NetworkConfig;

public class Main {
    public static void main(String[] args) {
        NetworkConfig.load();
        NodeContext node = new NodeContext();
        
        node.start();
    }
}