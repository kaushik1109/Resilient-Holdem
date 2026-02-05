package game;

import networking.GameMessage;

import static util.ConsolePrint.printError;
import static util.ConsolePrint.printGame;
import static util.ConsolePrint.printNormal;

import java.util.List;

/**
 * Implements the Texas Hold'em poker game logic, managing player actions, game phases, and state transitions.
 */
public class TexasHoldem {
    private final NodeContext node;
    public final PokerTable table; 

    private boolean gameInProgress = false;
    
    public enum Phase { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

    public TexasHoldem(NodeContext node) {
        this(node, new PokerTable());
    }

    /**
     * Initializes the Texas Hold'em game with a given node context and loaded table state.
     * Removes self from player list if present (since the new leader shouldn't be a player), 
     * reconciles player roster with connected peers, and forces a state sync to ensure consistency.
     * @param node The NodeContext of this node.
     * @param loadedTable The PokerTable state to load.
     */
    public TexasHoldem(NodeContext node, PokerTable loadedTable) {
        this.node = node;
        this.table = loadedTable;
        node.sequencer.resetSeqId();

        printGame("[Game] Reconciling player roster");
        for (String peerId : node.tcp.getConnectedPeerIds()) {
            addPlayer(peerId);
        }
        
        for (Player player : table.players) {
            if (player.id.equals(node.myId)) {
                player.isActive = false;
                player.folded = true;
            } else {
                player.isActive = true;
                player.folded = false;
            }
        }

        printGame("[Game] I (Node " + node.myId + ") am now Dealer. Leaving the table.");

        this.gameInProgress = true;
        node.queue.forceSync(node.sequencer.getCurrentSeqId());
        printNormal("Game State Loaded. Type 'start' to begin next hand");
    }

    /**
     * Adds a new player to the poker table.
     * If the game is in progress, the new player is marked as a spectator.
     * Sends a state dump to the new player to synchronize their view of the game.
     * @param playerId The ID of the player to add.
     */
    public void addPlayer(String playerId) {
        if (playerId.equals(node.myId)) return;
        
        sendPrivateMessage(GameMessage.Type.SYNC, playerId, String.valueOf(node.sequencer.getCurrentSeqId()));
        
        if (table.players.stream().anyMatch(p -> p.id.equals(playerId))) return;

        Player newPlayer = new Player(playerId, 1000);
        
        if (gameInProgress) {
            newPlayer.isActive = false;
            newPlayer.folded = true;
            sendStateDump(playerId);
        } else {
            printGame("[Game] Player " + playerId + " added to table.");
        }

        table.players.add(newPlayer);
    }

    /**
     * Starts a new round of Texas Hold'em if there are enough players.
     * Resets the deck, deals hole cards to each player, and notifies them of their hand.
     * Broadcasts the new round state to all players and prompts the first player to act.
     */
    public void startNewRound() {
        List<Player> activePlayers = table.players.stream().filter(p -> p.isActive).toList();
        
        if (activePlayers.size() < 2) {
            printGame("[Game] Cannot start. Need at least 2 players (excluding Dealer). Current: " + table.players.size());
            return;
        }
        
        gameInProgress = true;
        
        table.resetDeck();
        table.currentPlayerIndex = (table.dealerIndex + 1) % table.players.size();

        while (!table.players.get(table.currentPlayerIndex).isActive) {
            printGame("[Game] Skipping inactive player " + table.players.get(table.currentPlayerIndex).id);
            table.currentPlayerIndex = (table.currentPlayerIndex + 1) % table.players.size();
        }

        for (Player p : table.players) {
            if (!p.isActive) continue;
            p.resetForNewHand();

            Card c1 = table.deck.deal();
            Card c2 = table.deck.deal();
            p.holeCards.add(c1);
            p.holeCards.add(c2);
            
            node.tcp.sendToPeer(p.id, new GameMessage(GameMessage.Type.YOUR_HAND, c1 + "," + c2));
        }
        
        broadcastState("New Round! Dealer Node is " + node.myId + ".");
        notifyTurn();
    }

