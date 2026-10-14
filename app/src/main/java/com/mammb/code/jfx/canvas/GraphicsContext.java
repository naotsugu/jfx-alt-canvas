/*
 * Copyright 2023-2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mammb.code.jfx.canvas;

import com.mammb.code.canvas.lib.Metrics;
import com.mammb.code.canvas.lib.Point;
import com.mammb.code.canvas.lib.lib_h;
import javafx.geometry.Point2D;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.FontWeight;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The GraphicsContext.
 * @author Naotsugu Kobayashi
 */
public class GraphicsContext implements AutoCloseable {

    private final NativeGlobal nativeGlobal = NativeGlobal.instance();
    private final Cleaner.Cleanable cleanable;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment ctxSegment;
    private MemorySegment sceneSegment;

    private final Canvas theCanvas;
    private volatile boolean closed = false;
    private int sceneWidth;
    private int sceneHeight;
    private PixelBuffer<ByteBuffer> pixelBuffer;

    private LineMetrics metrics;

    private ContextState curState;
    private LinkedList<ContextState> stateStack;


    GraphicsContext(Canvas canvas, int width, int height) {

        theCanvas = canvas;
        sceneWidth = alignWidth(width);
        sceneHeight = height;

        ctxSegment = lib_h.create_render_context(sceneWidth, sceneHeight);
        cleanable = nativeGlobal.cleaner(this, ctxSegment, arena);
        initSceneBuffer();

        curState = new ContextState();
        stateStack = new LinkedList<>();
    }

    public Canvas getCanvas() {
        return theCanvas;
    }

    void resize(int width, int height) {
        if (!closed && width > 0 && height > 0) {
            int newWidth = alignWidth(width);
            if (newWidth == sceneWidth && height == sceneHeight) {
                return;
            }
            try {
                if (lib_h.resize(ctxSegment, newWidth, height)) {
                    sceneWidth = newWidth;
                    sceneHeight = height;
                    initSceneBuffer();
                    theCanvas.getRenderPulse().request();
                }
            } finally {
                Reference.reachabilityFence(this);
            }
        }
    }

    private void initSceneBuffer() {
        sceneSegment = arena.allocate((long) sceneWidth * sceneHeight * 4); // 4bytes[BGRA]
        pixelBuffer = new PixelBuffer<>(
                sceneWidth, sceneHeight,
                sceneSegment.asByteBuffer(),
                PixelFormat.getByteBgraPreInstance());
        theCanvas.setImage(new WritableImage(pixelBuffer));
    }

