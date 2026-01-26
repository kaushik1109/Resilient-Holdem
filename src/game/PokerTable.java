package game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import game.TexasHoldem.Phase;

public class PokerTable implements Serializable {
    public List<Player> players = new ArrayList<>();
    public Deck deck;
    public List<Card> communityCards = new ArrayList<>();
    
    public int pot = 0;
    public int currentHighestBet = 0;
    
    public int dealerIndex = 0; 
    public int currentPlayerIndex = 0; 
    
    public TexasHoldem.Phase currentPhase = TexasHoldem.Phase.PREFLOP;
    public int playersActedThisPhase = 0;

    public PokerTable() {
        this.deck = new Deck();
    }

    public void resetDeck() {
        this.deck = new Deck();
        this.deck.shuffle();
        this.communityCards.clear();
        this.pot = 0;
        this.currentHighestBet = 0;
        this.currentPhase = Phase.PREFLOP;
        this.playersActedThisPhase = 0;
    }
}