package game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Player implements Serializable {
    public int id;          // TCP Port (Node ID)
    public String name;
    public int chips;
    public int currentBet;  // How much bet in the CURRENT round
    public boolean folded = false;
    public boolean allIn = false;
    public List<Card> holeCards = new ArrayList<>();
    public boolean isActive = true; // NEW: False if they join mid-game

    public Player(int id, int startChips) {
        this.id = id;
        this.name = "Player " + id;
        this.chips = startChips;
        this.currentBet = 0;
        this.isActive = true; // Default to active
    }

    public void resetForNewHand() {
        folded = false;
        allIn = false;
        currentBet = 0;
        holeCards.clear();
        isActive = true; // Everyone plays in the new round
    }
}