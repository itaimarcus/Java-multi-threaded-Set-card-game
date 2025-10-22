package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.UtilImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealerTest {

    Dealer dealer;
    @Mock
    private UserInterface ui;
    @Mock
    private Table table;
    @Mock
    private Logger logger;

    private Player [] players;

    void assertInvariants() {
        assertTrue(players.length >= 0);
        assertTrue(dealer.deck.size() >= 0);
    }

    @BeforeEach
    void setUp() {
        // purposely do not find the configuration files (use defaults here).
        Env env = new Env(logger, new Config(logger, ""), ui, new UtilImpl(new Config(logger, "")));
        players = new Player[2];
        for(int i=0; i<players.length; i++)
        {
            players[i] = new Player(env, dealer, table, i, false);
        }
        dealer = new Dealer(env, table, players);
        assertInvariants();
    }

    @AfterEach
    void tearDown() {
        assertInvariants();
    }
    @Test
    void noSetsOnTable()
    {

        //test if noSetsOnTable returns true when there are no sets on the table

        //set up a table with no set on it
        Integer[] slots = {1, 3, 5, null,
                           null, null, null, null,
                           null, null, null, null
                        };
        when(table.getSlots()).thenReturn(slots);


        assertEquals(true, dealer.noSetsOnTable());
        
    }

    @Test
    void shouldFinish(){
 

        //tests if when the dealer gets a terminate order he actually terminates

        //at first no teminate order is placed
        assertEquals(false, dealer.shouldFinish());

        //a terminate order is issued
        dealer.terminate = true;

        //checks that the terminate order went through
        assertEquals(true, dealer.shouldFinish());
    }
}
