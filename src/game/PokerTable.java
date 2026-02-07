package game;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import game.TexasHoldem.Phase;

/**
 * Represents the state of a Poker table, including players, deck, community cards, pot, and game phase.
 */
public class PokerTable implements Serializable {
    // Thread safe alternative to arraylist. We're using this because sometimes 
    // we've had race conditions with one thread removing players while another is reading the list
    public List<Player> players = new CopyOnWriteArrayList<>();

    public Deck deck;
    public List<Card> communityCards = new ArrayList<>();
    
    public int pot = 0;
    public int currentHighestBet = 0;
    
    public int dealerIndex = 0; 
    public int currentPlayerIndex = 0; 
    
    public TexasHoldem.Phase currentPhase = TexasHoldem.Phase.PREFLOP;
    public int playersActedThisPhase = 0;

    public int roundNumber = 0;

    public PokerTable() {
        this.deck = new Deck();
    }

    public void resetDeck() {
        for (Player p : this.players) {
            p.chips += p.totalBet;
            p.totalBet = 0;
            p.currentBet = 0;
        }

        this.deck = new Deck();
        this.deck.shuffle();
        this.communityCards.clear();
        this.pot = 0;
        this.currentHighestBet = 0;
        this.currentPhase = Phase.PREFLOP;
        this.playersActedThisPhase = 0;
    }

    public void removePlayer(String playerId) {
        players.removeIf(p -> p.id.equals(playerId));
    }

    /**
     * Serializes the current game state (PokerTable) into a Base64 string for transmission.
     * @return The serialized game state as a Base64 string.
     */
    public static String getSerializedState(PokerTable table) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(table);
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) { return ""; }
    }

    /**
     * Deserializes a Base64 string back into a PokerTable object.
     * @param data The Base64 string containing the serialized game state.
     * @return The deserialized PokerTable object, or a new empty table if deserialization fails.
     */
    public static PokerTable deserializeState(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return (PokerTable) ois.readObject();
        } catch (Exception e) { return new PokerTable(); }
    }
}