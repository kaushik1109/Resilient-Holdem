package game;

import java.util.*;
import networking.GameMessage;

public class TexasHoldem {
    
    private final NodeContext context;
    
    private Deck deck;
    private List<Player> players = new ArrayList<>();
    private List<Card> communityCards = new ArrayList<>();
    
    private int pot = 0;
    private int currentHighestBet = 0;
    private int currentPlayerIndex = 0;
    private boolean gameInProgress = false;
    
    public enum Phase { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }
    private Phase currentPhase = Phase.PREFLOP;
    private int playersActedThisPhase = 0;

    public TexasHoldem(NodeContext context) {
        this.context = context;
        this.deck = new Deck();
        
        // Sync Local Queue
        this.context.queue.forceSync(context.sequencer.getCurrentSeqId());
        System.out.println("[Game] Local Queue Synced to Leader Sequencer.");
    }

    public void addPlayer(int playerId) {
        if (playerId == context.myPort) return;
        if (players.stream().anyMatch(p -> p.id == playerId)) return;

        Player newPlayer = new Player(playerId, 1000);
        
        // Whether game is running or not, the player needs to know 
        // that the Sequence ID has reset to 0.
        sendSyncPacket(playerId); 

        if (gameInProgress) {
            newPlayer.isActive = false; 
            newPlayer.folded = true;   
            System.out.println("[Game] Spectator " + playerId + " joined mid-game.");
            sendStateDump(playerId); // Only send Board/Pot if game is running
        } else {
            System.out.println("[Game] Player " + playerId + " joined (Waiting for start).");
        }
        
        players.add(newPlayer);
    }
    // Helper to just send the Sequence ID reset
    private void sendSyncPacket(int targetId) {
        long currentSeq = context.sequencer.getCurrentSeqId();
        context.tcp.sendToPeer(targetId, new GameMessage(
            GameMessage.Type.SYNC, "Leader", context.myPort, String.valueOf(currentSeq)
        ));
    }

