# Set Card Game: Concurrency Interview Stories

**Elevator pitch:** "I built a multi-threaded card game. Each player is a thread, one dealer thread is the single arbiter for set claims, the UI thread never blocks on game logic, locks are acquired in a consistent order, and shutdown is deterministic."

Each story gives a hook, the facts to remember, the key terms, and a one-line takeaway. Where the code and an earlier analysis disagree, the code wins.

---

# Part 1: Story bank (to remember)

## 1. The input pipeline that never blocks the UI
- **Hook:** key presses arrive on the Event Dispatch Thread, but handling them can take seconds.
- **Facts:**
  - `InputManager.keyPressed` calls `Player.keyPressed(slot)`.
  - The player stores up to 3 actions in a `ConcurrentLinkedQueue`, then calls `notify()` under `synchronized(playerThread)`.
  - The player thread waits in a `while` loop on its own monitor and runs the action under `synchronized(table)`.
- **Key terms:** producer-consumer, CAS, non-blocking, guarded wait.
- **Takeaway:** slow work goes on the player thread, and the key handler only enqueues.

## 2. One dealer as the single arbiter of set claims
- **Hook:** players race, but only one thread validates claims.
- **Facts:** the player calls `submitCards(this)` under `synchronized(dealer)` and waits in a loop (`while 3 tokens && legalset==2 && !terminate`). The Dealer drains `submitedPlayers` FIFO in `checklegal()`, then sets `legalset` and calls `notifyAll()`.
- **Takeaway:** serialize the decision, not the whole game.

## 3. Overlapping claims can't both score
- **Facts:** on a valid set the Dealer removes the cards and strips every player's tokens before the next claim. A stale claimant has fewer than 3 tokens, so it gets no verdict (`notifyAll()` at `Dealer.java` line 273).
- **Takeaway:** a lock protects memory, but the game rule is enforced by the single-threaded arbiter.

## 4. The table lock and lock ordering
- **Facts:**
  - One coarse `synchronized(table)` guards `slotToCard`, `cardToSlot` and the tokens.
  - The only nested order is dealer monitor, then table monitor (`checklegal()` calling `removeCardsFromTable()`).
  - Players take table, release it, then take dealer.
  - Per-slot locks were rejected because multi-slot operations would need ordered locks, and the grid is only about 12 slots.
- **Takeaway:** a consistent lock order is how you argue "no deadlock".

## 5. Guarded waits and lost wakeups
- **Facts:**
  - Always `while (!condition) wait()`.
  - Check the condition under the monitor, and enqueue before notifying.
  - Spurious wakeups are possible, and `notifyAll` means others may take the work first.
- **Takeaway:** `wait()` releases the monitor atomically, so a notify can't slip in between your check and your sleep.

## 6. The startup handshake and its latent bug
- **Facts:** the Dealer calls `start()` then `synchronized(this){ wait(); }`, and each player calls `synchronized(dealer){ dealer.notify(); }`. If the notify comes first, the Dealer waits forever. It works because thread startup takes far longer than the Dealer reaching `wait()`.
- **Fix:** `CountDownLatch`, or a boolean flag checked in a `while` loop.
- **Takeaway:** "works in practice" isn't "correct by design", and I know the difference.

## 7. Shutdown as a tree
- **Facts:** the Dealer sets `timer.terminate`, interrupts and joins the Timer. It then loops over players in reverse, calling `terminate()` (a volatile flag plus `interrupt()`) and `join()`. Each player interrupts and joins its own AI thread.
- **Why both flag and interrupt:** the interrupt wakes a blocked thread, and the flag records the intent because the exception clears the interrupted status.
- **Takeaway:** a thread that starts a thread is responsible for stopping and joining it.

## 8. Asynchronous timer reset
- **Facts:** `timer.reset = true` is read by the Timer thread on its next tick, so the Dealer never blocks. The cost is that the reset takes effect within up to 1 second (100 ms in the warning phase). I wouldn't claim sub-millisecond latency.
- **Takeaway:** trade latency for decoupling.

## 9. Visibility thinking
- **Facts:**
  - Three sources of visibility: `volatile`, a common lock, and `start()`/`join()`.
  - `volatile` fields: `terminate`, `dealing`, `isFrozen`, and the Timer's `time`, `reset` and `terminate`.
  - `legalset` and `submitedPlayers` are only touched under the Dealer monitor.
- **Takeaway:** `volatile` gives visibility and ordering, not atomicity. `time -= change` is the example.

## 10. The AI threads
- **Facts:**
  - Each AI player has its own helper thread (`createArtificialIntelligence`), which picks a random slot and calls `keyPressed`.
  - The AI waits when its queue is full or the player is frozen.
  - It busy-spins while `dealing` is true, because `keyPressed` returns immediately.
  - With one producer per queue, `size() < 3` then `add()` is safe in practice.
- **Takeaway:** the safety of a check-then-act depends on a design assumption, so state the assumption.