    void render() {
        if (!closed) {
            try {
                lib_h.render(ctxSegment, sceneSegment);
                pixelBuffer.updateBuffer(_ -> null);
            } finally {
                Reference.reachabilityFence(this);
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cleanable.clean();
        }
    }

    // -- draw ----------------------------------------------------------------

    /**
     * Fills a rectangle using the current fill paint.
     * @param x the X position of the upper left corner of the rectangle.
     * @param y the Y position of the upper left corner of the rectangle.
     * @param w the width of the rectangle.
     * @param h the height of the rectangle.
     */
    public void fillRect(double x, double y, double w, double h) {
        if (w != 0 && h != 0 && !closed) {
            lib_h.fill_rect(ctxSegment, x, y, w, h);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Strokes a rectangle using the current stroke paint.
     * @param x the X position of the upper left corner of the rectangle.
     * @param y the Y position of the upper left corner of the rectangle.
     * @param w the width of the rectangle.
     * @param h the height of the rectangle.
     */
    public void strokeRect(double x, double y, double w, double h) {
        if (w != 0 || h != 0 && !closed) {
            lib_h.stroke_rect(ctxSegment, x, y, w, h);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Fills an oval using the current fill paint.
     * @param x the X coordinate of the upper left bound of the oval.
     * @param y the Y coordinate of the upper left bound of the oval.
     * @param w the width at the center of the oval.
     * @param h the height at the center of the oval.
     */
    public void fillOval(double x, double y, double w, double h) {
        if (w != 0 || h != 0 && !closed) {
            lib_h.fill_oval(ctxSegment, x, y, w, h);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Strokes an oval using the current stroke paint.
     * @param x the X coordinate of the upper left bound of the oval.
     * @param y the Y coordinate of the upper left bound of the oval.
     * @param w the width at the center of the oval.
     * @param h the height at the center of the oval.
     */
    public void strokeOval(double x, double y, double w, double h) {
        if (w != 0 || h != 0 && !closed) {
            lib_h.stroke_oval(ctxSegment, x, y, w, h);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Fills a rounded rectangle using the current fill paint.
     * @param x the X coordinate of the upper left bound of the oval.
     * @param y the Y coordinate of the upper left bound of the oval.
     * @param w the width at the center of the oval.
     * @param h the height at the center of the oval.
     * @param radius the arc of the rectangle corners.
     */
    public void fillRoundRect(double x, double y, double w, double h, double radius) {
        if (w != 0 && h != 0 && !closed) {
            lib_h.fill_round_rect(ctxSegment, x, y, w, h, radius);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Strokes a rounded rectangle using the current stroke paint.
     * @param x the X coordinate of the upper left bound of the oval.
     * @param y the Y coordinate of the upper left bound of the oval.
     * @param w the width at the center of the oval.
     * @param h the height at the center of the oval.
     * @param radius the arc of the rectangle corners.
     */
    public void strokeRoundRect(double x, double y, double w, double h, double radius) {
        if (w != 0 && h != 0 && !closed) {
            lib_h.stroke_round_rect(ctxSegment, x, y, w, h, radius);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Strokes a line using the current stroke paint.
     * @param x1 the X coordinate of the starting point of the line.
     * @param y1 the Y coordinate of the starting point of the line.
     * @param x2 the X coordinate of the ending point of the line.
     * @param y2 the Y coordinate of the ending point of the line.
     */
    public void strokeLine(double x1, double y1, double x2, double y2) {
        if (!closed) {
            lib_h.stroke_line(ctxSegment, x1, y1, x2, y2);
            theCanvas.getRenderPulse().request();
        }
    }

    /**
     * Fills a polygon with the given points using the currently set fill paint.
     * A {@code null} value for any of the arrays will be ignored and nothing will be drawn.
     * @param xPoints array containing the x coordinates of the polygon's points or null.
     * @param yPoints array containing the y coordinates of the polygon's points or null.
     * @param nPoints the number of points that make the polygon.
     */
    public void fillPolygon(double[] xPoints, double[] yPoints, int nPoints) {
        if (nPoints >= 3 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.fill_polygon(ctxSegment,
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                        nPoints);
                theCanvas.getRenderPulse().request();
            }
        }
    }

    /**
     * Strokes a polygon with the given points using the currently set stroke paint.
     * A {@code null} value for any of the arrays will be ignored and nothing will be drawn.
     * @param xPoints array containing the x coordinates of the polygon's points or null.
     * @param yPoints array containing the y coordinates of the polygon's points or null.
     * @param nPoints the number of points that make the polygon.
     */
    public void strokePolygon(double[] xPoints, double[] yPoints, int nPoints) {
        if (nPoints >= 2 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.stroke_polygon(ctxSegment,
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                        nPoints);
                theCanvas.getRenderPulse().request();
            }
        }
    }

    /**
     * Strokes a polyline with the given points using the currently set stroke
     * paint attribute.
     * A {@code null} value for any of the arrays will be ignored and nothing will be drawn.
     * @param xPoints array containing the x coordinates of the polyline's points or null.
     * @param yPoints array containing the y coordinates of the polyline's points or null.
     * @param nPoints the number of points that make the polyline.
     */
    public void strokePolyline(double[] xPoints, double[] yPoints, int nPoints) {
        if (nPoints >= 2 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.stroke_polyline(ctxSegment,
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                        localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                        nPoints);
                theCanvas.getRenderPulse().request();
            }
        }
    }

    /**
     * Fills the given string of text at position x, y
     * with the current fill paint attribute.
     * A {@code null} text value will be ignored.
     * @param text the string of text or null.
     * @param x position on the x-axis.
     * @param y position on the y-axis.
     * @return the bottom-right corner of the drawn text.
     */
    public Point2D fillText(String text, double x, double y) {
        if (text == null || text.isEmpty() || closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.fill_text(localArena, ctxSegment, localArena.allocateFrom(text), x, y);
            theCanvas.getRenderPulse().request();
            return new Point2D(Point.x(end), Point.y(end));
        }
    }

    /**
     * Draws the given string of text at position x, y
     * with the current stroke paint attribute.
     * A {@code null} text value will be ignored.
     * @param text the string of text or null.
     * @param x position on the x-axis.
     * @param y position on the y-axis.
     * @return the bottom-right corner of the drawn text.
     */
    public Point2D strokeText(String text, double x, double y) {
        if (text == null || text.isEmpty() || closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.stroke_text(localArena, ctxSegment, localArena.allocateFrom(text), x, y);
            theCanvas.getRenderPulse().request();
            return new Point2D(Point.x(end), Point.y(end));
        }
    }

    /**
     * Fills text and includes a maximum width of the string.
     * If the width of the text extends past max width, then it will be sized
     * to fit.
     * @param text the string of text or null.
     * @param x position on the x-axis.
     * @param y position on the y-axis.
     * @param maxWidth  maximum width the text string can have.
     * @return the bottom-right corner of the drawn text.
     */
    public Point2D fillText(String text, double x, double y, double maxWidth) {
        if (maxWidth <= 0 && !closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.stroke_text_max_width(
                    localArena, ctxSegment, localArena.allocateFrom(text), x, y, maxWidth);
            theCanvas.getRenderPulse().request();
            return new Point2D(Point.x(end), Point.y(end));
        }
    }

    /**
     * Draws text with stroke paint and includes a maximum width of the string.
     * If the width of the text extends past max width, then it will be sized
     * to fit.
     * @param text the string of text or null.
     * @param x position on the x-axis.
     * @param y position on the y-axis.
     * @param maxWidth  maximum width the text string can have.
     * @return the bottom-right corner of the drawn text.
     */
    public Point2D strokeText(String text, double x, double y, double maxWidth) {
        if (maxWidth <= 0 && !closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.stroke_text_max_width(
                    localArena, ctxSegment, localArena.allocateFrom(text), x, y, maxWidth);
            theCanvas.getRenderPulse().request();
            return new Point2D(Point.x(end), Point.y(end));
        }
    }

    /**
     * Clears a portion of the canvas with a transparent color value.
     * @param x X position of the upper left corner of the rectangle.
     * @param y Y position of the upper left corner of the rectangle.
     * @param w width of the rectangle.
     * @param h height of the rectangle.
     */
    public void clearRect(double x, double y, double w, double h) {
        if (w != 0 && h != 0 && !closed) {
            lib_h.clear_rect(ctxSegment, x, y, w, h);
            theCanvas.getRenderPulse().request();
        }
    }

    // -- state ---------------------------------------------------------------

    /**
     * Sets the current fill paint attribute. The default value is BLACK.
     * @param p The Paint to be used as the fill Paint or null.
     */
    public void setFill(Paint p) {
        if (p != null  && curState.fill != p && !closed) {
            curState.fill = p;
            if (p instanceof Color c) {
                lib_h.set_fill(ctxSegment,
                        b(c.getRed()), b(c.getGreen()), b(c.getBlue()), b(c.getOpacity()));
            }
        }
    }

    /**
     * Gets the current fill paint attribute.
     * @return p The {@code Paint} to be used as the fill {@code Paint}.
     */
    public Paint getFill() {
        return curState.fill;
    }

    /**
     * Sets the current stroke paint attribute.
     * The default value is {@link Color#BLACK BLACK}.
     * @param p The Paint to be used as the stroke Paint or null.
     */
    public void setStroke(Paint p) {
        if (p != null && curState.stroke != p && !closed) {
            curState.stroke = p;
            if (p instanceof Color c) {
                lib_h.set_stroke(ctxSegment,
                        b(c.getRed()), b(c.getGreen()), b(c.getBlue()), b(c.getOpacity()));
            }
        }
    }

    /**
     * Gets the current stroke.
     * @return the {@code Paint} to be used as the stroke {@code Paint}.
     */
    public Paint getStroke() {
        return curState.stroke;
    }

    /**
     * Sets the current line width.
     * The default value is {@code 1.0}.
     * @param lw value in the range {0-positive infinity}, with any other
     *           value being ignored and leaving the value unchanged.
     */
    public void setLineWidth(double lw) {
        if (lw > 0 && lw < Double.POSITIVE_INFINITY && !closed) {
            if (curState.linewidth != lw) {
                curState.linewidth = lw;
                lib_h.set_line_width(ctxSegment, lw);
            }
        }
    }

    /**
     * Gets the current line width.
     * The default value is {@code 1.0}.
     * @return value between 0 and infinity.
     */
    public double getLineWidth() {
        return curState.linewidth;
    }

    /**
     * Sets the current stroke line cap.
     * The default value is {@link StrokeLineCap#SQUARE SQUARE}.
     * @param cap {@code StrokeLineCap} with a value of
     * Butt, Round, or Square or null.
     */
    public void setLineCap(StrokeLineCap cap) {
        if (cap != null && curState.linecap != cap && !closed) {
            lib_h.set_line_cap(ctxSegment, switch (cap) {
                case SQUARE -> 2; case ROUND -> 1; case BUTT -> 0;
            });
        }
    }

    /**
     * Gets the current stroke line cap.
     * The default value is {@link StrokeLineCap#SQUARE SQUARE}.
     * @return {@code StrokeLineCap} with a value of Butt, Round, or Square.
     */
    public StrokeLineCap getLineCap() {
        return curState.linecap;
    }

    /**
     * Sets the current stroke line join.
     * The default value is {@link StrokeLineJoin#MITER}.
     * @param join {@code StrokeLineJoin} with a value of Miter, Bevel, or Round or null.
     */
    public void setLineJoin(StrokeLineJoin join) {
        if (join != null && curState.linejoin != join && !closed) {
            curState.linejoin = join;
            lib_h.set_line_join(ctxSegment, switch (join) {
                case BEVEL -> 0; case ROUND -> 1; case MITER -> 2;
            });
        }
    }

    /**
     * Gets the current stroke line join.
     * The default value is {@link StrokeLineJoin#MITER}.
     * @return {@code StrokeLineJoin} with a value of Miter, Bevel, or Round.
     */
    public StrokeLineJoin getLineJoin() {
        return curState.linejoin;
    }

    public List<String> loadFont(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(256);
            int faces = lib_h.load_font_file(
                    arena.allocateFrom(path.toAbsolutePath().toString()),
                    name, name.byteSize());
            return IntStream.range(0, faces).mapToObj(name::getString).toList();
        }
    }

    /**
     * Sets the current Font.
     * The default value is specified by {@link Font#getDefault()}.
     * @param f the Font or null.
     */
    public void setFont(Font f) {
        setFontFamily(f.getFamily());
        setFontSize(f.getSize());
    }

    /**
     * Gets the current Font.
     * The default value is specified by {@link Font#getDefault()}.
     * @return the Font
     */
    public Font getFont() {
        return Font.font(
                curState.fontFamily,
                FontWeight.findByWeight((int) curState.fontWeight),
                curState.fontSize);
    }

    /**
     * Sets the font family.
     * @param f the font family.
     */
    public void setFontFamily(String f) {
        if (f != null && !curState.fontFamily.equals(f) && !closed) {
            curState.fontFamily = f;
            try (var localArena = Arena.ofConfined()) {
                lib_h.set_font_family(ctxSegment, localArena.allocateFrom(f));
                metrics = null;
            }
        }
    }

    /**
     * Gets the font family.
     * @return the font family
     */
    public String getFontFamily() {
        return curState.fontFamily;
    }

    /**
     * Sets the font size.
     * @param s the font size.
     */
    public void setFontSize(double s) {
        if (s > 0 && curState.fontSize != s && !closed) {
            curState.fontSize = s;
            lib_h.set_font_size(ctxSegment, s);
            metrics = null;
        }
    }

    /**
     * Gets the font size.
     * @return the font size
     */
    public double getFontSize() {
        return curState.fontSize;
    }

    /**
     * Sets the current Font Smoothing Type.
     * @param fontsmoothing the {@link FontSmoothingType} or null
     */
    public void setFontSmoothingType(FontSmoothingType fontsmoothing) {
        // do nothing
    }

    /**
     * Gets the current Font Smoothing Type.
     * @return the {@link FontSmoothingType}
     */
    public FontSmoothingType getFontSmoothingType() {
        return FontSmoothingType.GRAY;
    }

    /**
     * Sets the font weight on the CSS scale: 100 (thin) to 900 (black).
     * The default value is {@code 700}.
     * @param fw the font weight
     */
    public void setFontWeight(double fw) {
        if (fw > 0 && fw <= 1000 && !closed) {
            if (curState.fontWeight != fw) {
                curState.fontWeight = fw;
                lib_h.set_font_weight(ctxSegment, fw);
                metrics = null;
            }
        }
    }

    /**
     * Saves the following attributes onto a stack.
     */
    public void save() {
        stateStack.push(curState.copy());
    }

    /**
     * Pops the state off of the stack, setting the following attributes to
     * their value at the time when that state was pushed onto the stack.
     * If the stack is empty then nothing is changed.
     */
    public void restore() {
        if (!stateStack.isEmpty()) {
            ContextState savedState = stateStack.pop();
            savedState.restore(this);
        }
    }

    /**
     * Gets the line metrics of the current font.
     * @return the line metrics
     */
    public LineMetrics getMetrics() {
        if (closed) return null;
        if (metrics != null) return metrics;
        try (var localArena = Arena.ofConfined()) {
            MemorySegment ret = lib_h.get_line_metrics(localArena, ctxSegment);
            return metrics = new LineMetrics(
                    Metrics.ascent(ret),
                    Metrics.descent(ret),
                    Metrics.leading(ret),
                    Metrics.line_height(ret),
                    Metrics.baseline(ret),
                    Metrics.advance(ret));
        }
    }

    /**
     * Gets the advance width of a single Unicode code point in the current font.
     * @param codePoint a Unicode code point
     * @return the advance width of a single Unicode code point in the current font
     */
    public double getAdvance(int codePoint) {
        return lib_h.get_advance(ctxSegment, codePoint);
    }

    /**
     * Gets the advance width of the given text in the current font.
     * @param text the text
     * @return the advance width of the given text in the current font
     */
    public double getAdvance(String text) {
        try (var localArena = Arena.ofConfined()) {
            return lib_h.get_text_advance(ctxSegment, localArena.allocateFrom(text));
        }
    }

    // -- helper --------------------------------------------------------------

    /**
     * Rounds the width up to the next multiple of 64 pixels (= 256 bytes per row).
     * e.g. 1000 -> 1024, 1024 -> 1024.
     */
    private static int alignWidth(int width) {
        int widthAlignment = 256 / 4;
        return (width + widthAlignment - 1) & -widthAlignment;
    }

    private byte b(double v) {
        int val = (int) Math.round(v * 255.0);
        return (byte) val;
    }

}
