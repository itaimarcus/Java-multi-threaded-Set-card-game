package bguspl.set.ex;
import bguspl.set.Env;

public class Timer implements Runnable{
    private long shuffleTime;
    public volatile long time;
    public volatile boolean terminate;
    private Env env;
    private long change;

    public volatile boolean reset;

    public Timer(long shuffleTime, Env env) {
        this.time = shuffleTime; //time of a round
        this.shuffleTime = shuffleTime;
        this.env = env;
        this.terminate = false;
        this.change = 1000;
        if(shuffleTime == 0) change *= -1;
    }

    public void run() {
        try {
            while(!terminate) {
                while((time > 0 || shuffleTime == 0) && !terminate) {
                    if(reset) { //if dealer asked for reset
                        time = shuffleTime; //reset
                        reset = false; //return flag back to false
                    }
                    if(shuffleTime == 0) { //special timer - time since last action
                        env.ui.setElapsed(time);
                        Thread.currentThread().sleep(1000);
                    }
                    else { //regular timer
                        if(time > env.config.turnTimeoutWarningMillis) { //not warning yet
                            env.ui.setCountdown(time, time <= env.config.turnTimeoutWarningMillis);
                            Thread.currentThread().sleep(1000);
                        }
                        else {
                            for (int i=9; i>=0; i--) {
                                env.ui.setCountdown(time + i*100 - 1000, time <= env.config.turnTimeoutWarningMillis);
                                Thread.currentThread().sleep(100);
                            }
                        }
                    }
                    time -= change;
               }

               env.ui.setCountdown(time, time <= env.config.turnTimeoutWarningMillis);
                synchronized(this) {
                    wait();
                    time = shuffleTime;
                }
            }
        }
        catch(InterruptedException e) {}
    }

    public boolean done(){
        if(time > 0)
            return true;
        else {
            time = shuffleTime;
            return false;
        }
    }
}