## 11. Threads vs thread pool
- **Facts:** the game has a few long-lived threads with identity and state (dealer, timer, players, AI helpers), not many short tasks. A pool thread parked in `wait()` just holds a slot.
- **Takeaway:** a pool wins with many short tasks, and dedicated threads win for long-lived actors.

## 12. Lock hygiene
- **Facts:**
  - `synchronized(playerThread)` and `synchronized(aiThread)` use `Thread` objects as locks, but `Thread.join()` also uses the Thread object's monitor (the Javadoc advises against it).
  - `synchronized(dealer)` serves four jobs: claims, verdicts, the Dealer's sleep, and the startup handshake.
  - The better design is a private `Object` lock per purpose, or a `Condition` per player.
- **Takeaway:** one lock per distinct condition.

## 13. The `dealing` flag and time-of-check to time-of-use
- **Facts:** `keyPressed` filters early, and `performAction` re-checks under the table lock. `placeCardsOnTable()` isn't under the table lock and relies on `dealing`.
- **Takeaway:** the authoritative check must happen where the data is protected.

## 14. Skeleton boundaries
- **Facts:** I understand the interface with the course skeleton: the Event Dispatch Thread calls `InputManager.keyPressed`, the window close button calls `Main.xButtonPressed()`, which calls `dealer.terminate()` and joins the main thread. That blocks the Event Dispatch Thread until shutdown completes.
- **Takeaway:** know which thread calls your code.

## 15. Freezing a player cooperatively
- **Facts:** the Dealer sets `isFrozen = true`, which tells `keyPressed` and the AI to ignore input. The player thread actually stops by calling `dealer.wait()` and then `sleep()`. A thread can't be forced to wait by another.
- **Why one-second slices:** `point()` and `penalty()` sleep in `Math.min(SECOND, freezeTime)` steps so the UI countdown updates and `terminate` is checked.
- **Takeaway:** cooperative cancellation beats forced suspension.

## 16. Configurable behavior changes the concurrency
- **Facts:** `TurnTimeoutSeconds` greater than 0 gives a countdown. A value of 0 shows elapsed time. A negative value starts no Timer thread, and the Dealer waits with no timeout. My current config has 4 AI players, 0 humans, 5 s timeout, and 0 s freezes.
- **Takeaway:** different config paths exercise different synchronization code.

## 17. Testing
- **Facts:** I have 5 unit tests with Mockito (`point`, `penalty`, `keyPressed`, `noSetsOnTable`, `shouldFinish`), and none are concurrent. Evidence I can still produce:
  - Compare "Thread X starting." and "Thread X terminated." counts in `./logs`.
  - Write a stress test with many threads calling `keyPressed`.
  - Measure latency with `System.nanoTime()`.
- **Takeaway:** be honest about what's verified.

## 18. Retrospective review of my own code
- **Facts:** the weak-spot list: startup lost wakeup, overloaded Dealer monitor, unlocked refill, `Timer.time` compound writes (`Timer.java` line 46, plus writes at lines 27, 52 and 63), `wait(timer.time)` reading a volatile twice (`wait(0)` waits forever), magic numbers in `legalset`, `terminate` meaning two things, `playerThread.sleep(...)` written on an instance, and unread `score` visibility in `announceWinners`.
- **Takeaway:** this is my story for "what would you change?".

---

# Part 2: STAR stories for technical interviews

## Story A: Keeping the UI responsive with a producer-consumer pipeline

- **Situation:** In my Java Set game, keyboard presses arrive on the Swing Event Dispatch Thread. Handling a press can take seconds: it needs the table lock, then it can wait for the dealer's verdict, then the player is frozen.
- **Task:** Make the key handler return immediately, cap pending actions at 3 per player, and avoid lost wakeups.
- **Action:**
  - I used a `ConcurrentLinkedQueue` per player.
  - The producer checks `size() < 3`, adds, then notifies under the player's own monitor.
  - The consumer waits in a guarded `while` loop and processes one action at a time under `synchronized(table)`.
  - Because the notify needs the same monitor the consumer holds between its check and its sleep, the wakeup can't be lost.
- **Result:** The Event Dispatch Thread only enqueues. It never waits on game logic (apart from a very short monitor hit). The cap of 3 is enforced.
- **What I'd improve:** Switch to `ArrayBlockingQueue(3)` with `offer()` and `take()`, which removes my hand-written wait/notify and makes the cap atomic. I'd also use a private lock object instead of a `Thread` object.
- **Likely follow-ups:**
  - Isn't `size()` then `add()` a race? It's safe only because each queue has exactly one producer.
  - What is CAS? A CPU instruction that sets a value only if it still equals the expected one. I used a library queue built on it.
  - Did you measure the improvement? No. I'd describe the benefit as by design, not as a number.

## Story B: Preventing double scoring with a single arbiter and a lock order

