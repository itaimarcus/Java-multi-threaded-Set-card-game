package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ConcurrentLinkedQueue;

@ExtendWith(MockitoExtension.class)
class PlayerTest {

    Player player;
    @Mock
    Util util;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Dealer dealer;
    @Mock
    private Logger logger;
    @Mock
    private Thread playerThread;

    void assertInvariants() {
        assertTrue(player.id >= 0);
        assertTrue(player.score() >= 0);
    }

    @BeforeEach
    void setUp() {
        // purposely do not find the configuration files (use defaults here).
        Env env = new Env(logger, new Config(logger, ""), ui, util);
        player = new Player(env, dealer, table, 0, false);
        assertInvariants();
    }

    @AfterEach
    void tearDown() {
        assertInvariants();
    }

    @Test
    void point() {

        // force table.countCards to return 3
        when(table.countCards()).thenReturn(3); // this part is just for demonstration

        // calculate the expected score for later
        int expectedScore = player.score() + 1;

        // call the method we are testing
        try{player.point();} catch(InterruptedException e){}

        // check that the score was increased correctly
        assertEquals(expectedScore, player.score());

        // check that ui.setScore was called with the player's id and the correct score
        verify(ui).setScore(eq(player.id), eq(expectedScore));
    }

    @Test
    void penalty()
    {
        // calculate the expected score for later
        int expectedScore = player.score();

        // call the method we are testing
        try {player.penalty();}catch(InterruptedException e){}

        // check that the score was increased correctly
        assertEquals(expectedScore, player.score());

        // check that ui.setFreeze was called with the player's id and the correct milliseconds
        Long x = new Long(3000);
        verify(ui).setFreeze(eq(player.id), eq(x));
        verify(ui).setFreeze(eq(player.id), eq(x - 1000));
        verify(ui).setFreeze(eq(player.id), eq(x - 2000));
        verify(ui).setFreeze(eq(player.id), eq(x - 3000));  
    }

    @Test 
    void keyPressed(){

        //set up for the test, where q is the expected incoming actions queue
        ConcurrentLinkedQueue<Integer> q = new ConcurrentLinkedQueue<>();
        q.add(1);
        dealer.dealing = false;
        player.isFrozen = false;

        when(table.getCard(1)).thenReturn(1);
        dealer.terminate = false;

        player.playerThread = new Thread();

        //make a key press when the player is not frozen and the queue is empty
        player.keyPressed(1);

        //make sure that the order went through
        assertEquals(q.peek(), player.actionsToPerform.peek());

    }
}