package game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a player in the poker game, including their ID (IP:port), name, chip count, current bet, and status.
 */
public class Player implements Serializable {
    public String id;

    public String name;
    public int chips;
    public int currentBet;  
    public int totalBet;  
    public boolean folded = false;
    public boolean allIn = false;
    public List<Card> holeCards = new ArrayList<>();
    public boolean isActive = true;

    public Player(String id, String name, int startChips) {
        this.id = id;
        this.name = name;
        this.chips = startChips;
        this.currentBet = 0;
        this.isActive = true;
    }

    public Player(String id, int startChips) {
        this(id, "Player " + id, startChips);
    }

    public void resetForNewHand() {
        folded = false;
        allIn = false;
        currentBet = 0;
        holeCards.clear();
        isActive = true;
    }
}