package bguspl.set.ex;

import java.util.LinkedList;
import java.util.Random;

import java.util.concurrent.ConcurrentLinkedQueue;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    protected final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    protected Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private final long SECOND = 1000;

    ///
    protected ConcurrentLinkedQueue<Integer> actionsToPerform;
    protected LinkedList<Integer> myCards;
    private Dealer dealer;
    public int legalset;
    protected volatile boolean isFrozen;
    ///

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.actionsToPerform = new ConcurrentLinkedQueue<>();
        this.myCards = new LinkedList<>();
        this.legalset = 2;
        this.isFrozen = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        synchronized(dealer){
            dealer.notify();
        }

        while (!terminate) {
            synchronized(playerThread) {
                while(actionsToPerform.size() == 0 && !terminate) {
                    try{
                        playerThread.wait();
                    } catch(InterruptedException e) {
                        break;
                    }
                }
            }

            synchronized(table) {
                if(!terminate)
                    performAction();
            }

            if (myCards.size() == env.config.featureSize) {
                synchronized(dealer) {
                    dealer.submitCards(this);
                    while (myCards.size()==env.config.featureSize && legalset==2 && !terminate) {
                        dealer.notifyAll();
                        try {
                            dealer.wait();
                        } catch(InterruptedException e){
                            break;
                        }
                    }
                }
                if(legalset == 0) try{penalty();} catch(InterruptedException e){break;}
                if(legalset == 1) try{point();} catch(InterruptedException e){break;}
                if(legalset != 2) actionsToPerform.clear();
            }

            legalset = 2;

            if(!human) synchronized(aiThread){aiThread.notify();}
        }
        if (!human) try { aiThread.interrupt(); aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            
            Random rnd = new Random();

            while (!terminate) {
            try{
                synchronized(aiThread){
                    while(actionsToPerform.size() == 3 || isFrozen) {
                        aiThread.wait();
                    }
                }
                int key = rnd.nextInt(env.config.playerKeys(id).length);
                keyPressed(key);
            }
            catch (InterruptedException ignored) {}
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {

        terminate = true;
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {

        if(dealer.dealing || table.getCard(slot) == null || isFrozen || dealer.terminate) return;

        if(myCards.size() == 3 && !myCards.contains(table.getCard(slot))) return;

        if(actionsToPerform.size() < 3) {
            actionsToPerform.add(slot);
            synchronized(playerThread) {
                playerThread.notify();
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() throws InterruptedException{
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

        env.ui.setScore(id, ++score);
        
        long freezeTime = env.config.pointFreezeMillis;
        while(freezeTime > 0 && !terminate){
            env.ui.setFreeze(id, freezeTime);
            try{playerThread.sleep(Math.min(SECOND, freezeTime));} catch(InterruptedException e){return;}
            freezeTime -= 1000;
        }
        isFrozen = false;
        env.ui.setFreeze(id, 0);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() throws InterruptedException{

        long freezeTime = env.config.penaltyFreezeMillis;
        while(freezeTime > 0 && !terminate){
            env.ui.setFreeze(id, freezeTime);
            try{playerThread.sleep(Math.min(SECOND, freezeTime));} catch(InterruptedException e){return;}
            freezeTime -= 1000;
        }
        isFrozen = false;
        env.ui.setFreeze(id, 0);
    }

    public int score() {
        return score;
    }

    public void performAction(){
        int slot = actionsToPerform.remove();
        
        if(table.slotToCard[slot] == null || dealer.dealing) return;

        if (myCards.contains(table.slotToCard[slot])){
            env.ui.removeToken(id, slot);
            myCards.remove(table.slotToCard[slot]);
        }
        else if (myCards.size() < env.config.featureSize){
            env.ui.placeToken(id, slot);
            myCards.add(table.slotToCard[slot]);
        }
        if (!human){
            synchronized(aiThread){
                aiThread.notify();
            }
        }
    }
}
