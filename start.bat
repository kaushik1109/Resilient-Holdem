@echo off

javac -d bin src\*.java src\game\*.java src\networking\*.java src/util/*.java src\consensus\*.java
java -cp bin Main %1
pause