    // Renamed old 'sendWelcomePackage' to 'sendStateDump' for clarity
    private void sendStateDump(int targetId) {
        // Send Community Cards
        if (!communityCards.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Card c : communityCards) sb.append(c.toString()).append(",");
            context.tcp.sendToPeer(targetId, new GameMessage(
                GameMessage.Type.COMMUNITY_CARDS, "Leader", context.myPort, sb.toString()
            ));
        }
        // Send Pot/Status
        String stateMsg = "Spectating... (Pot: " + pot + ")";
        context.tcp.sendToPeer(targetId, new GameMessage(
            GameMessage.Type.GAME_STATE, "Leader", context.myPort, stateMsg
        ));
    }

    public void startNewRound() {
        if (players.size() < 2) return;
        
        gameInProgress = true;
        deck = new Deck();
        deck.shuffle();
        communityCards.clear();
        pot = 0;
        currentHighestBet = 0;
        currentPlayerIndex = 0;
        currentPhase = Phase.PREFLOP;
        playersActedThisPhase = 0;
        
        // Reset players
        for (Player p : players) p.resetForNewHand();

        // Deal Cards (Private TCP)
        for (Player p : players) {
            Card c1 = deck.deal();
            Card c2 = deck.deal();
            p.holeCards.add(c1);
            p.holeCards.add(c2);
            context.tcp.sendToPeer(p.id, new GameMessage(
                GameMessage.Type.YOUR_HAND, "Leader", context.myPort, c1.toString() + "," + c2.toString()
            ));
        }
        broadcastState("New Round! Pre-Flop Betting.");
        notifyTurn();
    }

    public void handleClientRequest(GameMessage msg) {
        // 1. Basic Validation
        if (!gameInProgress) {
            sendPrivateError(msg.tcpPort, "Game not started.");
            return;
        }

        Player current = players.get(currentPlayerIndex);
        
        // 2. Strict Turn Order Enforcement
        if (current.id != msg.tcpPort) {
            System.out.println("[Server] Rejecting out-of-turn move from " + msg.tcpPort);
            sendPrivateError(msg.tcpPort, "Not your turn! Current turn: " + current.id);
            return;
        }

        // 3. Logic Validation (Can they actually bet this?)
        // (Simplified check: just passing it through for now if turn is correct)
        // In a full implementation, you'd check p.chips >= amount here too.

        // 4. IF VALID: Multicast it to the world
        // Only NOW does it become an "Official" game move
        context.sequencer.multicastAction(msg);
    }

    /**
     * The Brain: Processes "bet 20", "fold", "call"
     */
    public void processAction(int playerId, String command) {
        if (!gameInProgress) return;

        Player current = players.get(currentPlayerIndex);
        
        // 1. Enforce Turn Order
        if (current.id != playerId) {
            System.out.println("[Server] Ignored action from " + playerId + " (Not their turn)");
            return;
        }

        String[] parts = command.split(" ");
        String type = parts[0].toLowerCase();
        boolean actionValid = false;

        try {
            switch (type) {
                case "fold":
                    current.folded = true;
                    broadcastState("Player " + playerId + " Folds.");
                    actionValid = true;
                    break;

                case "call":
                    int callAmt = currentHighestBet - current.currentBet;
                    if (payChips(current, callAmt)) {
                        broadcastState("Player " + playerId + " Calls " + callAmt);
                        actionValid = true;
                    }
                    break;

                case "check":
                    if (current.currentBet == currentHighestBet) {
                        broadcastState("Player " + playerId + " Checks.");
                        actionValid = true;
                    } else {
                        // Attempted check when they need to call
                        sendPrivateError(playerId, "Cannot Check. You must Call " + (currentHighestBet - current.currentBet));
                    }
                    break;

                case "bet":
                case "raise":
                    if (parts.length < 2) break;
                    int amount = Integer.parseInt(parts[1]);
                    // Logic: Bet must be at least the call amount + raise
                    int totalToPutIn = amount; 
                    
                    // Simple logic: 'bet 50' means "make my total bet 50"
                    // Or "add 50 to pot"? 
                    // Let's use: "Add this amount to my current stake"
                    
                    if (payChips(current, amount)) {
                        int totalBet = current.currentBet; // Already updated by payChips
                        if (totalBet > currentHighestBet) {
                            currentHighestBet = totalBet;
                            broadcastState("Player " + playerId + " Bets/Raises " + amount);
                            actionValid = true;
                        } else {
                            sendPrivateError(playerId, "Bet too small. Must exceed " + currentHighestBet);
                        }
                    }
                    break;
                    
                case "allin":
                     int allInAmt = current.chips;
                     payChips(current, allInAmt);
                     if (current.currentBet > currentHighestBet) currentHighestBet = current.currentBet;
                     current.allIn = true;
                     broadcastState("Player " + playerId + " Goes ALL IN!");
                     actionValid = true;
                     break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (actionValid) {
            moveToNextPlayer();
        }
    }

    private boolean payChips(Player p, int amount) {
        if (p.chips < amount) {
            sendPrivateError(p.id, "Not enough chips!");
            return false;
        }
        p.chips -= amount;
        p.currentBet += amount;
        pot += amount;
        return true;
    }
    private void moveToNextPlayer() {
        // 1. Check if everyone folded
        long activeCount = players.stream().filter(p -> !p.folded).count();
        if (activeCount < 2) {
            endRoundByFold();
            return;
        }

        // 2. Increment "Acted" counter
        playersActedThisPhase++;

        // 3. CHECK FOR PHASE END
        // Condition: Everyone active has acted AND everyone matches the highest bet
        boolean allMatched = players.stream()
            .filter(p -> !p.folded && !p.allIn)
            .allMatch(p -> p.currentBet == currentHighestBet);

        if (allMatched && playersActedThisPhase >= activeCount) {
            advancePhase(); // <--- GO TO FLOP/TURN/RIVER
            return;
        }

        // 4. If Phase continues, move to next player
        int loopSafety = 0;
        do {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
            loopSafety++;
        } while (players.get(currentPlayerIndex).folded && loopSafety < players.size());

        notifyTurn();
    }

    private void advancePhase() {
        playersActedThisPhase = 0;
        currentHighestBet = 0;
        // Reset "currentBet" for the new round so betting starts from 0 again
        for (Player p : players) p.currentBet = 0;
        
        // Move Dealer button (Simple: First active player acts first post-flop)
        currentPlayerIndex = 0;
        while (players.get(currentPlayerIndex).folded) {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
        }

        switch (currentPhase) {
            case PREFLOP:
                currentPhase = Phase.FLOP;
                dealCommunity(3);
                broadcastState("The FLOP is dealt!");
                break;
            case FLOP:
                currentPhase = Phase.TURN;
                dealCommunity(1);
                broadcastState("The TURN is dealt!");
                break;
            case TURN:
                currentPhase = Phase.RIVER;
                dealCommunity(1);
                broadcastState("The RIVER is dealt!");
                break;
            case RIVER:
                currentPhase = Phase.SHOWDOWN;
                performShowdown(); // <--- DETERMINE WINNER
                return;
            default:
                break;
        }
        
        notifyTurn();
    }

    private void dealCommunity(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Card c = deck.deal();
            communityCards.add(c);
            sb.append(c.toString()).append(",");
        }
        // Broadcast the specific cards so UI can update
        context.sequencer.multicastAction(new GameMessage(
            GameMessage.Type.COMMUNITY_CARDS, "Leader", context.myPort, sb.toString()
        ));
    }

    private void performShowdown() {
        System.out.println("[Game] --- SHOWDOWN ---");
        
        Player winner = null;
        int bestScore = -1;
        
        // FIX: Change 'winReason' from "Score 6000" to "Flush (Ace High)"
        String winHandDescription = ""; 

        StringBuilder summary = new StringBuilder("--- SHOWDOWN RESULTS ---\n");

        for (Player p : players) {
            if (p.folded) continue;
            
            int score = HandEvaluator.evaluate(p.holeCards, communityCards);
            String handDesc = HandEvaluator.getHandDescription(score); // <--- USE NEW METHOD
            
            summary.append(p.name)
                   .append(" shows: ").append(p.holeCards)
                   .append(" -> ").append(handDesc).append("\n");
            
            if (score > bestScore) {
                bestScore = score;
                winner = p;
                winHandDescription = handDesc;
            }
        }
        
        if (winner != null) {
            winner.chips += pot;
            
            String winMsg = "WINNER: " + winner.name + " with " + winHandDescription + "! Pot: " + pot;
            broadcastState(winMsg);
            
            // Append winner info to the summary log
            summary.append("\n").append(winMsg);

            context.sequencer.multicastAction(new GameMessage(
                GameMessage.Type.SHOWDOWN, "Leader", context.myPort, summary.toString()
            ));
        }
        
        System.out.println("[Game] Hand finished. Rotating Dealer...");
        
        // Wait 3 seconds so everyone can see the result before the server dies
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception e) {}
            context.election.passLeadership(); 
        }).start();
        
        gameInProgress = false;
    }

    private void endRoundByFold() {
        // Find the one person left
        Player winner = players.stream().filter(p -> !p.folded).findFirst().orElse(null);
        if (winner != null) {
            winner.chips += pot;
            broadcastState("Round Over. Everyone folded. " + winner.name + " wins " + pot);
        }
        gameInProgress = false;
    }

    private void notifyTurn() {
        Player next = players.get(currentPlayerIndex);
        broadcastState("Pot: " + pot + " | Turn: Player " + next.id + " (To Call: " + (currentHighestBet - next.currentBet) + ")");
    }

    private void broadcastState(String msg) {
        context.sequencer.multicastAction(new GameMessage(
            GameMessage.Type.GAME_STATE, "Leader", context.myPort, msg
        ));
    }
    
    private void sendPrivateError(int targetId, String msg) {
        // Send a temporary "Game State" just to that person
        context.tcp.sendToPeer(targetId, new GameMessage(
             GameMessage.Type.GAME_STATE, "Leader", context.myPort, "ERROR: " + msg
        ));
    }
}