package game;

import java.io.Serializable;

/**
 * Represents a playing card with a rank and suit, used in the poker game.
 */
public class Card implements Serializable {
    public enum Suit { HEARTS, DIAMONDS, CLUBS, SPADES }
    public enum Rank { 
        TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8), 
        NINE(9), TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14);
        
        public final int value;
        Rank(int v) { this.value = v; }
    }

    public final Rank rank;
    public final Suit suit;

    public Card(Rank rank, Suit suit) {
        this.rank = rank;
        this.suit = suit;
    }

    @Override
    public String toString() {
        return rank + " of " + suit;
    }
    
    public String toShortString() {
        String r = (rank.value > 10 || rank.value == 1) ? rank.name().substring(0, 1) : String.valueOf(rank.value);
        return r + suit.name().substring(0, 1);
    }
}