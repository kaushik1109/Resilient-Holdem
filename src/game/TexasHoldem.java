package game;

import networking.GameMessage;
import java.io.*;
import java.util.Base64;

public class TexasHoldem {
    private final NodeContext context;
    private final PokerTable table; 

    private boolean gameInProgress = false;
    
    public enum Phase { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

    public TexasHoldem(NodeContext context) {
        this(context, new PokerTable());
    }

    public TexasHoldem(NodeContext context, PokerTable loadedTable) {
        this.context = context;
        this.table = loadedTable;
        
        boolean removedSelf = table.players.removeIf(p -> p.id.equals(context.nodeId));
        if (removedSelf) {
            System.out.println("[Game] I (Node " + context.nodeId + ") am now Dealer. Leaving the table.");
        }

        System.out.println("[Game] Reconciling player roster");
        for (String peerId : context.tcp.getConnectedPeerIds()) {
            addPlayer(peerId);
        }

        this.gameInProgress = true;
        context.queue.forceSync(context.sequencer.getCurrentSeqId());
    }

    public void addPlayer(String playerId) {
        if (playerId.equals(context.nodeId)) return;
        
        if (table.players.stream().anyMatch(p -> p.id.equals(playerId))) return;

        Player newPlayer = new Player(playerId, 1000);
        
        if (gameInProgress) {
            newPlayer.isActive = false;
            newPlayer.folded = true;
            sendStateDump(playerId);
        } else {
            System.out.println("[Game] Player " + playerId + " added to table.");
        }
        
        long currentSeq = context.sequencer.getCurrentSeqId();
        sendPrivateMessage(GameMessage.Type.SYNC, playerId, String.valueOf(currentSeq));

        table.players.add(newPlayer);
    }

    public void startNewRound() {
        if (table.players.size() < 2) {
            System.out.println("[Game] Cannot start. Need at least 2 players (excluding Dealer). Current: " + table.players.size());
            return;
        }
        
        gameInProgress = true;
        
        table.resetDeck();;
        table.currentPlayerIndex = (table.dealerIndex + 1) % table.players.size();

        for (Player p : table.players) {
            p.resetForNewHand();

            Card c1 = table.deck.deal();
            Card c2 = table.deck.deal();
            p.holeCards.add(c1);
            p.holeCards.add(c2);
            
            context.tcp.sendToPeer(p.id, new GameMessage(GameMessage.Type.YOUR_HAND, context.myPort, context.myIp, c1 + "," + c2));
        }
        
        broadcastState("New Round! Dealer Node is " + context.nodeId + ". Button is Player " + table.players.get(table.dealerIndex).id);
        notifyTurn();
    }
    
    private void sendStateDump(String targetId) {
        if (!table.communityCards.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Card c : table.communityCards) sb.append(c.toString()).append(",");
            sendPrivateMessage(GameMessage.Type.COMMUNITY_CARDS, targetId, sb.toString());
        }

        sendPrivateState(targetId, "Spectating (Pot: " + table.pot + ")");
    }

    private void notifyTurn() {
        Player next = table.players.get(table.currentPlayerIndex);
        broadcastState("Pot: " + table.pot + " | Turn: Player " + next.id + " (To Call: " + (table.currentHighestBet - next.currentBet) + ")");
        sendPrivateState(next.id, "It is your turn!");
    }

    public void handleClientRequest(GameMessage msg) {
        if (!gameInProgress) {
            sendPrivateState(msg.senderId, "Game not started.");
            return;
        }

        Player current = table.players.get(table.currentPlayerIndex);
        
        if (!current.id.equals(msg.senderId)) {
            System.out.println("[Server] Rejecting out-of-turn move from " + msg.senderId);
            sendPrivateState(msg.senderId, "Not your turn! Current turn: " + current.id);
            return;
        }

        context.sequencer.multicastAction(msg);
    }

    public void processAction(String playerId, String command) {
        if (!gameInProgress) return;

        Player current = table.players.get(table.currentPlayerIndex);
        
        if (!current.id.equals(playerId)) {
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
                    broadcastState("Player " + playerId + " folds.");
                    actionValid = true;
                    break;

                case "call":
                    int callAmt = table.currentHighestBet - current.currentBet;
                    if (payChips(current, callAmt)) {
                        broadcastState("Player " + playerId + " calls " + callAmt);
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
                            actionValid = true;
                        } else {
                            sendPrivateState(playerId, "Bet too small. Must exceed " + table.currentHighestBet);
                        }
                    }
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

    private void dealCommunity(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            Card c = table.deck.deal();
            table.communityCards.add(c);
            sb.append(c.toString()).append(",");
        }
        
        context.sequencer.multicastAction(new GameMessage(GameMessage.Type.COMMUNITY_CARDS, context.myPort, context.myIp, sb.toString()));
    }

    private void performShowdown() {
        System.out.println(">>> SHOWDOWN");
        
        Player winner = null;
        int bestScore = -1;
        
        String winHandDescription = ""; 

        StringBuilder summary = new StringBuilder(">>> SHOWDOWN RESULTS\n");

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
            summary.append("\n").append("WINNER: " + winner.name + " with " + winHandDescription + "! Pot: " + table.pot);
            context.sequencer.multicastAction(new GameMessage(GameMessage.Type.SHOWDOWN, context.myPort, context.myIp, summary.toString()));
        }
        
        passLeadership();
    }

    public void handlePlayerCrash(String playerId) {
        Player p = table.players.stream()
            .filter(player -> player.id.equals(playerId))
            .findFirst()
            .orElse(null);

        if (p == null) return; 

        System.err.println("[Game] Handling crash for Player " + playerId);

        p.isActive = false;
        p.folded = true; 
        
        broadcastState("Player " + playerId + " disconnected and is Auto-Folded");

        if (table.players.indexOf(p) == table.currentPlayerIndex) {
            System.err.println("[Game] Crashed player had the turn. Forcing fold");
            processAction(playerId, "fold");
        }
        
        long activeCount = table.players.stream().filter(pl -> pl.isActive && !pl.folded).count();
        if (activeCount < 2) {
            endRoundByFold();
        }
    }

    private void endRoundByFold() {
        Player winner = table.players.stream().filter(p -> !p.folded).findFirst().orElse(null);

        if (winner != null) {
            winner.chips += table.pot;

            context.sequencer.multicastAction(new GameMessage(
                GameMessage.Type.SHOWDOWN, context.myPort, context.myIp, "Round Over. Everyone folded. " + winner.name + " wins " + table.pot
            ));
        }

        passLeadership();
    }

    private void passLeadership() {
        System.out.println("[Game] Hand finished. Rotating Dealer");
        
        String stateData = getSerializedState();
        
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception e) {}
            context.election.passLeadership(stateData); 
            context.destroyServerGame();
        }).start();
        
        gameInProgress = false;
    }

    private String getSerializedState() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(table);
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) { return ""; }
    }

    public static PokerTable deserializeState(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return (PokerTable) ois.readObject();
        } catch (Exception e) { return new PokerTable(); }
    }

    private void broadcastState(String msg) {
        context.sequencer.multicastAction(new GameMessage(
            GameMessage.Type.GAME_STATE, context.myPort, context.myIp, msg
        ));
    }
    
    private void sendPrivateState(String targetId, String msg) {
        sendPrivateMessage(GameMessage.Type.GAME_STATE, targetId, msg);
    }
    
    private void sendPrivateMessage(GameMessage.Type type, String targetId, String msg) {
        context.tcp.sendToPeer(targetId, new GameMessage(type, context.myPort, context.myIp, msg));
    }
}