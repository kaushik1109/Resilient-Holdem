import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import networking.GameMessage;

public class GameMessageTests {

  @Test
  void constructorSetsFields() {
    GameMessage m = new GameMessage(GameMessage.Type.GAME_STATE, "payload", 7);
    assertEquals(GameMessage.Type.GAME_STATE, m.type);
    assertEquals("payload", m.payload);
    assertEquals(7, m.sequenceNumber);
  }

  @Test
  void senderIdIsIpColonPort() {
    GameMessage m = new GameMessage(GameMessage.Type.HEARTBEAT);
    m.senderIp = "127.0.0.1";
    m.senderPort = 5000;
    assertEquals("127.0.0.1:5000", m.getSenderId());
  }
}