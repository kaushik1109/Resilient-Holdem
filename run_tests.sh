#!/bin/bash

set -e

echo "=== Resilient-Holdem Test Runner ==="

JUNIT_JAR="lib/junit-platform-console-standalone.jar"

# -------- sanity checks --------
if [ ! -f "$JUNIT_JAR" ]; then
  echo "❌ JUnit jar not found at $JUNIT_JAR"
  echo "Download it with:"
  echo "curl -L -o lib/junit-platform-console-standalone.jar \\"
  echo "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar"
  exit 1
fi

if [ ! -d "src" ]; then
  echo "❌ src/ folder not found — run this from repo root"
  exit 1
fi

# -------- clean --------
echo "→ Cleaning build folders"
rm -rf bin test-bin
mkdir -p bin test-bin

# -------- compile source --------
echo "→ Compiling source code"
javac -d bin \
  src/util/*.java \
  src/networking/*.java \
  src/consensus/*.java \
  src/game/*.java \
  src/Main.java
  
# -------- compile tests --------
echo "→ Compiling tests"
javac -cp "$JUNIT_JAR:bin" \
  -d test-bin \
  tests/java/*.java

# -------- verify doubles initialized (debug aid) --------
echo "→ Compiled test classes:"
find test-bin -maxdepth 1 -type f -name "*.class" | wc -l | xargs echo "   classes:"

# -------- run tests --------
echo "→ Running JUnit tests (Jupiter only)"
java -jar "$JUNIT_JAR" \
  -cp "bin:test-bin" \
  --scan-classpath \
  --exclude-engine junit-vintage

echo "=== Done ==="