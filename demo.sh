#!/bin/bash

mkdir -p bin
javac -d bin src/networking/*.java src/consensus/*.java src/game/*.java src/util/*.java src/Main.java

if [ $? -eq 0 ]; then
    echo "Compilation Successful. Launching Nodes"
else
    echo "Compilation Failed!"
    exit 1
fi

open_terminal() {
    osascript -e "tell application \"Terminal\" to do script \"cd $(pwd) && java -cp bin Main\""
}

open_terminal
sleep 1
open_terminal
sleep 1
open_terminal
sleep 1
open_terminal

echo "Done! 4 Nodes running."