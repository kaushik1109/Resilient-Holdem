package game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PokerTable implements Serializable {
    public List<Player> players = new ArrayList<>();
    public Deck deck;
    public List<Card> communityCards = new ArrayList<>();
    
    public int pot = 0;
    public int currentHighestBet = 0;
    
    public int dealerIndex = 0;      // Who has the Dealer Button
    public int currentPlayerIndex = 0; // Whose actual turn it is
    
    public TexasHoldem.Phase currentPhase = TexasHoldem.Phase.PREFLOP;
    public int playersActedThisPhase = 0;

    public PokerTable() {
        this.deck = new Deck();
    }
}