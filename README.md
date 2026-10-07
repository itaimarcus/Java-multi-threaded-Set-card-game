# Set Card Game

A multi-threaded Java (Swing) implementation of the card game **Set**.
Every player is a separate thread, a dealer thread referees the game, and a timer thread runs the countdown.

## 1. What you need to install

- **JDK 25** (Java Development Kit). This is required, because `pom.xml` is set to Java 25.
  Download "Eclipse Temurin 25" from <https://adoptium.net> and install it.
- **Maven 3.9+** (optional, but needed for building from the terminal and for running tests).
  Download the binary zip from <https://maven.apache.org/download.cgi>, unzip it, and add its `bin` folder to your `PATH`.
- **An editor** (optional): VS Code or Cursor with the **Extension Pack for Java**, if you want to use F5.
- A computer with a screen. The game opens a Swing window, so it can't run on a server without a display.

You do **not** need to download any `.jar` files by hand:
- The card images and `config.properties` are already in `src/main/resources`.
- JUnit and Mockito (used only by the tests) are downloaded automatically by Maven the first time you build. This needs internet access once.

Check your setup:
```
java -version
mvn -v
```
Both should print a version. If `mvn` is "not recognized", Maven isn't on your `PATH`. Either add it, or call it by its full path, for example `C:\tools\maven\bin\mvn.cmd`.

**Using an older JDK?** The code itself is plain Java. Open `pom.xml`, and replace `25` with your JDK version in the three `maven.compiler.*` properties and in the compiler plugin's `<release>` value.

## 2. How to run

Run all commands from the project folder (the one that contains `pom.xml`).

### Option A: from the terminal
Build, then run:
```
mvn -q clean compile
java -cp target/classes bguspl.set.Main
```

Or build a jar and run it:
```
mvn -q clean package -DskipTests
java -jar target/Set_Card_Game-1.0-SNAPSHOT.jar
```

Run the unit tests:
```
mvn test
```

### Option B: F5 in VS Code or Cursor
1. Install the **Extension Pack for Java** and open this folder.
2. Open `src/main/java/bguspl/set/Main.java`.
3. Press **F5** (or click **Run** above `main`). The editor builds the project for you.

If a dialog says **"Build failed, do you want to continue?"**, open the Problems panel (Ctrl+Shift+M) to see why. Choosing "Proceed" still starts the game. To stop the prompt, add this line to `.vscode/settings.json`:
```
"java.debug.settings.onBuildFailureProceed": true
```

**Important:** after you change `config.properties`, rebuild (`mvn -q compile`, or press F5 again). The game reads the copy in `target/classes`.

## 3. How to play

- The table shows 12 cards (3 rows x 4 columns). Each card has 4 features (shape, color, number, shading), and each feature has 3 values.
- A **set** is 3 cards where, for every feature, the values are either **all the same** or **all different**.
- Press your keys to put (or remove) a token on a card. When you have 3 tokens, the dealer checks them.
  - Valid set: you get a point, the cards are replaced, and the timer resets.
  - Invalid set: you get a penalty and are frozen for a few seconds.
- If time runs out, the dealer reshuffles all the cards and starts a new round.
- The game ends when there are no sets left. The winner is announced and the window closes.
- Click the game window first so it has keyboard focus. Closing the window (X) shuts the game down cleanly.

Default keys (the grid layout matches the table):

- Player 1: `Q W E R` / `A S D F` / `Z X C V`
- Player 2: `U I O P` / `J K L ;` / `M , . /`

`Table.hints()` can print the sets that exist on the table to the console (useful for testing). It is not called by the dealer in the current code, so the `Hints` setting has no effect yet.

## 4. How it works (short)

Threads:
- **Main**: builds everything, starts the dealer, then waits for it to finish.
- **Dealer**: shuffles, deals, checks set claims one at a time, replaces cards, and shuts everything down.
- **Timer**: counts down and updates the clock on screen.
- **One thread per player**: takes that player's key presses and acts on them.
- **One extra thread per computer player**: presses random keys.
- **Swing UI thread**: draws the window and receives the real key presses.