    /**
     * Sends a dump of the current game state to a specific player.
     * @param targetId The ID of the target player.
     */
    private void sendStateDump(String targetId) {
        if (!table.communityCards.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Card c : table.communityCards) sb.append(c.toString()).append(",");
            sendPrivateMessage(GameMessage.Type.COMMUNITY_CARDS, targetId, sb.toString());
        }

        sendPrivateState(targetId, "Spectating (Pot: " + table.pot + ")");
    }

    /**
     * Notifies the current player that it is their turn to act with a small delay 
     * to ensure the message is received after any state updates.
     */
    private void notifyTurn() {
        Player next = table.players.get(table.currentPlayerIndex);
        broadcastState("Pot: " + table.pot + " | Turn: Player " + next.id + " (To Call: " + (table.currentHighestBet - next.currentBet) + ")");
        try { Thread.sleep(500); } catch (Exception e) {}
        sendPrivateState(next.id, "It is your turn!");
    }

    /**
     * handles an incoming client request (player action) and processes it if valid.
     * Validates if it's the player's turn and multicasts the action via the sequencer.
     * @param msg The GameMessage containing the player's action request.
     */
    public void handleClientRequest(GameMessage msg) {
        if (!gameInProgress) {
            sendPrivateState(msg.getSenderId(), "Game not started.");
            return;
        }

        Player current = table.players.get(table.currentPlayerIndex);
        
        if (!current.id.equals(msg.getSenderId())) {
            printError("[Server] Rejecting out-of-turn move from " + msg.getSenderId());
            sendPrivateState(msg.getSenderId(), "Not your turn! Current turn: " + current.id);
            return;
        }

        node.sequencer.multicastAction(msg);
    }