- **Situation:** Several players race to claim 3-card sets, while the dealer removes and reshuffles cards. Two players could claim overlapping cards, or place tokens on a slot being cleared.
- **Task:** Guarantee a card can only score once, keep the table invariant `slotToCard[x] == y iff cardToSlot[y] == x`, and avoid deadlocks.
- **Action:**
  - A player submits its claim under `synchronized(dealer)` and sleeps in `dealer.wait()` inside a guarded loop.
  - The Dealer thread checks claims one at a time in FIFO order.
  - On a valid set, it removes the cards, strips tokens from every player, and only then looks at the next claim. A stale claimant has fewer than 3 tokens and gets no verdict.
  - Table access uses one coarse `synchronized(table)`. I rejected per-slot locks because set removal touches several slots and would need ordered locking.
  - The lock order is dealer monitor then table monitor, and no thread takes them in the reverse order.
- **Result:** Claims are processed in a serialized order, overlapping claims can't both score, and there is no lock-order cycle, by construction.
- **What I'd improve:**
  - `placeCardsOnTable()` isn't under the table lock and relies on the `dealing` flag.
  - The Dealer monitor serves four purposes, which forces `notifyAll` and lets waiting players wake each other.
  - I'd hide the protocol behind `dealer.submitAndAwaitVerdict(player)` with a `Condition` per player.
- **Likely follow-ups:** How do you know there are no deadlocks? (The lock order.) Why not a read-write lock? (Reads are part of read-modify-write sequences.)

## Story C: Deterministic startup and shutdown across many threads

- **Situation:** The game runs the main thread, Dealer, Timer, N players and up to N AI helpers. They must start in order and stop cleanly, whether the game ends naturally or the user closes the window.
- **Task:** Shut everything down with no orphaned threads, including threads blocked in `sleep()` or `wait()`.
- **Action:**
  - I used `volatile` flags plus `interrupt()` and `join()` in a top-down order.
  - The Dealer stops and joins the Timer first. It then calls `terminate()` on each player in reverse, which sets the volatile flag and interrupts, and joins each.
  - Each player stops and joins its own AI thread.
  - I used both the flag and the interrupt because the interrupt wakes a blocked thread, while the flag preserves the intent.
  - For the timer I used `timer.reset = true`, so the Dealer never blocks on the timer. The cost is that a reset takes effect on the next tick.
- **Result:** The shutdown path has a defined owner for every thread. I can verify it by comparing the "starting" and "terminated" lines in the log files.
- **What I'd improve:**
  - The startup handshake can lose a wakeup (`wait()` with no condition). I'd use a `CountDownLatch`.
  - `Timer.time` is written by two threads, with a compound `-=` on a volatile. I'd use an `AtomicLong` or a single writer.
  - `terminate` means both "user quit" and "game ended", and I'd split it.
- **Likely follow-ups:** Why not `ExecutorService.shutdownNow()`? (These are long-lived loop threads with their own state.) What does `volatile` guarantee? (Visibility and ordering, not atomicity.)

## Story D: Reviewing my own concurrent code and finding the real risks

- **Situation:** Revisiting this student project as a working engineer, I reviewed it the way I'd review a teammate's pull request.
- **Task:** Find concurrency problems that tests don't catch, and decide which matter.
- **Action:** I traced every lock, flag and signal and built an inventory (`playerThread`, `aiThread`, `dealer`, `table`, `timer`, and the volatile flags), then checked each for correct use.
- **Result:**
  - **Startup:** lost-wakeup risk, which works only because of timing.
  - **Locks:** an overloaded Dealer monitor, and `Thread` objects used as locks (`join()` uses the same monitor).
  - **Timer:** `Timer.time` is shared by two threads and `wait(timer.time)` can become `wait(0)`.
  - **Table:** the unlocked refill.
  - **AI helper:** it busy-spins while `dealing` is true.
  - **Visibility:** `announceWinners()` reads `score` without a guarantee.
- **What I'd improve:** I'd fix the first three (startup latch, dedicated locks or a `Condition` per player, an atomic timer value), then add tests: a stress test on `keyPressed`, plus a log-based check for orphaned threads.
- **Why this story works:** it shows judgment, humility and the ability to prioritize.

## Story E: Making design trade-offs deliberately

- **Situation:** At each point there were several valid concurrency tools.
- **Task:** Pick the simplest one that was correct and explain why.
- **Action:**
  - **Table lock:** one coarse lock over per-slot locks or a read-write lock, because the grid has about 12 slots and critical sections last microseconds. Per-slot locking would also need ordered multi-lock acquisition for removals.
  - **Dedicated threads:** I chose long-lived threads over a pool, because pools pay off for many short tasks and these threads each own state and loop for the whole game.
  - **Volatile flags:** I used flags for simple published state and locks for compound state.
  - **Single arbiter:** one thread validates claims, so there's no distributed agreement to get wrong.
- **Result:** A small set of mechanisms that I can explain and defend, with each limitation known.
- **What I'd improve:** Prefer library primitives (`BlockingQueue`, `CountDownLatch`, `Condition`) over hand-rolled wait/notify, unless the point is to learn.

---

# Claims to avoid in interviews
- Zero UI frame drops or sub-millisecond latency (never measured).
- 100% termination guarantee or zero deadlocks (say "by construction, with a consistent lock order").
- "Lock-free" for the whole input path (the wakeup still takes a monitor).

