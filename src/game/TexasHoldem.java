package game;

import networking.GameMessage;
import java.io.*;
import java.util.Base64;

public class TexasHoldem {
    private final NodeContext context;
    private final PokerTable table; 

    private boolean gameInProgress = false;
    
    // Move Phase enum to class level or separate file so PokerTable can see it
    public enum Phase { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

    public TexasHoldem(NodeContext context) {
        this(context, new PokerTable());
    }

public TexasHoldem(NodeContext context, PokerTable loadedTable) {
        this.context = context;
        this.table = loadedTable;
        
        // --- FIX 1: Roster Reconciliation ---
        // 1. I am now the Dealer. I cannot play. Remove me from the table.
        boolean removedSelf = table.players.removeIf(p -> p.id == context.myPort);
        if (removedSelf) {
            System.out.println("[Game] I (Node " + context.myPort + ") am now Dealer. Standing up from the table.");
        }

        // 2. Scan for missing peers (e.g., The Old Leader who just stepped down)
        // They are connected via TCP, but might not be in the 'players' list yet.
        System.out.println("[Game] reconciling player roster...");
        for (int peerId : context.tcp.getConnectedPeerIds()) {
            addPlayer(peerId); // This method handles duplicates safely
        }

        // 3. Sync State
        this.gameInProgress = (table.currentPhase != Phase.PREFLOP || table.pot > 0);
        context.queue.forceSync(context.sequencer.getCurrentSeqId());

        // 4. Send SYNC to all active players
        long currentSeq = context.sequencer.getCurrentSeqId();
        for (Player p : table.players) {
             context.tcp.sendToPeer(p.id, new GameMessage(
                 GameMessage.Type.SYNC, "Leader", context.myPort, String.valueOf(currentSeq)
             ));
        }
    }

    public void addPlayer(int playerId) {
        // 1. Leader Logic: The Dealer does NOT sit at the table.
        if (playerId == context.myPort) return;
        
        // 2. Duplicate Check
        if (table.players.stream().anyMatch(p -> p.id == playerId)) return;

        // 3. New Player Logic
        Player newPlayer = new Player(playerId, 1000);
        
        // If they join mid-game (and weren't already in the table state), they spectate
        if (gameInProgress) {
            newPlayer.isActive = false;
            newPlayer.folded = true;
            sendStateDump(playerId);
        } else {
            // New players joining during the break are active for the next hand
             System.out.println("[Game] Player " + playerId + " added to table.");
        }
        
        // Send Sync so they are on the right Sequence ID
        long currentSeq = context.sequencer.getCurrentSeqId();
        context.tcp.sendToPeer(playerId, new GameMessage(
            GameMessage.Type.SYNC, "Leader", context.myPort, String.valueOf(currentSeq)
        ));

        table.players.add(newPlayer);
    }

    public void startNewRound() {
        // We need at least 2 players + 1 Dealer (Me) = 3 Nodes Total involved? 
        // Or can 2 players play while 1 deals? Yes. 
        if (table.players.size() < 2) {
            System.out.println("[Game] Cannot start. Need at least 2 players (excluding Dealer). Current: " + table.players.size());
            return;
        }
        
        gameInProgress = true;
        
        // Reset State
        table.deck = new Deck();
        table.deck.shuffle();
        table.communityCards.clear();
        table.pot = 0;
        table.currentHighestBet = 0;
        table.currentPhase = Phase.PREFLOP;
        table.playersActedThisPhase = 0;
        
        // 1. Rotate Button (Relative to the list of players)
        table.dealerIndex = (table.dealerIndex + 1) % table.players.size();
        
        // 2. Set Turn (Player after Button)
        table.currentPlayerIndex = (table.dealerIndex + 1) % table.players.size();

        // Reset Players
        for (Player p : table.players) p.resetForNewHand();

        // Deal Cards (Only to Players in the list)
        for (Player p : table.players) {
            Card c1 = table.deck.deal();
            Card c2 = table.deck.deal();
            p.holeCards.add(c1);
            p.holeCards.add(c2);
            
            context.tcp.sendToPeer(p.id, new GameMessage(
                GameMessage.Type.YOUR_HAND, "Leader", context.myPort, c1 + "," + c2
            ));
        }
        
        broadcastState("New Round! Dealer Node is " + context.myPort + ". Button is Player " + table.players.get(table.dealerIndex).id);
        notifyTurn();
    }
    
    // Helper to send state to late joiners
    private void sendStateDump(int targetId) {
        if (!table.communityCards.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Card c : table.communityCards) sb.append(c.toString()).append(",");
            context.tcp.sendToPeer(targetId, new GameMessage(
                GameMessage.Type.COMMUNITY_CARDS, "Leader", context.myPort, sb.toString()
            ));
        }
        String stateMsg = "Spectating... (Pot: " + table.pot + ")";
        context.tcp.sendToPeer(targetId, new GameMessage(
             GameMessage.Type.GAME_STATE, "Leader", context.myPort, stateMsg
        ));
    }

    // Example of using the persistent index
    private void notifyTurn() {
        Player next = table.players.get(table.currentPlayerIndex); // <--- Uses Table State
        broadcastState("Pot: " + table.pot + " | Turn: Player " + next.id);
    }

