package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    protected final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    public volatile boolean terminate;

    ///
    private Thread[] playersThreads;
    private LinkedList<Integer> slotsToRemove;
    private Timer timer;
    private Queue<Player> submitedPlayers;
    protected volatile boolean dealing;
    ///

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime;

    private Thread timerThread;

    private Thread dealerThread;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        
        ///
        this.playersThreads = new Thread[players.length];
        this.slotsToRemove = new LinkedList<>();
        this.submitedPlayers = new LinkedList<>();
        this.reshuffleTime = env.config.turnTimeoutMillis;
        this.timer = new Timer(reshuffleTime, env);
        this.dealing = true;
        ///
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        ///
        dealerThread = Thread.currentThread();
        for (int i = 0; i < playersThreads.length; i++) {
            playersThreads[i] = new Thread(players[i]);
            playersThreads[i].start();
            try {
                synchronized(this) {
                    wait();
                }
            } catch(InterruptedException e) {}
        }

        if (reshuffleTime >= 0) {
            timerThread = new Thread(timer);
            timerThread.start();
        }

        ///
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            synchronized(timer) {
                timer.notify();
            }
            dealing = false;
            timerLoop();
            dealing = true;
            removeAllCardsFromTable();
        }

        announceWinners();

        try {
            if(timerThread != null) {
                timer.terminate = true;
                timerThread.interrupt();
                timerThread.join();
            }
        } 
        catch(InterruptedException ign) {}

        for (int i = players.length-1; i>=0; i--){
            players[i].terminate();
            try {
                playersThreads[i].join();
            } catch(InterruptedException e) {}
        }
        if(!terminate) {
            try {
                dealerThread.sleep(env.config.endGamePauseMillies);
            } catch (InterruptedException e) {}
            env.ui.dispose();
        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate&& (timer.done()|| (env.config.turnTimeoutMillis <= 0&&!noSetsOnTable() ) ) ){
            sleepUntilWokenOrTimeout();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        dealerThread.interrupt();
        try {
            dealerThread.join();
        } catch (InterruptedException e) {}
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    protected boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        ///
        synchronized(table) {
            Iterator<Integer> it = slotsToRemove.iterator();
            while(it.hasNext()) {
                Integer slotToRemove = it.next();
                for(Player p : players) {
                    if (p.myCards.contains(table.slotToCard[slotToRemove])) {
                        p.myCards.remove((Integer)table.slotToCard[slotToRemove]);
                        env.ui.removeToken(p.id, slotToRemove);
                    }
                }
                table.removeCard(slotToRemove); 
            }
            slotsToRemove.clear();
            terminate = isOver();
            //
        }
        ///
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {

        int slot = 0;
        while (deck.size() != 0 && table.countCards() < env.config.rows*env.config.columns) {
            if (table.slotToCard[slot] == null) {
                table.placeCard(deck.remove(0), slot);
            }
            slot++;
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {
        while (submitedPlayers.isEmpty() && timer.time != 0) {
            notifyAll();
            if (timer.time > 0) {
                try {
                    wait(timer.time);
                } catch(InterruptedException e) {
                    return;
                }
            }
            else {
                try {
                    wait();
                } catch(InterruptedException e) {
                    return;
                }
                checklegal();
                break;
            }
        }
        if (timer.time != 0) checklegal();
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized(table){
            for(int slot = 0; slot < table.slotToCard.length ; slot++){
                if(table.slotToCard[slot] == null)
                    continue;
                for(Player p : players) {
                    if(p.myCards.contains(table.slotToCard[slot])){
                        p.myCards.remove((Integer)table.slotToCard[slot]);
                        env.ui.removeToken(p.id, slot);
                    }
                }
                deck.add(table.slotToCard[slot]);
                table.removeCard(slot);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        LinkedList<Integer> winners = new LinkedList<>();
        int maxScore = 0;
        for(Player p : players){
            maxScore = Math.max(maxScore, p.score());
        }
        for(Player p : players){
            if(p.score() == maxScore)
                winners.add(p.id);
        }
        env.ui.announceWinner(winners.stream().mapToInt(Integer::intValue).toArray());
    }

    public void submitCards(Player submited){
        submitedPlayers.add(submited);
    }

    public void checklegal() {
        while (!submitedPlayers.isEmpty()){
            Player p = submitedPlayers.remove();
            if (p.myCards.size() < env.config.featureSize) {
                notifyAll();
            }
            else {
                p.isFrozen = true;
                if (env.util.testSet(p.myCards.stream().mapToInt(Integer::intValue).toArray())) {
                    p.legalset = 1;
                    for (int card : p.myCards) {
                        slotsToRemove.add(table.cardToSlot[card]);
                    }
                    removeCardsFromTable();
                    placeCardsOnTable();
                    timer.reset = true;
                }
                else {
                    p.legalset = 0;
                }
                notifyAll();
            }
        }
    }

    private boolean isOver(){
        List<Integer> toCheck = new LinkedList<>();
        toCheck.addAll(deck);
        List<Integer> tmp = new ArrayList<>(Arrays.asList(table.slotToCard));
        while(tmp.contains(null))
            tmp.remove(null);
        toCheck.addAll(tmp);
        return env.util.findSets(toCheck, 1).size() == 0;
    }

    protected boolean noSetsOnTable(){
        List<Integer> tmp = new ArrayList<>(Arrays.asList(table.getSlots()));
        while(tmp.contains(null))
            tmp.remove(null);
        return env.util.findSets(tmp, 1).size() == 0;
    }
}