# Quick concept answers
- **`wait` vs `sleep`:** `wait` needs and releases the monitor and ends on notify, timeout or interrupt. `sleep` releases nothing and ends by time or interrupt.
- **Why a `while` loop around `wait`:** spurious wakeups, and the condition may change before you re-acquire the lock.
- **What a lost wakeup is:** a notify with no waiter. The monitor makes check-then-sleep atomic.
- **Why `volatile`:** the JIT can hoist a read out of a loop, and CPU store buffers delay visibility. It doesn't make `x++` atomic.

---
---

# גרסה בעברית (Hebrew Version)

<div dir="rtl">

# סיפורי ראיונות על תכנות מקבילי: משחק Set

**תקציר בשורה אחת (Elevator Pitch):** "בניתי משחק קלפים מרובה תהליכונים (Multi-threaded). כל שחקן הוא תהליכון (Thread), תהליכון אחד של מחלק (Dealer) הוא הפוסק היחיד בטענות לסט, תהליכון ממשק המשתמש (Event Dispatch Thread) אף פעם לא נחסם בגלל לוגיקת המשחק, הנעילות (Locks) נלקחות תמיד באותו סדר, והכיבוי דטרמיניסטי."

לכל סיפור יש: וו (Hook), עובדות לזכור, מונחי מפתח ומסקנה בשורה אחת. כשהקוד ותשובה קודמת סותרים זה את זה, הקוד קובע.

---

# חלק 1: מאגר סיפורים (לזיכרון)

## 1. צינור הקלט שלא חוסם את הממשק (UI)
- **הוו (Hook):** לחיצות מקשים מגיעות על ה-Event Dispatch Thread, אבל טיפול בהן יכול לקחת שניות.
- **עובדות:**
  - `InputManager.keyPressed` קורא ל-`Player.keyPressed(slot)`.
  - השחקן שומר עד 3 פעולות בתור `ConcurrentLinkedQueue`, ואז קורא ל-`notify()` בתוך `synchronized(playerThread)`.
  - תהליכון השחקן ממתין בלולאת `while` על המוניטור (Monitor) של עצמו, ומבצע את הפעולה בתוך `synchronized(table)`.
- **מונחי מפתח:** יצרן-צרכן (Producer-Consumer), CAS, ללא חסימה (Non-blocking), המתנה שמורה (Guarded Wait).
- **מסקנה:** העבודה האיטית נעשית בתהליכון השחקן, ומטפל המקשים רק מכניס לתור.

## 2. מחלק אחד כפוסק יחיד של טענות לסט
- **הוו (Hook):** שחקנים מתחרים, אבל רק תהליכון אחד מאמת טענות.
- **עובדות:** השחקן קורא ל-`submitCards(this)` בתוך `synchronized(dealer)` וממתין בלולאה (`while 3 tokens && legalset==2 && !terminate`). המחלק מרוקן את `submitedPlayers` לפי סדר הגעה (FIFO) ב-`checklegal()`, קובע את `legalset` וקורא ל-`notifyAll()`.
- **מסקנה:** מסדרים בתור (Serialize) את ההחלטה, לא את המשחק כולו.

## 3. טענות חופפות לא יכולות שתיהן לקבל נקודה
- **עובדות:** כשהסט תקין, המחלק מסיר את הקלפים ומוריד את האסימונים (Tokens) של כל השחקנים לפני הטענה הבאה. מי שהטענה שלו התיישנה נשאר עם פחות מ-3 אסימונים, ולכן לא מקבל פסק דין (`notifyAll()` בשורה 273 ב-`Dealer.java`).
- **מסקנה:** נעילה מגינה על זיכרון, אבל כלל המשחק נאכף על ידי הפוסק היחיד שרץ בתהליכון אחד.

## 4. נעילת השולחן וסדר הנעילות
- **עובדות:**
  - נעילה גסה אחת (Coarse-grained) `synchronized(table)` מגינה על `slotToCard`, על `cardToSlot` ועל האסימונים.
  - הסדר המקונן היחיד הוא: מוניטור של המחלק, ואז מוניטור של השולחן (`checklegal()` שקורא ל-`removeCardsFromTable()`).
  - שחקנים לוקחים את נעילת השולחן, משחררים אותה, ורק אז לוקחים את נעילת המחלק.
  - דחיתי נעילה לכל משבצת (Per-slot) כי פעולות על כמה משבצות דורשות נעילות מסודרות, והמשטח הוא רק כ-12 משבצות.
- **מסקנה:** סדר נעילות קבוע הוא הדרך לטעון שאין קיפאון (Deadlock).

## 5. המתנות שמורות והתעוררות אבודה (Lost Wakeup)
- **עובדות:**
  - תמיד `while (!condition) wait()`.
  - בודקים את התנאי בתוך המוניטור, ומכניסים לתור לפני שמעירים.
  - התעוררות מדומה (Spurious Wakeup) אפשרית, ועם `notifyAll` תהליכון אחר עלול לקחת את העבודה.
- **מסקנה:** `wait()` משחרר את המוניטור באופן אטומי (Atomic), ולכן `notify` לא יכול להתחבא בין הבדיקה לבין השינה.

