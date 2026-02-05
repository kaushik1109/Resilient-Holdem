# Resilient-Holdem 

## Architecture Overview
This is a distributed Texas Hold'em poker game implementing total order broadcast for game state consistency. Key components:

- **NodeContext**: Central hub coordinating all subsystems (networking, consensus, game state)
- **Networking**: TCP mesh for peer-to-peer communication, UDP multicast for sequenced broadcasts
- **Consensus**: Leader election (ElectionManager), total ordering (Sequencer + HoldBackQueue)
- **Game Logic**: TexasHoldem server game, ClientGameState for local views

Messages are routed through `NodeContext.routeMessage()` based on `GameMessage.Type`.

---

## Development Workflow

- **Build & Run (Recommended)**  
  Use the provided startup script, which compiles the project and launches nodes with the correct configuration:

  ```bash
  chmod +x start.sh
  ./start.sh
---

## Key Patterns
- **Message Routing**: All inter-node communication via GameMessage enum types, handled in `NodeContext.routeMessage()`
- **Consensus Flow**: Client actions → ACTION_REQUEST → Sequencer assigns seq ID → UDP multicast → HoldBackQueue delivers in order
- **Leader Election**: Bully algorithm variant; leader creates/manages TexasHoldem server instance
- **State Management**: Server game state on leader, replicated client views via sequenced messages

---

## File Organization
- `src/Main.java`: Entry point, creates NodeContext, starts election, handles user input
- `src/game/NodeContext.java`: Core integration point, routes messages to appropriate handlers
- `src/consensus/`: ElectionManager, Sequencer, HoldBackQueue implement distributed algorithms
- `src/networking/`: TcpMeshManager (reliable peer connections), UdpMulticastManager (discovery + ordering)
- `src/util/`: ConsolePrint helpers for consistent logging output
- `network.config`: UDP multicast settings

---

## Conventions
- Pure Java, no external dependencies
- AtomicLong for sequence IDs, ConcurrentHashMap for thread-safe collections
- Heartbeat-based failure detection (2s intervals, 6s timeout)
- Random ports (5000–6000) for TCP servers

---

## Common Tasks
- Adding game actions: Extend `GameMessage.Type`, add routing in `NodeContext.routeMessage()`, implement in TexasHoldem
- Network changes: Update TcpMeshManager for connection logic, UdpMulticastManager for broadcast patterns
- Consensus modifications: Modify Sequencer for ordering, HoldBackQueue for delivery guarantees

---

## Testing (JUnit 5)

The project includes a JUnit 5 test suite focused on the distributed-system core components without modifying the production code.

### What is tested

- **Sequencer**
  - Sequence ID increments correctly
  - ACTION_REQUEST is converted to PLAYER_ACTION
  - Multicast messages are emitted
  - NACK repair path resends from history buffer

- **ElectionManager**
  - Election challenges only higher-ID peers
  - Coordinator messages update leader state
  - Election restarts on leader failure
  - Lower-node election requests trigger OK + new election

- **HoldBackQueue**
  - Out-of-order messages are delivered in total order
  - Duplicate sequence numbers are ignored
  - Missing sequence gaps trigger NACK
  - Sync jumps expected sequence correctly

- **NodeContext Routing**
  - NACK routed to Sequencer
  - SYNC routed to HoldBackQueue
  - Ordered messages routed to queue
  - LEAVE routed to disconnect handling
  - Coordinator updates leader + queue leaderId

Tests use lightweight test doubles for networking layers and reflection-based injection to avoid changing the codebase or opening real sockets.

---

## Running Tests (macOS / Linux)

Make the script executable:

```bash
chmod +x run_tests.sh