    /**
     * Processes a player's action command, updating the game state accordingly.
     * Validates actions such as fold, call, check, bet/raise, and all-in.
     * Advances the game state and notifies players as necessary.
     * @param playerId The ID of the player performing the action.
     * @param command The action command string.
     */
    public void processAction(String playerId, String command) {
        if (!gameInProgress) return;

        Player current = table.players.get(table.currentPlayerIndex);

        String[] parts = command.split(" ");
        String type = parts[0].toLowerCase();
        boolean actionValid = false;

        try {
            switch (type) {
                case "fold":
                    current.folded = true;
                    broadcastState("Player " + playerId + " folds.");
                    actionValid = true;
                    break;

                case "call":
                    int callAmt = table.currentHighestBet - current.currentBet;
                    if (payChips(current, callAmt)) {
                        broadcastState("Player " + playerId + " calls " + callAmt);
                        sendUpdatedTableState();
                        actionValid = true;
                    }
                    break;

                case "check":
                    if (current.currentBet == table.currentHighestBet) {
                        broadcastState("Player " + playerId + " Checks.");
                        actionValid = true;
                    } else {
                        sendPrivateState(playerId, "Cannot check. You must call " + (table.currentHighestBet - current.currentBet));
                    }
                    break;

                case "bet":
                case "raise":
                    if (parts.length < 2) break;
                    int amount = Integer.parseInt(parts[1]);
                    
                    if (payChips(current, amount)) {
                        int totalBet = current.currentBet; 
                        if (totalBet > table.currentHighestBet) {
                            table.currentHighestBet = totalBet;
                            broadcastState("Player " + playerId + " bets/raises " + amount);
                            sendUpdatedTableState();
                            actionValid = true;
                        } else {
                            sendPrivateState(playerId, "Bet too small. Must exceed " + table.currentHighestBet);
                        }
                    }
                    break;

                case "pay":
                case "add":
                    if (parts.length < 2) break;
                    int chips = Integer.parseInt(parts[1]);
                    Player player = table.players.get(table.currentPlayerIndex);
                    player.chips += chips;
                    broadcastState("Player " + playerId + " buys in for " + chips + " chips. Current chips: " + player.chips);
                    sendUpdatedTableState();
                    break;
                    
                case "allin":
                     payChips(current, current.chips);
                     if (current.currentBet > table.currentHighestBet) table.currentHighestBet = current.currentBet;
                     current.allIn = true;
                     broadcastState("Player " + playerId + " goes all in!");
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

    /**
     * Processes the payment of chips from a player for betting or calling.
     * Validates if the player has enough chips and updates their chip count and current bet.
     * @param p The player making the payment.
     * @param amount The amount of chips to pay.
     * @return True if the bet was successful, false otherwise.
     */
    private boolean payChips(Player p, int amount) {
        if (p.chips < amount) {
            sendPrivateState(p.id, "Not enough chips! You have " + p.chips + " but tried to bet " + amount + ". Use 'allin' if you want to bet everything.");
            return false;
        }

        p.chips -= amount;
        p.currentBet += amount;
        table.pot += amount;
        return true;
    }

    /**
     * Advances the turn to the next active player or phase in the game.
     * Checks for round-ending conditions such as all but one player folding.
     * Notifies players of the new turn or phase as necessary.
     */
    private void moveToNextPlayer() {
        long activeCount = table.players.stream().filter(p -> !p.folded).count();
        if (activeCount < 2) {
            endRoundByFold();
            return;
        }

        table.playersActedThisPhase++;

        boolean allMatched = table.players.stream()
            .filter(p -> !p.folded && !p.allIn)
            .allMatch(p -> p.currentBet == table.currentHighestBet);

        // Logic check: If everyone else is All-In/Folded except one guy, ensuring he doesn't bet against himself?
        // For simplicity: If all active (non-all-in) players matched, we advance.
        if (allMatched && table.playersActedThisPhase >= activeCount) {
            advancePhase();
            return;
        }

        int loopSafety = 0;
        do {
            table.currentPlayerIndex = (table.currentPlayerIndex + 1) % table.players.size();
            loopSafety++;
        } while (
            (table.players.get(table.currentPlayerIndex).folded || 
             table.players.get(table.currentPlayerIndex).allIn)
            && loopSafety < table.players.size()
        );

        notifyTurn();
    }

    /**
     * Advances the game to the next phase (Flop, Turn, River, Showdown).
     * Deals community cards as necessary and notifies players of the new phase.
     * If betting is skipped due to all players being all-in, it automatically advances to the next phase.
     */
    private void advancePhase() {
        table.playersActedThisPhase = 0;
        table.currentHighestBet = 0;
        for (Player p : table.players) p.currentBet = 0;
        
        table.currentPlayerIndex = 0;
        while (table.players.get(table.currentPlayerIndex).folded) {
            table.currentPlayerIndex = (table.currentPlayerIndex + 1) % table.players.size();
        }

        long canBetCount = table.players.stream()
            .filter(p -> !p.folded && !p.allIn)
            .count();
            
        boolean skipBetting = (canBetCount < 2);

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
                performShowdown();
                return;
            default: break;
        }
        
        if (skipBetting) {
            broadcastState("All players all-in (or only one active). Running it out");
            
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (Exception e) {}
                advancePhase(); 
            }).start();
        } else {
            notifyTurn();
        }
    }

    /**
     * Deals a specified number of community cards and broadcasts them to all players.
     * @param count The number of community cards to deal.
     */
    private void dealCommunity(int count) {
        StringBuilder communityCards = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Card c = table.deck.deal();
            table.communityCards.add(c);
            communityCards.append(c.toString()).append(",");
        }
        
