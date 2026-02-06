import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import consensus.Sequencer;
import networking.GameMessage;

public class SequencerTests {

  private TestDoubles.DummyUdp udp;
  private TestDoubles.DummyTcp tcp;
  private Sequencer sequencer;

  @BeforeEach
  void setup() {
    udp = TestDoubles.allocate(TestDoubles.DummyUdp.class).init();
    tcp = TestDoubles.allocate(TestDoubles.DummyTcp.class).init();
    sequencer = new Sequencer(udp, tcp);
  }

  @Test
  void startsAtZero() {
    assertEquals(0, sequencer.getCurrentSeqId());
  }

  @Test
  void multicastActionIncrementsSequenceAndSendsUdp() {
    GameMessage req = new GameMessage(GameMessage.Type.PLAYER_ACTION, "bet 10");
    req.senderPort = 1111;

    sequencer.multicastAction(req);

    assertEquals(1, sequencer.getCurrentSeqId());
    assertEquals(1, udp.multicasts.size());

    GameMessage sent = udp.multicasts.get(0);
    assertEquals(1, sent.sequenceNumber);
    assertEquals("bet 10", sent.payload);
  }

  @Test
  void actionRequestIsConvertedToPlayerAction() {
    GameMessage req = new GameMessage(GameMessage.Type.ACTION_REQUEST, "raise 5");
    req.senderPort = 2222;

    sequencer.multicastAction(req);

    GameMessage sent = udp.multicasts.get(0);
    assertEquals(GameMessage.Type.PLAYER_ACTION, sent.type);
    assertEquals("raise 5", sent.payload);
  }

  @Test
  void handleNackResendsIfInHistoryElseDoesNothing() {
    // Create seq #1 in history
    GameMessage req = new GameMessage(GameMessage.Type.PLAYER_ACTION, "call");
    req.senderPort = 3333;
    sequencer.multicastAction(req);

    // NACK asking for #1
    GameMessage nack = new GameMessage(GameMessage.Type.NACK, "1");
    nack.senderIp = "127.0.0.1";
    nack.senderPort = 5002;

    // IMPORTANT: signature is (GameMessage, String requestorId)
    sequencer.handleNack(nack, nack.getSenderId());

    assertEquals(1, tcp.sentToPeer.size(), "Should resend missing message via TCP");
    assertEquals(1, tcp.sentToPeer.get(0).msg.sequenceNumber);

    // NACK asking for missing #999 (not in history)
    GameMessage nack2 = new GameMessage(GameMessage.Type.NACK, "999");
    nack2.senderIp = "127.0.0.1";
    nack2.senderPort = 5002;

    sequencer.handleNack(nack2, nack2.getSenderId());

    assertEquals(1, tcp.sentToPeer.size(), "Should not resend when not in history");
  }
}