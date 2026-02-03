package game;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Player implements Serializable {
    // TCP Port (Node ID)
    public  String id;

    public String name;
    public int chips;
    public int currentBet;  
    public boolean folded = false;
    public boolean allIn = false;
    public List<Card> holeCards = new ArrayList<>();
    public boolean isActive = true;

    public Player(String id, int startChips) {
        this.id = id;
        this.name = "Player " + id;
        this.chips = startChips;
        this.currentBet = 0;
        this.isActive = true;
    }

    public void resetForNewHand() {
        folded = false;
        allIn = false;
        currentBet = 0;
        holeCards.clear();
        isActive = true;
    }
}