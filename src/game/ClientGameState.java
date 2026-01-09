package game;

import java.util.*;

public class ClientGameState {
    public List<String> myHand = new ArrayList<>();
    public List<String> communityCards = new ArrayList<>();
    public String status = "Waiting for game...";
    
    public void onReceiveHand(String payload) {
        // Payload: "Ace of Spades,King of Hearts"
        String[] cards = payload.split(",");
        myHand.clear();
        Collections.addAll(myHand, cards);
        System.out.println(">>> MY HAND: " + myHand);
    }
    
    public void onReceiveCommunity(String payload) {
        String[] cards = payload.split(",");
        communityCards.clear();
        Collections.addAll(communityCards, cards);
        System.out.println(">>> BOARD: " + communityCards);
    }
    
    public void onReceiveState(String msg) {
        this.status = msg;
        System.out.println(">>> GAME INFO: " + msg);
    }
    
    public void printBoard() {
        if (myHand.isEmpty()) {
            System.out.println(">>> MY HAND: [Spectating]");
        } else {
            System.out.println(">>> MY HAND: " + myHand);
        }
        System.out.println(">>> BOARD: " + communityCards);
        System.out.println(">>> INFO: " + status);
    }
}