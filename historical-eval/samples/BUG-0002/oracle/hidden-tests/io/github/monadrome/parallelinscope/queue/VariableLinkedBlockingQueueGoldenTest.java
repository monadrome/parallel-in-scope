package io.github.monadrome.parallelinscope.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VariableLinkedBlockingQueueGoldenTest {

    @Test
    void shrinkingBelowCurrentSizeRejectsOffersUntilElementsAreRemoved() throws Exception {
        VariableLinkedBlockingQueue<String> queue = new VariableLinkedBlockingQueue<>(3);
        assertTrue(queue.offer("a"));
        assertTrue(queue.offer("b"));

        queue.setCapacity(1);

        assertEquals(2, queue.size());
        assertEquals(-1, queue.remainingCapacity());
        assertFalse(queue.offer("c"));
        assertFalse(queue.offer("c", 50, TimeUnit.MILLISECONDS));

        assertEquals("a", queue.take());
        assertEquals("b", queue.take());
        assertTrue(queue.offer("c"));
        assertEquals(1, queue.size());
    }
}
