import game.NodeContext;
import networking.GameMessage;
import java.util.Scanner;
import java.util.Random;
import game.ClientGameState;

public class Main {
    public static void main(String[] args) {
        int myPort = 5000 + new Random().nextInt(1000);
        NodeContext node = new NodeContext(myPort);
        node.start();

        new Thread(() -> {
            try { Thread.sleep(5000); } catch (Exception e) {}
            if (node.election.iAmLeader && node.getServerGame() == null) {
                node.createServerGame();
            }
        }).start();

        ClientGameState.handleUserCommands(node, myPort);
    }
}