## 6. לחיצת היד בהתחלה והבאג הסמוי
- **עובדות:** המחלק קורא ל-`start()` ואז `synchronized(this){ wait(); }`, וכל שחקן קורא ל-`synchronized(dealer){ dealer.notify(); }`. אם ה-notify מגיע קודם, המחלק ממתין לנצח. זה עובד כי הפעלת תהליכון לוקחת הרבה יותר זמן מהגעת המחלק ל-`wait()`.
- **תיקון:** `CountDownLatch`, או דגל בוליאני שנבדק בלולאת `while`.
- **מסקנה:** "עובד בפועל" זה לא "נכון בתכנון", ואני מכיר את ההבדל.

## 7. כיבוי כעץ
- **עובדות:** המחלק קובע `timer.terminate`, קוטע (Interrupt) את הטיימר ומחכה לו (`join`). אחר כך הוא עובר על השחקנים בסדר הפוך וקורא ל-`terminate()` (דגל `volatile` ועוד `interrupt()`) ול-`join()`. כל שחקן קוטע ומחכה לתהליכון ה-AI שלו.
- **למה גם דגל וגם interrupt:** ה-interrupt מעיר תהליכון חסום, והדגל שומר את הכוונה כי החריגה מנקה את סטטוס ה-interrupt.
- **מסקנה:** מי שמפעיל תהליכון אחראי לעצור אותו ולחכות לו.

## 8. איפוס טיימר אסינכרוני
- **עובדות:** `timer.reset = true` נקרא על ידי תהליכון הטיימר בטיק הבא, ולכן המחלק לא נחסם. המחיר: האיפוס נכנס לתוקף תוך עד שנייה אחת (100 מילישניות בשלב האזהרה). לא הייתי טוען ל-sub-millisecond.
- **מסקנה:** מוותרים על זמן תגובה כדי להפריד בין הרכיבים.

## 9. חשיבה על נראות (Visibility)
- **עובדות:**
  - שלושה מקורות לנראות: `volatile`, נעילה משותפת, ו-`start()`/`join()`.
  - שדות `volatile`: `terminate`, `dealing`, `isFrozen`, וב-Timer: `time`, `reset`, `terminate`.
  - ל-`legalset` ול-`submitedPlayers` ניגשים רק בתוך מוניטור המחלק.
- **מסקנה:** `volatile` נותן נראות וסדר, לא אטומיות. `time -= change` היא הדוגמה.

## 10. תהליכוני ה-AI
- **עובדות:**
  - לכל שחקן מחשב יש תהליכון עזר משלו (`createArtificialIntelligence`), שבוחר משבצת אקראית וקורא ל-`keyPressed`.
  - ה-AI ממתין כשהתור מלא או כשהשחקן קפוא.
  - הוא מסתובב בלולאה פעילה (Busy-spin) כש-`dealing` שווה true, כי `keyPressed` חוזר מיד.
  - עם יצרן אחד לכל תור, הבדיקה `size() < 3` ואז `add()` בטוחה בפועל.
- **מסקנה:** הבטיחות של בדיקה-ואז-פעולה (Check-then-act) תלויה בהנחת תכנון, אז צריך לציין את ההנחה.

## 11. תהליכונים ייעודיים מול מאגר תהליכונים (Thread Pool)
- **עובדות:** במשחק יש מספר קטן של תהליכונים ארוכי חיים עם זהות ומצב (מחלק, טיימר, שחקנים, עוזרי AI), ולא הרבה משימות קצרות. תהליכון מאגר שתקוע ב-`wait()` רק תופס מקום.
- **מסקנה:** מאגר מנצח כשיש הרבה משימות קצרות, ותהליכונים ייעודיים מנצחים בשחקנים ארוכי חיים.

## 12. היגיינת נעילות (Lock Hygiene)
- **עובדות:**
  - `synchronized(playerThread)` ו-`synchronized(aiThread)` משתמשים באובייקטי `Thread` כנעילות, אבל גם `Thread.join()` משתמש במוניטור של אובייקט ה-Thread (ה-Javadoc ממליץ נגד זה).
  - `synchronized(dealer)` משרת ארבע מטרות: טענות, פסקי דין, שינה של המחלק ולחיצת היד בהתחלה.
  - התכנון הטוב יותר: אובייקט `Object` פרטי לכל מטרה, או `Condition` לכל שחקן.
- **מסקנה:** נעילה אחת לכל תנאי נפרד.

## 13. הדגל `dealing` ובעיית זמן-בדיקה מול זמן-שימוש (TOCTOU)
- **עובדות:** `keyPressed` מסנן מוקדם, ו-`performAction` בודק שוב בתוך נעילת השולחן. `placeCardsOnTable()` לא נמצאת בתוך נעילת השולחן ומסתמכת על `dealing`.
- **מסקנה:** הבדיקה הקובעת חייבת להתבצע במקום שבו המידע מוגן.

