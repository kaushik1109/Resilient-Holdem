import org.junit.jupiter.api.*;
import java.io.File;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class IntegrationClusterTests {

  private static final File ROOT = new File(".");
  private Process n1, n2, n3;

  @AfterEach
  void cleanup() {
    TestUtils.destroy(n1);
    TestUtils.destroy(n2);
    TestUtils.destroy(n3);
  }

  @Test
  void singleNodeBootsAndStaysAliveBriefly() {
    n1 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);

    var out = TestUtils.readLines(n1, Duration.ofSeconds(3));
    assertTrue(n1.isAlive(), "Node should remain alive after 3 seconds");
    assertFalse(TestUtils.containsAny(out, "Exception", "ERROR", "FATAL"),
        "Startup should not immediately error. Logs:\n" + out);
  }

  @Test
  void threeNodesBootWithoutImmediateFailures() {
    n1 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);
    n2 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);
    n3 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);

    var o1 = TestUtils.readLines(n1, Duration.ofSeconds(5));
    var o2 = TestUtils.readLines(n2, Duration.ofSeconds(5));
    var o3 = TestUtils.readLines(n3, Duration.ofSeconds(5));

    assertTrue(n1.isAlive() && n2.isAlive() && n3.isAlive(), "All nodes should be alive after 5s");
    assertFalse(TestUtils.containsAny(o1, "Exception", "ERROR", "FATAL"), "n1 logs:\n" + o1);
    assertFalse(TestUtils.containsAny(o2, "Exception", "ERROR", "FATAL"), "n2 logs:\n" + o2);
    assertFalse(TestUtils.containsAny(o3, "Exception", "ERROR", "FATAL"), "n3 logs:\n" + o3);
  }

  @Test
  void killingOneNodeDoesNotCrashOthersImmediately() {
    n1 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);
    n2 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);
    n3 = TestUtils.startNode(List.of("java", "-cp", "bin", "Main"), Map.of(), ROOT);

    TestUtils.readLines(n1, Duration.ofSeconds(2));
    TestUtils.readLines(n2, Duration.ofSeconds(2));
    TestUtils.readLines(n3, Duration.ofSeconds(2));

    // kill one
    TestUtils.destroy(n1);

    var o2 = TestUtils.readLines(n2, Duration.ofSeconds(3));
    var o3 = TestUtils.readLines(n3, Duration.ofSeconds(3));

    assertTrue(n2.isAlive() && n3.isAlive(), "Remaining nodes should stay alive");
    assertFalse(TestUtils.containsAny(o2, "Exception", "FATAL"), "n2 logs:\n" + o2);
    assertFalse(TestUtils.containsAny(o3, "Exception", "FATAL"), "n3 logs:\n" + o3);
  }
}