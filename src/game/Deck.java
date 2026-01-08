package game;

import java.io.Serializable;
import java.util.*;

public class Deck implements Serializable {
    private LinkedList<Card> cards = new LinkedList<>();

    public Deck() {
        for (Card.Suit suit : Card.Suit.values()) {
            for (Card.Rank rank : Card.Rank.values()) {
                cards.add(new Card(rank, suit));
            }
        }
    }

    public void shuffle() {
        Collections.shuffle(cards);
    }

    public Card deal() {
        return cards.poll();
    }
    
    public int size() {
        return cards.size();
    }
}