## 14. גבולות השלד (Skeleton)
- **עובדות:** אני מבין את הממשק מול השלד של הקורס: ה-Event Dispatch Thread קורא ל-`InputManager.keyPressed`, וכפתור סגירת החלון קורא ל-`Main.xButtonPressed()`, שקורא ל-`dealer.terminate()` ומחכה לתהליכון הראשי. זה חוסם את ה-Event Dispatch Thread עד שהכיבוי מסתיים.
- **מסקנה:** צריך לדעת איזה תהליכון קורא לקוד שלך.

## 15. הקפאת שחקן בשיתוף פעולה
- **עובדות:** המחלק קובע `isFrozen = true`, מה שאומר ל-`keyPressed` ול-AI להתעלם מקלט. תהליכון השחקן בעצמו עוצר כי הוא קורא ל-`dealer.wait()` ואחר כך ל-`sleep()`. אי אפשר לאלץ תהליכון אחד להמתין מבחוץ.
- **למה פרוסות של שנייה:** `point()` ו-`penalty()` ישנים בצעדים של `Math.min(SECOND, freezeTime)` כדי שספירת הקיפאון בממשק תתעדכן ו-`terminate` ייבדק.
- **מסקנה:** ביטול בשיתוף פעולה עדיף על השעיה בכוח.

## 16. קונפיגורציה משנה את המקביליות
- **עובדות:** `TurnTimeoutSeconds` גדול מ-0 נותן ספירה לאחור. ערך 0 מציג זמן שחלף. ערך שלילי לא מפעיל תהליכון טיימר, והמחלק ממתין בלי timeout. הקונפיגורציה הנוכחית שלי: 4 שחקני AI, 0 בני אדם, timeout של 5 שניות, וקיפאון של 0 שניות.
- **מסקנה:** נתיבי קונפיגורציה שונים מפעילים קוד סנכרון שונה.

## 17. בדיקות (Testing)
- **עובדות:** יש לי 5 בדיקות יחידה עם Mockito (`point`, `penalty`, `keyPressed`, `noSetsOnTable`, `shouldFinish`), ואף אחת מהן לא מקבילית. ראיות שעדיין אפשר להפיק:
  - להשוות את מספר השורות "Thread X starting." ו-"Thread X terminated." ב-`./logs`.
  - לכתוב בדיקת עומס (Stress Test) עם הרבה תהליכונים שקוראים ל-`keyPressed`.
  - למדוד השהיה עם `System.nanoTime()`.
- **מסקנה:** להיות כן לגבי מה שנבדק בפועל.

## 18. סקירה לאחור של הקוד שלי
- **עובדות:** רשימת הנקודות החלשות: התעוררות אבודה בהתחלה, מוניטור מחלק עמוס מדי, מילוי קלפים ללא נעילה, כתיבות מורכבות ל-`Timer.time` (שורה 46 ב-`Timer.java`, ועוד כתיבות בשורות 27, 52 ו-63), קריאה כפולה של volatile ב-`wait(timer.time)` (`wait(0)` ממתין לנצח), מספרי קסם ב-`legalset`, ל-`terminate` יש שתי משמעויות, `playerThread.sleep(...)` שנכתב על מופע, ונראות של `score` ב-`announceWinners`.
- **מסקנה:** זה הסיפור שלי לשאלה "מה היית משנה?".

---

# חלק 2: סיפורי STAR לראיונות טכניים

## סיפור A: ממשק שנשאר מגיב בעזרת צינור יצרן-צרכן

- **מצב (Situation):** במשחק Set שבניתי ב-Java, לחיצות מקשים מגיעות על ה-Event Dispatch Thread של Swing. טיפול בלחיצה יכול לקחת שניות: צריך את נעילת השולחן, אחר כך אפשר להמתין לפסק דין של המחלק, ואז השחקן קופא.
- **משימה (Task):** לגרום למטפל המקשים לחזור מיד, להגביל פעולות ממתינות ל-3 לשחקן, ולמנוע התעוררות אבודה.
- **פעולה (Action):**
  - השתמשתי ב-`ConcurrentLinkedQueue` לכל שחקן.
  - היצרן בודק `size() < 3`, מוסיף, ואז מעיר בתוך המוניטור של השחקן עצמו.
  - הצרכן ממתין בלולאת `while` שמורה ומטפל בפעולה אחת בכל פעם בתוך `synchronized(table)`.
  - מכיוון ש-notify צריך את אותו מוניטור שהצרכן מחזיק בין הבדיקה לשינה, ההתעוררות לא יכולה ללכת לאיבוד.
- **תוצאה (Result):** ה-Event Dispatch Thread רק מכניס לתור. הוא אף פעם לא ממתין ללוגיקת המשחק (מלבד כניסה קצרה מאוד למוניטור). המגבלה של 3 נאכפת.
- **מה הייתי משפר:** הייתי עובר ל-`ArrayBlockingQueue(3)` עם `offer()` ו-`take()`, מה שמסיר את קוד ה-wait/notify הידני ומאפשר אכיפה אטומית של המגבלה. כמו כן הייתי משתמש באובייקט נעילה פרטי במקום באובייקט `Thread`.
- **שאלות המשך סבירות:**
  - האם `size()` ואז `add()` הם תנאי מירוץ? זה בטוח רק כי לכל תור יש יצרן אחד בדיוק.
  - מה זה CAS? הוראת מעבד שמשנה ערך רק אם הוא עדיין שווה לערך הצפוי. אני השתמשתי בתור מספרייה שמבוסס עליה.
  - מדדת את השיפור? לא. הייתי מתאר את היתרון כתוצאה של התכנון, לא כמספר.

