import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

import consensus.HoldBackQueue;
import networking.GameMessage;

public class HoldBackQueueTests {

  private HoldBackQueue q;
  private TestDoubles.DummyTcp tcp;
  private List<Long> deliveredSeqs;

  @BeforeEach
  void setup() {
    q = new HoldBackQueue();
    tcp = TestDoubles.allocate(TestDoubles.DummyTcp.class).init();
    deliveredSeqs = new ArrayList<>();

    // Leader id is now a String
    q.setLeaderId("127.0.0.1:9000");

    // Prefer the combined setup if it exists
    q.setQueueAttributes(tcp, null, (m) -> deliveredSeqs.add(m.sequenceNumber));
  }

  @Test
  void deliversInOrderWhenInsertedOutOfOrder() {
    q.addMessage(msg(GameMessage.Type.GAME_STATE, 1, "a"));
    q.addMessage(msg(GameMessage.Type.GAME_STATE, 3, "c"));
    q.addMessage(msg(GameMessage.Type.GAME_STATE, 2, "b"));

    assertEquals(List.of(1L, 2L, 3L), deliveredSeqs);
  }

  @Test
  void doesNotDeliverPastGapAndSendsNack() {
    q.addMessage(msg(GameMessage.Type.GAME_STATE, 1, "a"));
    q.addMessage(msg(GameMessage.Type.GAME_STATE, 3, "c"));

    assertEquals(List.of(1L), deliveredSeqs);
    assertEquals(1, tcp.nacks.size());
    assertEquals("127.0.0.1:9000", tcp.nacks.get(0).leaderId);
    assertEquals(2L, tcp.nacks.get(0).missingSeq);
  }

  private GameMessage msg(GameMessage.Type t, long seq, String payload) {
    GameMessage m = new GameMessage(t, payload, seq);
    m.senderIp = "127.0.0.1";
    m.senderPort = 1111;
    return m;
  }
}