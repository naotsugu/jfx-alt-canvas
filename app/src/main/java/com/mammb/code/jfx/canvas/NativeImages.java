package com.mammb.code.jfx.canvas;

import com.mammb.code.canvas.lib.lib_h;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Caches the native copy of JavaFX images.
 * An image is uploaded on its first use and released when the Image is garbage collected.
 * The pixels are copied once, so later changes to a WritableImage are not reflected.
 */
final class NativeImages {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<Image, Long> HANDLES = new WeakHashMap<>();

    private NativeImages() { }

    /**
     * Returns the native handle of the image, or 0 if it cannot be used
     * (for example it is still loading).
     */
    static synchronized long handleOf(Image image) {
        Long cached = HANDLES.get(image);
        if (cached != null) return cached;

        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        if (reader == null || w <= 0 || h <= 0) return 0;

        byte[] pixels = new byte[w * h * 4];
        reader.getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), pixels, 0, w * 4);

        long handle;
        try (Arena arena = Arena.ofConfined()) {
            handle = lib_h.create_image(arena.allocateFrom(ValueLayout.JAVA_BYTE, pixels), w, h);
        }
        if (handle == 0) return 0;

        // the action must not reference the image itself
        CLEANER.register(image, new Release(handle));
        HANDLES.put(image, handle);
        return handle;
    }

    private record Release(long handle) implements Runnable {
        @Override
        public void run() {
            lib_h.destroy_image(handle);
        }
    }

}
