package util;

public class ConsolePrint {
    public static void printError(String message) {
        System.err.println(ConsoleColors.RED + message + ConsoleColors.RESET);
    }

    public static void printBold(String message) {
        System.out.println(ConsoleColors.BOLD + message + ConsoleColors.RESET);
    }

    public static void printNetworking(String message) {
        System.out.println(ConsoleColors.PURPLE + message + ConsoleColors.RESET);
    }

    public static void printElection(String message) {
        System.out.println(ConsoleColors.CYAN + message + ConsoleColors.RESET);
    }

    public static void printElectionBold(String message) {
        System.out.println(ConsoleColors.CYAN_BOLD + message + ConsoleColors.RESET);
    }

    public static void printNormal(String message) {
        System.out.println(message + ConsoleColors.RESET);
    }

    public static void printGame(String message) {
        System.out.println(ConsoleColors.GREEN + message + ConsoleColors.RESET);
    }
}
