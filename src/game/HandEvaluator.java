package game;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates the strength of a player's hand in Texas Hold'em poker based on their hole cards and the community cards.
 * Provides a scoring mechanism to rank hands and determine winners.
 */
public class HandEvaluator {
    public enum HandRank {
        HIGH_CARD(1), PAIR(2), TWO_PAIR(3), TRIPS(4), 
        STRAIGHT(5), FLUSH(6), FULL_HOUSE(7), QUADS(8), STRAIGHT_FLUSH(9);
        
        public final int value;
        HandRank(int v) { this.value = v; }
    }

    /**
     * Evaluates the best hand rank from the given hole cards and community cards.
     * @param hole The player's hole cards.
     * @param community The community cards on the table.
     * @return An integer score representing the hand rank and kicker.
     */
    public static int evaluate(List<Card> hole, List<Card> community) {
        List<Card> all = new ArrayList<>();
        all.addAll(hole);
        all.addAll(community);
        
        // Sort by rank descending (Ace, King, Queen, ...)
        all.sort((a, b) -> b.rank.value - a.rank.value);

        // Check Flush
        boolean isFlush = all.stream()
            .collect(Collectors.groupingBy(c -> c.suit, Collectors.counting()))
            .values().stream().anyMatch(count -> count >= 5);

        // Check Pairs/Trips
        Map<Card.Rank, Long> counts = all.stream().collect(Collectors.groupingBy(c -> c.rank, Collectors.counting()));
        
        boolean hasQuad = counts.values().contains(4L);
        boolean hasTrip = counts.values().contains(3L);
        long pairCount = counts.values().stream().filter(c -> c == 2L).count();

        // Basic Scoring Logic
        if (isFlush) return HandRank.FLUSH.value * 1000000 + all.get(0).rank.value;
        if (hasQuad) return HandRank.QUADS.value * 1000000 + getHighRank(counts, 4);
        if (hasTrip && pairCount >= 1) return HandRank.FULL_HOUSE.value * 1000000;
        if (isFlush) return HandRank.FLUSH.value * 1000000;
        if (hasTrip) return HandRank.TRIPS.value * 1000000 + getHighRank(counts, 3);
        if (pairCount >= 2) return HandRank.TWO_PAIR.value * 1000000 + getHighRank(counts, 2);
        if (pairCount == 1) return HandRank.PAIR.value * 1000000 + getHighRank(counts, 2);

        return HandRank.HIGH_CARD.value * 1000000 + all.get(0).rank.value;
    }

    /**
     * Helper method to get the highest rank for a given count (e.g., highest rank of pairs or trips).
     * @param counts The map of card ranks to their counts.
     * @param count The count of cards (e.g., 2 for a pair, 3 for trips).
     * @return The highest rank value among cards with the given count.
     */
    private static int getHighRank(Map<Card.Rank, Long> counts, int count) {
        return counts.entrySet().stream()
            .filter(e -> e.getValue() == count)
            .map(e -> e.getKey().value)
            .max(Integer::compare)
            .orElse(0);
    }

    /**
     * Converts a hand score back into a human-readable description.
     * @param score The integer score of the hand.
     * @return A string description of the hand rank and kicker.
     */
    public static String getHandDescription(int score) {
        int rankValue = score / 1000000;
        int kickerValue = score % 1000000;
        
        String rankName = "Unknown";
        for (HandRank hr : HandRank.values()) {
            if (hr.value == rankValue) {
                rankName = hr.name(); // e.g., "FLUSH"
                break;
            }
        }
        
        // Convert "14" back to "Ace", "13" to "King"
        String kickerName = getRankName(kickerValue);
        
        // "FLUSH (Ace High)" or "PAIR (King High)"
        return rankName + " (" + kickerName + " High)";
    }
    
    private static String getRankName(int val) {
        switch (val) {
            case 14: return "Ace";
            case 13: return "King";
            case 12: return "Queen";
            case 11: return "Jack";
            default: return String.valueOf(val);
        }
    }
}