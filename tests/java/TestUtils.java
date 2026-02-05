import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class TestUtils {
  private TestUtils() {}

  // ---------- Reflection ----------
  public static Object newInstance(String fqcn, Class<?>[] sig, Object[] args) {
    try {
      Class<?> c = Class.forName(fqcn);
      Constructor<?> ctor = c.getDeclaredConstructor(sig);
      ctor.setAccessible(true);
      return ctor.newInstance(args);
    } catch (Exception e) {
      throw new RuntimeException("Failed to construct " + fqcn, e);
    }
  }

  public static Object call(Object target, String method, Class<?>[] sig, Object[] args) {
    try {
      Method m = target.getClass().getDeclaredMethod(method, sig);
      m.setAccessible(true);
      return m.invoke(target, args);
    } catch (Exception e) {
      throw new RuntimeException("Failed to call " + method + " on " + target.getClass(), e);
    }
  }

  public static Object getField(Object target, String field) {
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception e) {
      throw new RuntimeException("Failed to read field " + field + " on " + target.getClass(), e);
    }
  }

  // ---------- Process / logs ----------
  public static Process startNode(List<String> cmd, Map<String,String> env, File workDir) {
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      if (env != null) pb.environment().putAll(env);
      if (workDir != null) pb.directory(workDir);
      pb.redirectErrorStream(true);
      return pb.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static List<String> readLines(Process p, Duration forHowLong) {
    ExecutorService ex = Executors.newSingleThreadExecutor();
    try {
      Future<List<String>> fut = ex.submit(() -> {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          long deadline = System.currentTimeMillis() + forHowLong.toMillis();
          while (System.currentTimeMillis() < deadline && p.isAlive()) {
            while (br.ready()) {
              String line = br.readLine();
              if (line != null) lines.add(line);
            }
            Thread.sleep(50);
          }
          while (br.ready()) {
            String line = br.readLine();
            if (line != null) lines.add(line);
          }
        }
        return lines;
      });
      return fut.get(forHowLong.toMillis() + 500, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      return List.of();
    } finally {
      ex.shutdownNow();
    }
  }

  public static boolean containsAny(List<String> lines, String... needles) {
    for (String l : lines) for (String n : needles) if (l.contains(n)) return true;
    return false;
  }

  public static long countMatches(List<String> lines, String needle) {
    return lines.stream().filter(l -> l.contains(needle)).count();
  }

  public static void destroy(Process p) {
    if (p == null) return;
    p.destroy();
    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    if (p.isAlive()) p.destroyForcibly();
  }
}