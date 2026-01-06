import consensus.HoldBackQueue;
import networking.GameMessage;

public class TestQueue {
    public static void main(String[] args) {
        System.out.println("--- TESTING HOLDBACK QUEUE ---");
        
        HoldBackQueue queue = new HoldBackQueue();

        // SCENARIO 1: Message #1 arrives (Expected)
        System.out.println("\n[Test] Arrived: Seq #1");
        queue.addMessage(createMsg(1, "Bet 10"));
        // Expected Output: >>> GAME ENGINE: Processing Bet 10 (Seq: 1)

        // SCENARIO 2: Message #3 arrives (Gap! We are waiting for #2)
        System.out.println("\n[Test] Arrived: Seq #3 (Should be held back)");
        queue.addMessage(createMsg(3, "Fold"));
        // Expected Output: (Silence... Logic holds it internally)

        // SCENARIO 3: Message #4 arrives (Gap continues)
        System.out.println("\n[Test] Arrived: Seq #4 (Should be held back)");
        queue.addMessage(createMsg(4, "Check"));
        // Expected Output: (Silence...)

        // SCENARIO 4: Message #2 FINALLY arrives (The missing piece)
        System.out.println("\n[Test] Arrived: Seq #2 (Should trigger cascade)");
        queue.addMessage(createMsg(2, "Call"));
        // Expected Output: 
        // >>> GAME ENGINE: Processing Call (Seq: 2)
        // >>> GAME ENGINE: Processing Fold (Seq: 3)
        // >>> GAME ENGINE: Processing Check (Seq: 4)

        // SCENARIO 5: Duplicate Message #2 arrives again
        System.out.println("\n[Test] Arrived: Seq #2 (Duplicate)");
        queue.addMessage(createMsg(2, "Call"));
        // Expected Output: (Silence - ignored)
    }

    // Helper to create dummy messages
    private static GameMessage createMsg(long seq, String payload) {
        // TCP Port 0, Address "Test", Type ORDERED
        return new GameMessage(
            GameMessage.Type.ORDERED_MULTICAST, 
            "TestIP", 
            0, 
            payload, 
            seq
        );
    }
}