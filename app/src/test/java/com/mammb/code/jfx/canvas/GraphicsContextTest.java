package com.mammb.code.jfx.canvas;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GraphicsContextTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException e) {
            // Already started
            latch.countDown();
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testResize() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                Canvas canvas = new Canvas(400, 300);
                GraphicsContext gc = canvas.getGraphicsContext();

                assertEquals(448, canvas.getImage().getWidth());
                assertEquals(300, canvas.getImage().getHeight());

                gc.fillRect(0, 0, 400, 300);
                gc.render();

                canvas.setSize(500, 350);
                assertEquals(512, canvas.getImage().getWidth());
                assertEquals(350, canvas.getImage().getHeight());

                gc.fillRect(0, 0, 500, 350);
                gc.render();

                canvas.setSize(600, 400);
                assertEquals(640, canvas.getImage().getWidth());
                assertEquals(400, canvas.getImage().getHeight());

                gc.fillRect(0, 0, 600, 400);
                gc.render();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (error.get() != null) {
            fail(error.get());
        }
    }
}
