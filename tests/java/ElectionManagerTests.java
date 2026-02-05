import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import consensus.ElectionManager;
import game.NodeContext;
import networking.GameMessage;
import game.ClientGameState;
import consensus.Sequencer;

public class ElectionManagerTests {

  private NodeContext ctx;
  private TestDoubles.DummyTcp tcp;

  @BeforeEach
  void setup() {
    ctx = TestDoubles.allocate(NodeContext.class);
    // set myId so election compares against it
    TestDoubles.setField(ctx, "myId", "127.0.0.1:5000");
    TestDoubles.setField(ctx, "myIdHash", java.util.Objects.hash("127.0.0.1:5000"));
    TestDoubles.setField(ctx, "clientGame", new ClientGameState());

    tcp = TestDoubles.allocate(TestDoubles.DummyTcp.class).init();
    var udp = TestDoubles.allocate(TestDoubles.DummyUdp.class).init();

    TestDoubles.setField(ctx, "tcp", tcp);
    TestDoubles.setField(ctx, "udp", udp);
    TestDoubles.setField(ctx, "sequencer", new Sequencer(udp, tcp));
  }

  @Test
  void startElectionChallengesOnlyHigherIds() {
    tcp.peers.add("127.0.0.1:4000");
    tcp.peers.add("127.0.0.1:6000"); // higher port than me => "higher"
    tcp.peers.add("127.0.0.1:3000");

    ElectionManager em = new ElectionManager(ctx, tcp);
    em.startElection("test");

    assertTrue(tcp.sentToPeer.stream().anyMatch(s ->
        s.peerId.equals("127.0.0.1:6000") && s.msg.type == GameMessage.Type.ELECTION
    ));
  }
  
  @Test
  void receivingElectionFromLowerSendsOkAndStartsElection() {
    tcp.peers.add("127.0.0.1:6000"); // higher exists

    ElectionManager em = new ElectionManager(ctx, tcp);

    GameMessage election = new GameMessage(GameMessage.Type.ELECTION);
    election.senderIp = "127.0.0.1";
    election.senderPort = 3000; // lower
    em.handleMessage(election);

    assertTrue(tcp.sentToPeer.stream().anyMatch(s ->
        s.peerId.equals("127.0.0.1:3000") && s.msg.type == GameMessage.Type.ELECTION_OK
    ));
    assertTrue(tcp.sentToPeer.stream().anyMatch(s ->
        s.peerId.equals("127.0.0.1:6000") && s.msg.type == GameMessage.Type.ELECTION
    ));
  }

  @Test
  void receivingCoordinatorUpdatesLeaderState() {
    ElectionManager em = new ElectionManager(ctx, tcp);

    GameMessage coord = new GameMessage(GameMessage.Type.COORDINATOR);
    coord.senderIp = "127.0.0.1";
    coord.senderPort = 6000;
    em.handleMessage(coord);

    assertEquals("127.0.0.1:6000", em.currentLeaderId);
    assertFalse(em.iAmLeader);

    GameMessage coord2 = new GameMessage(GameMessage.Type.COORDINATOR);
    coord2.senderIp = "127.0.0.1";
    coord2.senderPort = 5000;
    em.handleMessage(coord2);

    assertEquals("127.0.0.1:5000", em.currentLeaderId);
    assertTrue(em.iAmLeader);
  }

  @Test
  void leaderFailureTriggersElectionSoon() throws Exception {
    tcp.peers.add("127.0.0.1:6000");

    ElectionManager em = new ElectionManager(ctx, tcp);
    em.currentLeaderId = "127.0.0.1:7000";

    em.handleNodeFailure("127.0.0.1:7000");

    Thread.sleep(800);
    assertTrue(tcp.sentToPeer.stream().anyMatch(s ->
        s.peerId.equals("127.0.0.1:6000") && s.msg.type == GameMessage.Type.ELECTION
    ));
  }
}