        node.sequencer.multicastAction(new GameMessage(GameMessage.Type.COMMUNITY_CARDS, communityCards.toString()));
    }

    /**
     * Performs the showdown phase, evaluating all active players' hands and determining the winner.
     * Broadcasts the results to all players and awards the pot to the winner.
     * Rotates the dealer and passes leadership to the next node.
     */
    private void performShowdown() {
        Player winner = null;
        int bestScore = -1;
        
        String winHandDescription = ""; 

        StringBuilder summary = new StringBuilder("Showdown Results\n");

        for (Player p : table.players) {
            if (p.folded) continue;
            
            int score = HandEvaluator.evaluate(p.holeCards, table.communityCards);
            String handDesc = HandEvaluator.getHandDescription(score);
            
            summary.append(p.name)
                   .append(" shows: ").append(p.holeCards)
                   .append(" -> ").append(handDesc).append("\n");
            
            if (score > bestScore) {
                winHandDescription = handDesc;
                bestScore = score;
                winner = p;
            }
        }
        
        if (winner != null) {
            winner.chips += table.pot;
            summary.append("\n").append("Winner: " + winner.name + " with " + winHandDescription + "! Pot: " + table.pot);
            node.sequencer.multicastAction(new GameMessage(GameMessage.Type.SHOWDOWN, summary.toString()));
        }
        
        passLeadership();
    }

    /**
     * Handles a player crash by marking them as inactive and folded.
     * If it was their turn, forces a fold action.
     * Checks for round-ending conditions and ends the round if necessary.
     * @param playerId The ID of the crashed player.
     */
    public void handlePlayerCrash(String playerId) {
        Player p = table.players.stream()
            .filter(player -> player.id.equals(playerId))
            .findFirst()
            .orElse(null);

        if (p == null) return; 

        printError("[Game] Handling crash for Player " + playerId);

        p.isActive = false;
        p.folded = true; 
        
        broadcastState("Player " + playerId + " disconnected and is Auto-Folded");

        if (table.players.indexOf(p) == table.currentPlayerIndex) {
            printError("[Game] Crashed player had the turn. Forcing fold");
            processAction(playerId, "fold");
        }
        
        long activeCount = table.players.stream().filter(pl -> pl.isActive && !pl.folded).count();
        if (activeCount < 2) {
            endRoundByFold();
        }
    }

    /**
     * Ends the round when all but one player has folded.
     * Awards the pot to the remaining player and passes leadership to the next node.
     */
    private void endRoundByFold() {
        Player winner = table.players.stream().filter(p -> !p.folded).findFirst().orElse(null);

        if (winner != null) {
            winner.chips += table.pot;

            node.sequencer.multicastAction(new GameMessage(
                GameMessage.Type.SHOWDOWN, "Round Over. Everyone folded. " + winner.name + " wins " + table.pot
            ));
        }

        passLeadership();
    }

    /**
     * Passes leadership to the next node by serializing the current game state
     * and sending it to the next leader. Destroys the server game instance afterwards.
     */
    private void passLeadership() {
        printGame("[Game] Hand finished.");
        
        sendUpdatedTableState();
        
        printGame("[Game] Rotating dealer.");
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (Exception e) {}
            node.election.passLeadership(PokerTable.getSerializedState(table)); 
            node.destroyServerGame();
        }).start();
        
        gameInProgress = false;
    }

    /**
     * Sends the updated table state to all players.
     */
    private void sendUpdatedTableState() {
        printGame("[Game] Sending updated table state to all players.");
        String stateData = PokerTable.getSerializedState(table);
        node.sequencer.multicastAction(new GameMessage(GameMessage.Type.GAME_STATE, stateData));
    }

    private void broadcastState(String msg) {
        node.sequencer.multicastAction(new GameMessage(GameMessage.Type.GAME_INFO, msg));
    }
    
    private void sendPrivateState(String targetId, String msg) {
        sendPrivateMessage(GameMessage.Type.GAME_INFO, targetId, msg);
    }
    
    private void sendPrivateMessage(GameMessage.Type type, String targetId, String msg) {
        node.tcp.sendToPeer(targetId, new GameMessage(type, msg));
    }
}