Flow:
1. `Main` creates the config, window, table, dealer and players, then starts the dealer.
2. The dealer starts the player threads (and the timer), one after another.
3. Each round, the dealer shuffles and places 12 cards, then waits.
4. A key press goes into the player's small queue (max 3). The player thread takes it out and places or removes a token.
5. With 3 tokens, the player hands its claim to the dealer and sleeps.
6. The dealer checks claims in the order they arrived, and wakes the player with the result.
7. When the timer reaches 0, the dealer collects all the cards and starts the next round.
8. When no sets are left, the dealer announces the winners and stops all the threads in order.

Safety rules:
- Only the dealer decides if a set is valid, so two players can't score on the same cards.
- Everything that changes the table is done inside a lock on the table.
- Locks are always taken in the same order, so threads can't deadlock.

Main files:
- `src/main/java/bguspl/set/ex/Dealer.java`, `Player.java`, `Table.java`, `Timer.java`: the game logic.
- `src/main/java/bguspl/set/`: the rest (window, input handling, config, main), mostly provided as a skeleton.
- `src/test/java/bguspl/set/ex/`: unit tests.
- `docs/set-game-interview-stories.md`: notes about the concurrency design.

## 5. Configuration (`src/main/resources/config.properties`)

| Setting | Meaning |
|---|---|
| `HumanPlayers` | Number of keyboard players (they come first). |
| `ComputerPlayers` | Number of AI players. |
| `PlayerNames` | Names shown on screen, separated by commas. |
| `TurnTimeoutSeconds` | Round length in seconds. `0` shows time since the last action, and a negative value shows no timer (a round ends only when no set is left on the table). |
| `TurnTimeoutWarningSeconds` | When this many seconds remain, the clock switches to warning mode. |
| `PointFreezeSeconds` | How long a player is frozen after scoring. |
| `PenaltyFreezeSeconds` | How long a player is frozen after a wrong set. |
| `TableDelaySeconds` | Delay when placing or removing a card (for animation). |
| `EndGamePauseSeconds` | Pause at the end before the window closes. |
| `Hints` | Meant to print the available sets to the console. Not used by the current code. |
| `Rows`, `Columns` | Table size. Each player needs `Rows x Columns` keys. |
| `PlayerKeys1`, `PlayerKeys2` | Key codes for each player's grid. More than 2 human players need `PlayerKeys3`, etc. |
| `LogLevel` | `ALL` writes a full log. `OFF` writes nothing useful. |

Good values for a normal game: `TurnTimeoutSeconds=60`, `PointFreezeSeconds=1`, `PenaltyFreezeSeconds=3`, `TableDelaySeconds=0.1`.
Very small values (like freezes of 0) make computer players play extremely fast and produce huge logs.

You can also override the settings without rebuilding: put a `config.properties` file in the folder you run the game from. It is read first.

## 6. Logs

- Each run creates a new file in `./logs/`, named after the start time (for example `10-7_20-30-19.log`). Old logs are **not** overwritten.
- The folder is created in the folder you run from, so running from the project root puts it next to `pom.xml`.
- With `LogLevel=ALL` the logs can get very big (tens of MB) when the freeze delays are 0. Set `LogLevel=OFF`, or delete the `logs` folder at any time. It's recreated on the next run.
- The log shows when each thread starts and terminates, which is handy to check that the game shuts down cleanly.

## 7. Troubleshooting

- **`mvn` is not recognized:** add Maven's `bin` folder to `PATH`, or call it by its full path.
- **"release version 25 not supported":** your JDK is older than 25. Install JDK 25, or lower the version in `pom.xml` (see section 1).
- **No Run button above `main`:** the Java extension isn't active. Run from the terminal instead.
- **A config change has no effect:** rebuild (`mvn -q compile`) and run again.
- **Keys do nothing:** click the game window first, and make sure `HumanPlayers` is greater than 0.
