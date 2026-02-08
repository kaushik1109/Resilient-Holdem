import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import networking.GameMessage;
import networking.TcpMeshManager;
import networking.UdpMulticastManager;
import game.NodeContext;

public final class TestDoubles {
  private TestDoubles() {}

  // Unsafe allocation: bypass constructors (avoids opening sockets)
  public static <T> T allocate(Class<T> cls) {
    try {
      var unsafeClass = Class.forName("sun.misc.Unsafe");
      Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object unsafe = theUnsafe.get(null);
      var allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
      @SuppressWarnings("unchecked")
      T obj = (T) allocateInstance.invoke(unsafe, cls);
      return obj;
    } catch (Exception e) {
      throw new RuntimeException("Failed to allocate " + cls.getName(), e);
    }
  }

  // ---------- Dummy UDP ----------
  public static class DummyUdp extends UdpMulticastManager {
    public List<GameMessage> multicasts;

    // only to satisfy compiler; never called (Unsafe used)
    public DummyUdp(NodeContext ctx) { super(ctx); }

    public DummyUdp init() {
      this.multicasts = new CopyOnWriteArrayList<>();
      return this;
    }

    @Override
    public void sendMulticast(GameMessage msg) {
      multicasts.add(msg);
    }
  }

  // ---------- Dummy TCP ----------
  public static class DummyTcp extends TcpMeshManager {
    public Set<String> peers;
    public List<Sent> sentToPeer;
    public List<GameMessage> multicasts;
    public List<Nack> nacks;
    public List<String> closed;

    public static class Sent {
      public final String peerId;
      public final GameMessage msg;
      public Sent(String peerId, GameMessage msg) { this.peerId = peerId; this.msg = msg; }
    }

    public static class Nack {
      public final String leaderId;
      public final long missingSeq;
      public Nack(String leaderId, long missingSeq) { this.leaderId = leaderId; this.missingSeq = missingSeq; }
    }

    // only to satisfy compiler; never called (Unsafe used)
    public DummyTcp(NodeContext ctx) { super(ctx); }

    public DummyTcp init() {
      this.peers = new LinkedHashSet<>();
      this.sentToPeer = new CopyOnWriteArrayList<>();
      this.multicasts = new CopyOnWriteArrayList<>();
      this.nacks = new CopyOnWriteArrayList<>();
      this.closed = new CopyOnWriteArrayList<>();
      return this;
    }

    @Override
    public Set<String> getConnectedPeerIds() {
      return peers;
    }

    @Override
    public void sendToPeer(String peerId, GameMessage msg) {
      sentToPeer.add(new Sent(peerId, msg));
    }

    @Override
    public void multicastToAll(GameMessage msg) {
      multicasts.add(msg);
    }

    @Override
    public void sendNack(String leaderId, long missingSeq) {
      nacks.add(new Nack(leaderId, missingSeq));
    }

    @Override
    public void closeConnection(String peerId) {
      closed.add(peerId);
    }
  }

  // Set private/final fields in NodeContext
  public static void setField(Object target, String name, Object value) {
    try {
      Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set field " + name + " on " + target.getClass(), e);
    }
  }
}