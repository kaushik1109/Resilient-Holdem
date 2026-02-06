import org.junit.jupiter.api.*;

import consensus.ElectionManager;
import consensus.HoldBackQueue;
import consensus.Sequencer;
import game.NodeContext;
import networking.GameMessage;

import static org.junit.jupiter.api.Assertions.*;

public class NodeContextRoutingTests {

  // Dummy queue: capture add/sync/leaderId
  static class DummyQueue extends HoldBackQueue {
    public String leaderSet = null;
    public long forceSyncTo = Long.MIN_VALUE;
    public int addedCount = 0;
    public GameMessage lastAdded = null;

    @Override public synchronized void addMessage(GameMessage msg) {
      addedCount++;
      lastAdded = msg;
    }

    @Override public synchronized void forceSync(long catchUpSeq) {
      forceSyncTo = catchUpSeq;
    }

    @Override public void setLeaderId(String leaderId) {
      leaderSet = leaderId;
    }
  }

  // Dummy sequencer: capture handleNack
  static class DummySequencer extends Sequencer {
    public int nackCalls = 0;
    public GameMessage lastNack = null;
    public String lastReqId = null;

    public DummySequencer() { super(null, null); } // never called (Unsafe used)

    @Override
    public void handleNack(GameMessage nackMsg, String requestorId) {
      nackCalls++;
      lastNack = nackMsg;
      lastReqId = requestorId;
    }
  }

  private NodeContext ctx;
  private TestDoubles.DummyTcp tcp;
  private TestDoubles.DummyUdp udp;
  private ElectionManager election;
  private DummySequencer sequencer;
  private DummyQueue queue;

  @BeforeEach
  void setup() {
    ctx = TestDoubles.allocate(NodeContext.class);

    tcp = TestDoubles.allocate(TestDoubles.DummyTcp.class).init();
    udp = TestDoubles.allocate(TestDoubles.DummyUdp.class).init();

    // ElectionManager signature changed: (NodeContext, TcpMeshManager)
    election = new ElectionManager(ctx, tcp);

    sequencer = TestDoubles.allocate(DummySequencer.class);
    queue = new DummyQueue();

    // Inject NodeContext finals
    TestDoubles.setField(ctx, "myId", "127.0.0.1:5000");
    TestDoubles.setField(ctx, "myIdHash", java.util.Objects.hash("127.0.0.1:5000"));
    TestDoubles.setField(ctx, "tcp", tcp);
    TestDoubles.setField(ctx, "udp", udp);
    TestDoubles.setField(ctx, "election", election);
    TestDoubles.setField(ctx, "sequencer", sequencer);
    TestDoubles.setField(ctx, "queue", queue);

    // Don’t touch app/game in these tests
    TestDoubles.setField(ctx, "clientGame", null);
    TestDoubles.setField(ctx, "serverGame", null);
    TestDoubles.setField(ctx, "dropNext", false);
  }

  @Test
  void nackRoutesToSequencerHandleNack() {
    GameMessage nack = new GameMessage(GameMessage.Type.NACK, "7");
    nack.senderIp = "127.0.0.1";
    nack.senderPort = 6001;

    ctx.routeMessage(nack);

    assertEquals(1, sequencer.nackCalls);
    assertEquals("127.0.0.1:6001", sequencer.lastReqId);
    assertEquals("7", sequencer.lastNack.payload);
  }

  @Test
  void syncMessageCallsForceSync() {
    GameMessage sync = new GameMessage(GameMessage.Type.SYNC, "50");
    ctx.routeMessage(sync);

    assertEquals(50L, queue.forceSyncTo);
  }

  @Test
  void orderedMessagesWithPositiveSeqGoToHoldBackQueue() {
    GameMessage m = new GameMessage(GameMessage.Type.GAME_INFO, "info", 10);
    ctx.routeMessage(m);

    assertEquals(1, queue.addedCount);
    assertEquals(10, queue.lastAdded.sequenceNumber);
  }

  @Test
  void dropNextDropsNextSequencedMessageOnly() {
    TestDoubles.setField(ctx, "dropNext", true);

    GameMessage m1 = new GameMessage(GameMessage.Type.GAME_INFO, "x", 5);
    ctx.routeMessage(m1);
    assertEquals(0, queue.addedCount);

    GameMessage m2 = new GameMessage(GameMessage.Type.GAME_INFO, "y", 6);
    ctx.routeMessage(m2);
    assertEquals(1, queue.addedCount);
  }

  @Test
  void coordinatorSetsLeaderOnQueueAndElection() {
    GameMessage coord = new GameMessage(GameMessage.Type.COORDINATOR);
    coord.senderIp = "127.0.0.1";
    coord.senderPort = 7000;

    ctx.routeMessage(coord);

    assertEquals("127.0.0.1:7000", election.currentLeaderId);
    assertEquals("127.0.0.1:7000", queue.leaderSet);
  }

  @Test
  void leaveRoutesToOnPeerDisconnectedAndClosesTcpConnection() {
    GameMessage leave = new GameMessage(GameMessage.Type.LEAVE);
    leave.senderIp = "127.0.0.1";
    leave.senderPort = 8000;

    ctx.routeMessage(leave);

    assertTrue(tcp.closed.contains("127.0.0.1:8000"));
  }
}