## סיפור B: מניעת ניקוד כפול בעזרת פוסק יחיד וסדר נעילות

- **מצב (Situation):** כמה שחקנים מתחרים על טענות לסט של 3 קלפים, בזמן שהמחלק מסיר ומערבב קלפים. שני שחקנים עלולים לטעון על קלפים חופפים, או להניח אסימונים על משבצת שמתרוקנת.
- **משימה (Task):** להבטיח שקלף מקבל ניקוד פעם אחת בלבד, לשמור על האינווריאנטה `slotToCard[x] == y iff cardToSlot[y] == x`, ולמנוע קיפאון (Deadlock).
- **פעולה (Action):**
  - שחקן מגיש את הטענה בתוך `synchronized(dealer)` ונרדם ב-`dealer.wait()` בתוך לולאה שמורה.
  - תהליכון המחלק בודק טענות אחת אחת לפי סדר הגעה (FIFO).
  - בסט תקין הוא מסיר את הקלפים, מוריד אסימונים מכל השחקנים, ורק אז מסתכל בטענה הבאה. מי שהטענה שלו התיישנה נשאר עם פחות מ-3 אסימונים ולא מקבל פסק דין.
  - הגישה לשולחן משתמשת בנעילה גסה אחת `synchronized(table)`. דחיתי נעילה לכל משבצת כי הסרת סט נוגעת בכמה משבצות ודורשת נעילות מסודרות.
  - סדר הנעילות הוא מוניטור המחלק ואז מוניטור השולחן, ואף תהליכון לא לוקח אותם בסדר ההפוך.
- **תוצאה (Result):** טענות מעובדות בסדר סדרתי, טענות חופפות לא יכולות שתיהן לקבל ניקוד, ואין מעגל נעילות, מתוך הבנייה.
- **מה הייתי משפר:**
  - `placeCardsOnTable()` לא בתוך נעילת השולחן ומסתמכת על הדגל `dealing`.
  - מוניטור המחלק משרת ארבע מטרות, מה שמכריח `notifyAll` ומאפשר לשחקנים ממתינים להעיר זה את זה.
  - הייתי מסתיר את הפרוטוקול מאחורי `dealer.submitAndAwaitVerdict(player)` עם `Condition` לכל שחקן.
- **שאלות המשך סבירות:** איך אתה יודע שאין קיפאון? (סדר הנעילות.) למה לא נעילת קריאה-כתיבה (Read-Write Lock)? (הקריאות הן חלק מרצפי קריאה-שינוי-כתיבה.)

## סיפור C: הפעלה וכיבוי דטרמיניסטיים בין הרבה תהליכונים

- **מצב (Situation):** המשחק מריץ את התהליכון הראשי, מחלק, טיימר, N שחקנים ועד N עוזרי AI. הם חייבים להתחיל בסדר ולעצור נקי, בין אם המשחק נגמר טבעית ובין אם המשתמש סוגר את החלון.
- **משימה (Task):** לכבות הכול בלי תהליכונים יתומים, כולל תהליכונים שחסומים ב-`sleep()` או ב-`wait()`.
- **פעולה (Action):**
  - השתמשתי בדגלי `volatile` יחד עם `interrupt()` ו-`join()` בסדר מלמעלה למטה.
  - המחלק עוצר ומחכה לטיימר קודם. אחר כך הוא קורא ל-`terminate()` לכל שחקן בסדר הפוך, מה שקובע את דגל ה-volatile וקוטע, ומחכה לכל אחד.
  - כל שחקן עוצר ומחכה לתהליכון ה-AI שלו.
  - השתמשתי גם בדגל וגם ב-interrupt כי ה-interrupt מעיר תהליכון חסום, והדגל שומר את הכוונה.
  - לטיימר השתמשתי ב-`timer.reset = true`, כך שהמחלק לא נחסם על הטיימר. המחיר: האיפוס נכנס לתוקף בטיק הבא.
- **תוצאה (Result):** בנתיב הכיבוי יש בעלים מוגדר לכל תהליכון. אני יכול לאמת זאת בהשוואת שורות "starting" ו-"terminated" בקבצי הלוג.
- **מה הייתי משפר:**
  - לחיצת היד בהתחלה יכולה לאבד התעוררות (`wait()` בלי תנאי). הייתי משתמש ב-`CountDownLatch`.
  - ל-`Timer.time` כותבים שני תהליכונים, עם `-=` מורכב על volatile. הייתי משתמש ב-`AtomicLong` או בכותב יחיד.
  - ל-`terminate` יש שתי משמעויות: "המשתמש יצא" ו"המשחק נגמר", והייתי מפצל אותן.
