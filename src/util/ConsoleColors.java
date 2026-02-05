package util; 

/**
 * Utility class defining ANSI escape codes for console text coloring and formatting.
 * These constants can be used to enhance the readability of console output by applying colors and styles.
 */
public class ConsoleColors {
    public static final String RESET = "\033[0m"; 
    public static final String BOLD = "\033[1m";

    public static final String RED = "\033[0;31m";
    public static final String GREEN = "\033[0;32m";
    public static final String YELLOW = "\033[0;33m";
    public static final String CYAN = "\033[0;36m";
    public static final String PURPLE = "\033[0;35m";

    public static final String CYAN_BOLD = "\033[1;36m";
}