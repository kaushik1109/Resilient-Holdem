#!/bin/bash

# 1. Compile the project first
echo "--- Compiling Project ---"
# Create bin directory if it doesn't exist
mkdir -p bin
# Compile all java files
javac -d bin src/networking/*.java src/consensus/*.java src/game/*.java src/Main.java

# Check if compilation succeeded
if [ $? -eq 0 ]; then
    echo "Compilation Successful. Launching Nodes..."
else
    echo "Compilation Failed!"
    exit 1
fi

# 2. Function to open a new Terminal window on macOS
open_terminal() {
    osascript -e "tell application \"Terminal\" to do script \"cd $(pwd) && java -cp bin Main\""
}

# 3. Launch 3 Nodes
open_terminal
sleep 1
open_terminal
sleep 1
open_terminal

echo "Done! 3 Nodes running."