    // --- Helper: Serialize Table to String (for Migration) ---
    public String getSerializedState() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(table);
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) { return ""; }
    }

    // --- Helper: Deserialize String to Table ---
    public static PokerTable deserializeState(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return (PokerTable) ois.readObject();
        } catch (Exception e) { return new PokerTable(); }
    }

    public void handleClientRequest(GameMessage msg) {
        // 1. Basic Validation
        if (!gameInProgress) {
            sendPrivateError(msg.tcpPort, "Game not started.");
            return;
        }

        Player current = table.players.get(table.currentPlayerIndex);
        
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

        Player current = table.players.get(table.currentPlayerIndex);
        
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
                    int callAmt = table.currentHighestBet - current.currentBet;
                    if (payChips(current, callAmt)) {
                        broadcastState("Player " + playerId + " Calls " + callAmt);
                        actionValid = true;
                    }
                    break;

                case "check":
                    if (current.currentBet == table.currentHighestBet) {
                        broadcastState("Player " + playerId + " Checks.");
                        actionValid = true;
                    } else {
                        // Attempted check when they need to call
                        sendPrivateError(playerId, "Cannot Check. You must Call " + (table.currentHighestBet - current.currentBet));
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
                        if (totalBet > table.currentHighestBet) {
                            table.currentHighestBet = totalBet;
                            broadcastState("Player " + playerId + " Bets/Raises " + amount);
                            actionValid = true;
                        } else {
                            sendPrivateError(playerId, "Bet too small. Must exceed " + table.currentHighestBet);
                        }
                    }
                    break;
                    
                case "allin":
                     int allInAmt = current.chips;
                     payChips(current, allInAmt);
                     if (current.currentBet > table.currentHighestBet) table.currentHighestBet = current.currentBet;
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
        table.pot += amount;
        return true;
    }

    private void moveToNextPlayer() {
        // 1. Check if everyone folded
        long activeCount = table.players.stream().filter(p -> !p.folded).count();
        if (activeCount < 2) {
            endRoundByFold();
            return;
        }

        // 2. Increment "Acted" counter
        table.playersActedThisPhase++;

        // 3. CHECK FOR PHASE END
        // Condition: Everyone active has acted AND everyone matches the highest bet
        boolean allMatched = table.players.stream()
            .filter(p -> !p.folded && !p.allIn)
            .allMatch(p -> p.currentBet == table.currentHighestBet);

        if (allMatched && table.playersActedThisPhase >= activeCount) {
            advancePhase(); // <--- GO TO FLOP/TURN/RIVER
            return;
        }

        // 4. If Phase continues, move to next player
        int loopSafety = 0;
        do {
            table.currentPlayerIndex = (table.currentPlayerIndex + 1) % table.players.size();
            loopSafety++;
        } while (table.players.get(table.currentPlayerIndex).folded && loopSafety < table.players.size());

        notifyTurn();
    }

    private void advancePhase() {
        table.playersActedThisPhase = 0;
        table.currentHighestBet = 0;
        // Reset "currentBet" for the new round so betting starts from 0 again
        for (Player p : table.players) p.currentBet = 0;
        
        // Move Dealer button (Simple: First active player acts first post-flop)
        table.currentPlayerIndex = 0;
        while (table.players.get(table.currentPlayerIndex).folded) {
            table.currentPlayerIndex = (table.currentPlayerIndex + 1) % table.players.size();
        }

        switch (table.currentPhase) {
            case PREFLOP:
                table.currentPhase = Phase.FLOP;
                dealCommunity(3);
                broadcastState("The FLOP is dealt!");
                break;
            case FLOP:
                table.currentPhase = Phase.TURN;
                dealCommunity(1);
                broadcastState("The TURN is dealt!");
                break;
            case TURN:
                table.currentPhase = Phase.RIVER;
                dealCommunity(1);
                broadcastState("The RIVER is dealt!");
                break;
            case RIVER:
                table.currentPhase = Phase.SHOWDOWN;
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
            Card c = table.deck.deal();
            table.communityCards.add(c);
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
        
        String winHandDescription = ""; 

        StringBuilder summary = new StringBuilder("--- SHOWDOWN RESULTS ---\n");

        for (Player p : table.players) {
            if (p.folded) continue;
            
            int score = HandEvaluator.evaluate(p.holeCards, table.communityCards);
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
            winner.chips += table.pot;
            
            String winMsg = "WINNER: " + winner.name + " with " + winHandDescription + "! Pot: " + table.pot;
            broadcastState(winMsg);
            
            // Append winner info to the summary log
            summary.append("\n").append(winMsg);

            context.sequencer.multicastAction(new GameMessage(
                GameMessage.Type.SHOWDOWN, "Leader", context.myPort, summary.toString()
            ));
        }
        
        System.out.println("[Game] Hand finished. Rotating Dealer...");
        
        String stateData = getSerializedState();
        
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception e) {}
            context.election.passLeadership(stateData); 
            context.destroyServerGame();
        }).start();
        
        gameInProgress = false;
    }

    private void endRoundByFold() {
        // Find the one person left
        Player winner = table.players.stream().filter(p -> !p.folded).findFirst().orElse(null);
        if (winner != null) {
            winner.chips += table.pot;
            broadcastState("Round Over. Everyone folded. " + winner.name + " wins " + table.pot);
        }
        gameInProgress = false;
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