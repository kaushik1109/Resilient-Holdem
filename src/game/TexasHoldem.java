package game;

import java.util.*;
import networking.GameMessage;
import networking.TcpMeshManager;
import consensus.Sequencer;

public class TexasHoldem {
    private int myTcpPort; // The Leader's ID
    private TcpMeshManager tcpLayer;
    private Sequencer sequencer;
    
    // Game State
    private Deck deck;
    private List<Player> players = new ArrayList<>();
    private List<Card> communityCards = new ArrayList<>();
    private int pot = 0;
    private int currentPlayerIndex = 0;

    public TexasHoldem(int myPort, TcpMeshManager tcp, Sequencer seq) {
        this.myTcpPort = myPort;
        this.tcpLayer = tcp;
        this.sequencer = seq;
        this.deck = new Deck(); // Only initialized here (on Leader)
    }

    /**
     * Called when a peer joins via Discovery.
     * We add them to the table, but WE (The Leader) do not sit down.
     */
    public void addPlayer(int playerId) {
        if (playerId == myTcpPort) return; // Dealer doesn't play!
        
        // Prevent duplicates
        if (players.stream().anyMatch(p -> p.id == playerId)) return;

        Player newPlayer = new Player(playerId, 1000); // Start with 1000 chips
        players.add(newPlayer);
        System.out.println("[Game] Added Player " + playerId + ". Total: " + players.size());
    }

    public void startNewRound() {
        if (players.size() < 2) {
            System.out.println("[Game] Not enough players to start.");
            return;
        }

        System.out.println("[Game] --- STARTING NEW ROUND ---");
        
        // 1. Reset State
        deck = new Deck();
        deck.shuffle();
        communityCards.clear();
        pot = 0;
        for (Player p : players) p.resetForNewHand();

        // 2. Deal Hole Cards (PRIVATE TCP)
        for (Player p : players) {
            Card c1 = deck.deal();
            Card c2 = deck.deal();
            
            p.holeCards.add(c1);
            p.holeCards.add(c2);

            // Send strictly to THIS player via TCP
            String payload = c1.toString() + "," + c2.toString();
            GameMessage msg = new GameMessage(
                GameMessage.Type.YOUR_HAND, "Leader", myTcpPort, payload
            );
            tcpLayer.sendToPeer(p.id, msg);
        }
        
        // 3. Announce Game Start (PUBLIC UDP)
        sequencer.multicastAction(new GameMessage(
            GameMessage.Type.GAME_STATE, "Leader", myTcpPort, "Round Started. Blinds Posted."
        ));
        
        // 4. Start Pre-Flop Betting (Move to first player)
        currentPlayerIndex = 0;
        promptCurrentPlayer();
    }
    
    public void dealFlop() {
        // Burn one
        deck.deal();
        
        // Deal 3
        Card c1 = deck.deal();
        Card c2 = deck.deal();
        Card c3 = deck.deal();
        communityCards.add(c1); communityCards.add(c2); communityCards.add(c3);

        broadcastCommunityCards("FLOP");
    }
    
    // Helper to send public cards
    private void broadcastCommunityCards(String stage) {
        StringBuilder sb = new StringBuilder();
        for (Card c : communityCards) sb.append(c.toString()).append(",");
        
        // Send via UDP Multicast so everyone sees it at once
        sequencer.multicastAction(new GameMessage(
            GameMessage.Type.COMMUNITY_CARDS, "Leader", myTcpPort, sb.toString()
        ));
    }

    private void promptCurrentPlayer() {
        Player current = players.get(currentPlayerIndex);
        sequencer.multicastAction(new GameMessage(
            GameMessage.Type.GAME_STATE, "Leader", myTcpPort, 
            "Turn: Player " + current.id
        ));
    }
}