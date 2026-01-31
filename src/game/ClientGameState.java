package game;

import java.util.*;

public class ClientGameState {
    public List<String> myHand = new ArrayList<>();
    public List<String> communityCards = new ArrayList<>();
    public String status = "Waiting for game...";
    
    public void onReceiveHand(String payload) {
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
    
    public void printStatus(int myPort) {
        System.out.println("\n>>> CURRENT STATUS");
        System.out.println(">>> MY PORT: " + myPort);
        if (myHand.isEmpty()) {
             System.out.println(">>> HAND: [Spectating / Folded]");
        } else {
             System.out.println(">>> HAND: " + myHand);
        }
        
        System.out.println(">>> BOARD: " + communityCards);
        System.out.println(">>> INFO:  " + status);
    }
}