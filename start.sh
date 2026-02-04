#!/bin/bash

echo "Starting Node"
mkdir -p bin
javac -d bin src/networking/*.java src/consensus/*.java src/game/*.java src/util/*.java src/Main.java
java -cp bin Main $1