- **שאלות המשך סבירות:** למה לא `ExecutorService.shutdownNow()`? (אלה תהליכוני לולאה ארוכי חיים עם מצב משלהם.) מה `volatile` מבטיח? (נראות וסדר, לא אטומיות.)

## סיפור D: סקירת הקוד המקבילי שלי ומציאת הסיכונים האמיתיים

- **מצב (Situation):** כשחזרתי לפרויקט הסטודנטיאלי הזה כמהנדס עובד, סקרתי אותו כמו שהייתי סוקר Pull Request של חבר צוות.
- **משימה (Task):** למצוא בעיות מקביליות שבדיקות לא תופסות, ולהחליט אילו חשובות.
- **פעולה (Action):** עקבתי אחרי כל נעילה, דגל ואות, ובניתי מלאי (`playerThread`, `aiThread`, `dealer`, `table`, `timer`, ודגלי ה-volatile), ואז בדקתי כל אחד לשימוש נכון.
- **תוצאה (Result):**
  - **הפעלה:** סיכון להתעוררות אבודה, שעובד רק בזכות תזמון.
  - **נעילות:** מוניטור מחלק עמוס מדי, ואובייקטי `Thread` ששימשו כנעילות (`join()` משתמש באותו מוניטור).
  - **טיימר:** ל-`Timer.time` כותבים שני תהליכונים, ו-`wait(timer.time)` יכול להפוך ל-`wait(0)`.
  - **שולחן:** המילוי ללא נעילה.
  - **עוזר ה-AI:** הוא מסתובב בלולאה פעילה כש-`dealing` שווה true.
  - **נראות:** `announceWinners()` קורא את `score` בלי הבטחה.
- **מה הייתי משפר:** הייתי מתקן את שלושת הראשונים (`CountDownLatch` בהפעלה, נעילות ייעודיות או `Condition` לכל שחקן, ערך אטומי לטיימר), ואז מוסיף בדיקות: בדיקת עומס על `keyPressed`, ובדיקה מבוססת לוג לתהליכונים יתומים.
- **למה הסיפור הזה עובד:** הוא מראה שיקול דעת, ענווה ויכולת לתעדף.

## סיפור E: קבלת החלטות תכנון במודע

- **מצב (Situation):** בכל נקודה היו כמה כלי מקביליות תקפים.
- **משימה (Task):** לבחור את הפשוט ביותר שעדיין נכון, ולהסביר למה.
- **פעולה (Action):**
  - **נעילת השולחן:** נעילה גסה אחת במקום נעילה לכל משבצת או נעילת קריאה-כתיבה, כי יש כ-12 משבצות וקטעים קריטיים נמשכים מיקרו-שניות. נעילה לכל משבצת גם הייתה דורשת לקיחת כמה נעילות בסדר קבוע בהסרות.
  - **תהליכונים ייעודיים:** בחרתי תהליכונים ארוכי חיים ולא מאגר, כי מאגר משתלם להרבה משימות קצרות, ולכל אחד מהתהליכונים כאן יש מצב משלו והוא רץ בלולאה לאורך המשחק כולו.
  - **דגלי volatile:** השתמשתי בדגלים למצב פשוט שמתפרסם, ובנעילות למצב מורכב.
  - **פוסק יחיד:** תהליכון אחד מאמת טענות, ולכן אין הסכמה מבוזרת שאפשר לטעות בה.
- **תוצאה (Result):** קבוצה קטנה של מנגנונים שאני יכול להסביר ולהגן עליהם, עם כל מגבלה ידועה.
- **מה הייתי משפר:** להעדיף פרימיטיבים מהספרייה (`BlockingQueue`, `CountDownLatch`, `Condition`) על פני wait/notify ידני, אלא אם המטרה היא ללמוד.

---

# טענות להימנע מהן בראיונות
- אפס נפילות פריימים בממשק או השהיה של פחות ממילישנייה (אף פעם לא נמדד).
- הבטחה של 100% סיום תהליכונים או אפס קיפאונות (לומר "מתוך הבנייה, עם סדר נעילות קבוע").
- "Lock-free" על כל נתיב הקלט (ההתעוררות עדיין לוקחת מוניטור).

# תשובות מהירות על מושגים
- **`wait` מול `sleep`:** `wait` צריך ומשחרר את המוניטור ומסתיים ב-notify, בפקיעת זמן או ב-interrupt. `sleep` לא משחרר כלום ומסתיים בזמן או ב-interrupt.
- **למה לולאת `while` סביב `wait`:** התעוררות מדומה, והתנאי יכול להשתנות לפני שמחזירים לעצמך את הנעילה.
- **מה זו התעוררות אבודה (Lost Wakeup):** notify בלי מי שממתין. המוניטור הופך את "בדיקה ואז שינה" לאטומי.
- **למה `volatile`:** ה-JIT יכול להוציא קריאה מחוץ ללולאה, ומאגרי כתיבה של המעבד (Store Buffers) מעכבים נראות. זה לא הופך `x++` לאטומי.

</div>
