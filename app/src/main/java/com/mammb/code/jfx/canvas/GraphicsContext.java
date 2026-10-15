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
import javafx.geometry.VPos;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

/**
 * The GraphicsContext.
 * <p>
 * Not supported (no natural counterpart in Vello): effects ({@code setEffect},
 * {@code applyEffect}), {@code getPixelWriter}, {@code ImagePattern} paints,
 * proportional gradients and the RED / GREEN / BLUE blend modes.
 * Unsupported values are ignored and leave the current value unchanged.
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

    private void request() {
        theCanvas.getRenderPulse().request();
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
            request();
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
        if ((w != 0 || h != 0) && !closed) {
            lib_h.stroke_rect(ctxSegment, x, y, w, h);
            request();
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
        if (w != 0 && h != 0 && !closed) {
            lib_h.fill_oval(ctxSegment, x, y, w, h);
            request();
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
        if ((w != 0 || h != 0) && !closed) {
            lib_h.stroke_oval(ctxSegment, x, y, w, h);
            request();
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
            request();
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
            request();
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
            request();
        }
    }

    /**
     * Fills an arc using the current fill paint.
     * @param x the X coordinate of the arc's ellipse bounds.
     * @param y the Y coordinate of the arc's ellipse bounds.
     * @param w the width of the arc's ellipse bounds.
     * @param h the height of the arc's ellipse bounds.
     * @param startAngle the starting angle of the arc in degrees.
     * @param arcExtent the angular extent of the arc in degrees.
     * @param closure closure type (OPEN, CHORD, or ROUND).
     */
    public void fillArc(double x, double y, double w, double h,
            double startAngle, double arcExtent, ArcType closure) {
        if (w != 0 && h != 0 && closure != null && !closed) {
            lib_h.fill_arc(ctxSegment, x, y, w, h, startAngle, arcExtent, arcTypeCode(closure));
            request();
        }
    }

    /**
     * Strokes an arc using the current stroke paint.
     * @param x the X coordinate of the arc's ellipse bounds.
     * @param y the Y coordinate of the arc's ellipse bounds.
     * @param w the width of the arc's ellipse bounds.
     * @param h the height of the arc's ellipse bounds.
     * @param startAngle the starting angle of the arc in degrees.
     * @param arcExtent the angular extent of the arc in degrees.
     * @param closure closure type (OPEN, CHORD, or ROUND).
     */
    public void strokeArc(double x, double y, double w, double h,
            double startAngle, double arcExtent, ArcType closure) {
        if ((w != 0 || h != 0) && closure != null && !closed) {
            lib_h.stroke_arc(ctxSegment, x, y, w, h, startAngle, arcExtent, arcTypeCode(closure));
            request();
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
        if (xPoints != null && yPoints != null && nPoints >= 3 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.fill_polygon(ctxSegment,
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                    nPoints);
                request();
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
        if (xPoints != null && yPoints != null && nPoints >= 2 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.stroke_polygon(ctxSegment,
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                    nPoints);
                request();
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
        if (xPoints != null && yPoints != null && nPoints >= 2 && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.stroke_polyline(ctxSegment,
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, xPoints),
                    localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, yPoints),
                    nPoints);
                request();
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
            request();
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
            request();
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
        if (text == null || text.isEmpty() || maxWidth <= 0 || closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.fill_text_max_width(
                localArena, ctxSegment, localArena.allocateFrom(text), x, y, maxWidth);
            request();
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
        if (text == null || text.isEmpty() || maxWidth <= 0 || closed) return new Point2D(x, y);
        try (var localArena = Arena.ofConfined()) {
            MemorySegment end = lib_h.stroke_text_max_width(
                localArena, ctxSegment, localArena.allocateFrom(text), x, y, maxWidth);
            request();
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
            request();
        }
    }

    // -- image ---------------------------------------------------------------

    /**
     * Draws an image at the given x, y position using the width and height of the image.
     * @param img the image to be drawn or null.
     * @param x the X coordinate on the destination for the upper left of the image.
     * @param y the Y coordinate on the destination for the upper left of the image.
     */
    public void drawImage(Image img, double x, double y) {
        if (img == null) return;
        drawImage(img, 0, 0, img.getWidth(), img.getHeight(),
            x, y, img.getWidth(), img.getHeight());
    }

    /**
     * Draws an image into the given destination rectangle of the canvas.
     * The image is scaled to fit the rectangle.
     * @param img the image to be drawn or null.
     * @param x the X coordinate on the destination for the upper left of the image.
     * @param y the Y coordinate on the destination for the upper left of the image.
     * @param w the width of the destination rectangle.
     * @param h the height of the destination rectangle.
     */
    public void drawImage(Image img, double x, double y, double w, double h) {
        if (img == null) return;
        drawImage(img, 0, 0, img.getWidth(), img.getHeight(), x, y, w, h);
    }

    /**
     * Draws the current source rectangle of the image into the destination rectangle.
     * The pixels of the image are copied to the native side on first use,
     * so later changes to a {@code WritableImage} are not reflected.
     * @param img the image to be drawn or null.
     * @param sx the source rectangle's X coordinate position.
     * @param sy the source rectangle's Y coordinate position.
     * @param sw the source rectangle's width.
     * @param sh the source rectangle's height.
     * @param dx the destination rectangle's X coordinate position.
     * @param dy the destination rectangle's Y coordinate position.
     * @param dw the destination rectangle's width.
     * @param dh the destination rectangle's height.
     */
    public void drawImage(Image img, double sx, double sy, double sw, double sh,
            double dx, double dy, double dw, double dh) {
        if (img == null || closed || sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return;
        long handle = NativeImages.handleOf(img);
        if (handle == 0) return;
        lib_h.draw_image(ctxSegment, handle, sx, sy, sw, sh, dx, dy, dw, dh);
        request();
    }

    // -- path ----------------------------------------------------------------

    /** Clears the current path and starts a new one. */
    public void beginPath() {
        if (!closed) lib_h.begin_path(ctxSegment);
    }

    /**
     * Starts a new sub-path at the given coordinates.
     * @param x0 the X coordinate of the starting point.
     * @param y0 the Y coordinate of the starting point.
     */
    public void moveTo(double x0, double y0) {
        if (!closed) lib_h.move_to(ctxSegment, x0, y0);
    }

    /**
     * Adds a line to the path.
     * @param x1 the X coordinate of the end point.
     * @param y1 the Y coordinate of the end point.
     */
    public void lineTo(double x1, double y1) {
        if (!closed) lib_h.line_to(ctxSegment, x1, y1);
    }

    /**
     * Adds a quadratic Bézier curve to the path.
     * @param xc the X coordinate of the control point.
     * @param yc the Y coordinate of the control point.
     * @param x1 the X coordinate of the end point.
     * @param y1 the Y coordinate of the end point.
     */
    public void quadraticCurveTo(double xc, double yc, double x1, double y1) {
        if (!closed) lib_h.quad_to(ctxSegment, xc, yc, x1, y1);
    }

    /**
     * Adds a cubic Bézier curve to the path.
     * @param xc1 the X coordinate of the first control point.
     * @param yc1 the Y coordinate of the first control point.
     * @param xc2 the X coordinate of the second control point.
     * @param yc2 the Y coordinate of the second control point.
     * @param x1 the X coordinate of the end point.
     * @param y1 the Y coordinate of the end point.
     */
    public void bezierCurveTo(double xc1, double yc1, double xc2, double yc2,
            double x1, double y1) {
        if (!closed) lib_h.bezier_to(ctxSegment, xc1, yc1, xc2, yc2, x1, y1);
    }

    /**
     * Adds a path element for an arc tangent to the lines (current point, x1/y1)
     * and (x1/y1, x2/y2).
     * @param x1 the X coordinate of the corner point.
     * @param y1 the Y coordinate of the corner point.
     * @param x2 the X coordinate of the end of the second line.
     * @param y2 the Y coordinate of the end of the second line.
     * @param radius the radius of the arc.
     */
    public void arcTo(double x1, double y1, double x2, double y2, double radius) {
        if (!closed) lib_h.arc_to(ctxSegment, x1, y1, x2, y2, radius);
    }

    /**
     * Adds path elements for an arc. Angles are in degrees, counter-clockwise.
     * @param centerX the X coordinate of the center of the arc.
     * @param centerY the Y coordinate of the center of the arc.
     * @param radiusX the horizontal radius of the arc.
     * @param radiusY the vertical radius of the arc.
     * @param startAngle the starting angle in degrees.
     * @param length the length of the arc in degrees.
     */
    public void arc(double centerX, double centerY, double radiusX, double radiusY,
            double startAngle, double length) {
        if (!closed) lib_h.path_arc(ctxSegment, centerX, centerY, radiusX, radiusY, startAngle, length);
    }

    /**
     * Adds a closed rectangle sub-path.
     * @param x the X coordinate of the upper left corner.
     * @param y the Y coordinate of the upper left corner.
     * @param w the width.
     * @param h the height.
     */
    public void rect(double x, double y, double w, double h) {
        if (!closed) lib_h.path_rect(ctxSegment, x, y, w, h);
    }

    /** Closes the current sub-path. */
    public void closePath() {
        if (!closed) lib_h.close_path(ctxSegment);
    }

    /**
     * Appends an SVG path string to the current path.
     * A string that cannot be parsed is ignored.
     * @param svgpath the SVG path string or null.
     */
    public void appendSVGPath(String svgpath) {
        if (svgpath != null && !closed) {
            try (var localArena = Arena.ofConfined()) {
                lib_h.append_svg_path(ctxSegment, localArena.allocateFrom(svgpath));
            }
        }
    }

    /** Fills the current path using the current fill paint and fill rule. */
    public void fill() {
        if (!closed) {
            lib_h.fill_path(ctxSegment);
            request();
        }
    }

    /** Strokes the current path using the current stroke paint and line style. */
    public void stroke() {
        if (!closed) {
            lib_h.stroke_path(ctxSegment);
            request();
        }
    }

    /**
     * Intersects the current clip with the current path.
     * The clip is reverted by {@link #restore()}.
     */
    public void clip() {
        if (!closed) {
            lib_h.clip_path(ctxSegment);
            request();
        }
    }

    /**
     * Returns true if the given point is inside the current path
     * (judged with the current fill rule).
     * @param x the X coordinate of the point.
     * @param y the Y coordinate of the point.
     * @return true if the point is inside the path.
     */
    public boolean isPointInPath(double x, double y) {
        return !closed && lib_h.is_point_in_path(ctxSegment, x, y);
    }

    // -- transform -----------------------------------------------------------

    /**
     * Translates the current transform.
     * @param x the distance to translate in the X direction.
     * @param y the distance to translate in the Y direction.
     */
    public void translate(double x, double y) {
        curState.transform.appendTranslation(x, y);
        applyTransform();
    }

    /**
     * Scales the current transform.
     * @param x the factor in the X direction.
     * @param y the factor in the Y direction.
     */
    public void scale(double x, double y) {
        curState.transform.appendScale(x, y);
        applyTransform();
    }

    /**
     * Rotates the current transform around the origin.
     * @param degrees the angle in degrees.
     */
    public void rotate(double degrees) {
        curState.transform.appendRotation(degrees, 0, 0);
        applyTransform();
    }

    /**
     * Concatenates the given transform to the current transform.
     * @param xform the transform or null.
     */
    public void transform(Affine xform) {
        if (xform != null) {
            curState.transform.append(xform);
            applyTransform();
        }
    }

    /**
     * Concatenates the given matrix to the current transform.
     * The parameter order is the same as JavaFX.
     */
    public void transform(double mxx, double myx, double mxy, double myy, double mxt, double myt) {
        curState.transform.append(mxx, mxy, mxt, myx, myy, myt);
        applyTransform();
    }

    /**
     * Sets the current transform.
     * @param xform the transform or null.
     */
    public void setTransform(Affine xform) {
        if (xform != null) {
            curState.transform.setToTransform(xform);
            applyTransform();
        }
    }

    /**
     * Sets the current transform. The parameter order is the same as JavaFX.
     */
    public void setTransform(double mxx, double myx, double mxy, double myy, double mxt, double myt) {
        curState.transform.setToTransform(mxx, mxy, mxt, myx, myy, myt);
        applyTransform();
    }

    /**
     * Gets a copy of the current transform.
     * @param xform the destination to copy into, or null to allocate a new one.
     * @return the copy of the current transform.
     */
    public Affine getTransform(Affine xform) {
        if (xform == null) xform = new Affine();
        xform.setToTransform(curState.transform);
        return xform;
    }

    /**
     * Gets a copy of the current transform.
     * @return the copy of the current transform.
     */
    public Affine getTransform() {
        return getTransform(null);
    }

    private void applyTransform() {
        if (closed) return;
        Affine t = curState.transform;
        lib_h.set_transform(ctxSegment,
            t.getMxx(), t.getMyx(), t.getMxy(), t.getMyy(), t.getTx(), t.getTy());
    }

    // -- state ---------------------------------------------------------------

    /**
     * Sets the global alpha (0.0 - 1.0) applied to everything drawn afterward.
     * Other values are ignored.
     * @param alpha the alpha value.
     */
    public void setGlobalAlpha(double alpha) {
        if (alpha >= 0 && alpha <= 1 && !closed && curState.globalAlpha != alpha) {
            curState.globalAlpha = alpha;
            lib_h.set_global_alpha(ctxSegment, alpha);
        }
    }

    /**
     * Gets the global alpha.
     * @return the global alpha.
     */
    public double getGlobalAlpha() {
        return curState.globalAlpha;
    }

    /**
     * Sets the global blend mode. {@code null} and the RED / GREEN / BLUE
     * modes are ignored.
     * @param op the blend mode.
     */
    public void setGlobalBlendMode(BlendMode op) {
        if (op != null && !closed && curState.blendMode != op) {
            int code = blendCode(op);
            if (code >= 0) {
                curState.blendMode = op;
                lib_h.set_blend_mode(ctxSegment, code);
            }
        }
    }

    /**
     * Gets the global blend mode.
     * @return the global blend mode.
     */
    public BlendMode getGlobalBlendMode() {
        return curState.blendMode;
    }

    /**
     * Sets the fill rule used by {@link #fill()}, {@link #fillPolygon} and {@link #clip()}.
     * @param fillRule NON_ZERO or EVEN_ODD, or null (ignored).
     */
    public void setFillRule(FillRule fillRule) {
        if (fillRule != null && !closed && curState.fillRule != fillRule) {
            curState.fillRule = fillRule;
            lib_h.set_fill_rule(ctxSegment, fillRule == FillRule.EVEN_ODD ? 1 : 0);
        }
    }

    /**
     * Gets the fill rule.
     * @return the fill rule.
     */
    public FillRule getFillRule() {
        return curState.fillRule;
    }

    /**
     * Sets the current fill paint attribute. The default value is BLACK.
     * Colors, {@code LinearGradient} and {@code RadialGradient} (non-proportional)
     * are supported; other paints are ignored.
     * @param p The Paint to be used as the fill Paint or null.
     */
    public void setFill(Paint p) {
        if (p != null && curState.fill != p && !closed && applyPaint(p, false)) {
            curState.fill = p;
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
     * See {@link #setFill(Paint)} for the supported paints.
     * @param p The Paint to be used as the stroke Paint or null.
     */
    public void setStroke(Paint p) {
        if (p != null && curState.stroke != p && !closed && applyPaint(p, true)) {
            curState.stroke = p;
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
            curState.linecap = cap;
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

    /**
     * Sets the current miter limit. The default value is {@code 10.0}.
     * @param ml value in the range {0-positive infinity}, with any other
     *           value being ignored.
     */
    public void setMiterLimit(double ml) {
        if (ml > 0 && ml < Double.POSITIVE_INFINITY && !closed && curState.miterLimit != ml) {
            curState.miterLimit = ml;
            lib_h.set_miter_limit(ctxSegment, ml);
        }
    }

    /**
     * Gets the current miter limit.
     * @return the miter limit.
     */
    public double getMiterLimit() {
        return curState.miterLimit;
    }

    /**
     * Sets the dash pattern of strokes (alternating dash and gap lengths).
     * {@code null} or an empty array turns dashing off. Negative or non-finite
     * values, or a pattern of only zeros, are ignored.
     * @param dashes the dash pattern or null.
     */
    public void setLineDashes(double... dashes) {
        if (closed) return;
        if (dashes == null || dashes.length == 0) {
            curState.lineDashes = null;
            lib_h.set_line_dashes(ctxSegment, MemorySegment.NULL, 0);
            return;
        }
        boolean allZero = true;
        for (double d : dashes) {
            if (!(d >= 0) || Double.isInfinite(d)) return; // negative, NaN or infinite
            if (d > 0) allZero = false;
        }
        if (allZero) return;
        curState.lineDashes = dashes.clone();
        try (var localArena = Arena.ofConfined()) {
            lib_h.set_line_dashes(ctxSegment,
                localArena.allocateFrom(ValueLayout.JAVA_DOUBLE, dashes), dashes.length);
        }
    }

    /**
     * Gets a copy of the current dash pattern.
     * @return the dash pattern or null if dashing is off.
     */
    public double[] getLineDashes() {
        return (curState.lineDashes == null) ? null : curState.lineDashes.clone();
    }

    /**
     * Sets the offset into the dash pattern. The default value is {@code 0.0}.
     * @param dashOffset the offset.
     */
    public void setLineDashOffset(double dashOffset) {
        if (!Double.isNaN(dashOffset) && !Double.isInfinite(dashOffset)
            && !closed && curState.lineDashOffset != dashOffset) {
            curState.lineDashOffset = dashOffset;
            lib_h.set_line_dash_offset(ctxSegment, dashOffset);
        }
    }

    /**
     * Gets the offset into the dash pattern.
     * @return the dash offset.
     */
    public double getLineDashOffset() {
        return curState.lineDashOffset;
    }

    /**
     * Loads a font file and registers it.
     * @param path the path of the font file (TTF, OTF, TTC, OTC).
     * @return the family names of the registered fonts (empty on failure).
     */
    public List<String> loadFont(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocate(1024);
            int faces = lib_h.load_font_file(
                arena.allocateFrom(path.toAbsolutePath().toString()),
                name, name.byteSize());
            if (faces == 0) return List.of();
            // several family names are separated by '\n'
            return List.of(name.getString(0).split("\n"));
        }
    }

    /**
     * Sets the current Font.
     * The default value is specified by {@link Font#getDefault()}.
     * @param f the Font or null.
     */
    public void setFont(Font f) {
        if (f == null) return;
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
     * The default value is {@code 500}.
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
     * Sets the horizontal text alignment. The x given to the text functions
     * becomes the left end, the center or the right end of the text.
     * JUSTIFY behaves like LEFT (the text is a single line).
     * @param align the alignment or null (ignored).
     */
    public void setTextAlign(TextAlignment align) {
        if (align != null && curState.textAlign != align && !closed) {
            curState.textAlign = align;
            lib_h.set_text_align(ctxSegment, switch (align) {
                case LEFT, JUSTIFY -> 0; case CENTER -> 1; case RIGHT -> 2;
            });
        }
    }

    /**
     * Gets the horizontal text alignment.
     * @return the alignment.
     */
    public TextAlignment getTextAlign() {
        return curState.textAlign;
    }

    /**
     * Sets the vertical text position. The y given to the text functions
     * becomes the baseline, the top, the center or the bottom of the line.
     * @param baseline the position or null (ignored).
     */
    public void setTextBaseline(VPos baseline) {
        if (baseline != null && curState.textBaseline != baseline && !closed) {
            curState.textBaseline = baseline;
            lib_h.set_text_baseline(ctxSegment, switch (baseline) {
                case BASELINE -> 0; case TOP -> 1; case CENTER -> 2; case BOTTOM -> 3;
            });
        }
    }

    /**
     * Gets the vertical text position.
     * @return the position.
     */
    public VPos getTextBaseline() {
        return curState.textBaseline;
    }

    /**
     * Saves the following attributes onto a stack: transform, global alpha,
     * blend mode, fill rule, fill and stroke paints, line width, cap, join,
     * miter limit, dashes, font, text alignment / baseline and clip.
     * The current path is not saved.
     */
    public void save() {
        if (closed) return;
        stateStack.push(curState.copy());
        lib_h.save_state(ctxSegment);
    }

    /**
     * Pops the state off of the stack, setting the following attributes to
     * their value at the time when that state was pushed onto the stack.
     * If the stack is empty then nothing is changed.
     */
    public void restore() {
        if (!closed && !stateStack.isEmpty()) {
            curState = stateStack.pop();
            lib_h.restore_state(ctxSegment);
            metrics = null;
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
     * Sends a paint to the native side.
     * @return false if the paint is not supported (nothing was changed)
     */
    private boolean applyPaint(Paint p, boolean stroke) {
        int target = stroke ? 1 : 0;
        switch (p) {
            case Color c -> {
                byte r = b(c.getRed()), g = b(c.getGreen()), bl = b(c.getBlue()), a = b(c.getOpacity());
                if (stroke) lib_h.set_stroke(ctxSegment, r, g, bl, a);
                else lib_h.set_fill(ctxSegment, r, g, bl, a);
                return true;
            }
            case LinearGradient lg when !lg.isProportional() -> {
                List<Stop> stops = lg.getStops();
                try (var localArena = Arena.ofConfined()) {
                    return lib_h.set_linear_gradient(ctxSegment, target,
                        lg.getStartX(), lg.getStartY(), lg.getEndX(), lg.getEndY(),
                        extendCode(lg.getCycleMethod()),
                        localArena.allocateFrom(ValueLayout.JAVA_FLOAT, offsets(stops)),
                        localArena.allocateFrom(ValueLayout.JAVA_BYTE, colors(stops)),
                        stops.size());
                }
            }
            case RadialGradient rg when !rg.isProportional() -> {
                List<Stop> stops = rg.getStops();
                // the first stop is mapped to the focus point
                double angle = Math.toRadians(rg.getFocusAngle());
                double dist = rg.getFocusDistance() * rg.getRadius();
                double fx = rg.getCenterX() + dist * Math.cos(angle);
                double fy = rg.getCenterY() + dist * Math.sin(angle);
                try (var localArena = Arena.ofConfined()) {
                    return lib_h.set_radial_gradient(ctxSegment, target,
                        rg.getCenterX(), rg.getCenterY(), rg.getRadius(), fx, fy,
                        extendCode(rg.getCycleMethod()),
                        localArena.allocateFrom(ValueLayout.JAVA_FLOAT, offsets(stops)),
                        localArena.allocateFrom(ValueLayout.JAVA_BYTE, colors(stops)),
                        stops.size());
                }
            }
            default -> {
                return false; // ImagePattern, proportional gradients
            }
        }
    }

    private static float[] offsets(List<Stop> stops) {
        float[] ret = new float[stops.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = (float) stops.get(i).getOffset();
        }
        return ret;
    }

    private static byte[] colors(List<Stop> stops) {
        byte[] ret = new byte[stops.size() * 4];
        for (int i = 0; i < stops.size(); i++) {
            Color c = stops.get(i).getColor();
            ret[i * 4]     = b(c.getRed());
            ret[i * 4 + 1] = b(c.getGreen());
            ret[i * 4 + 2] = b(c.getBlue());
            ret[i * 4 + 3] = b(c.getOpacity());
        }
        return ret;
    }

    private static int extendCode(CycleMethod m) {
        return switch (m) {
            case NO_CYCLE -> 0; case REFLECT -> 1; case REPEAT -> 2;
        };
    }

    private static int arcTypeCode(ArcType t) {
        return switch (t) {
            case OPEN -> 0; case CHORD -> 1; case ROUND -> 2;
        };
    }

    /** Returns the native blend mode code, or -1 if there is no counterpart. */
    private static int blendCode(BlendMode m) {
        return switch (m) {
            case SRC_OVER -> 0;
            case SRC_ATOP -> 1;
            case ADD -> 2;
            case MULTIPLY -> 3;
            case SCREEN -> 4;
            case OVERLAY -> 5;
            case DARKEN -> 6;
            case LIGHTEN -> 7;
            case COLOR_DODGE -> 8;
            case COLOR_BURN -> 9;
            case HARD_LIGHT -> 10;
            case SOFT_LIGHT -> 11;
            case DIFFERENCE -> 12;
            case EXCLUSION -> 13;
            default -> -1; // RED, GREEN, BLUE
        };
    }

    /**
     * Rounds the width up to the next multiple of 64 pixels (= 256 bytes per row).
     * e.g. 1000 -> 1024, 1024 -> 1024.
     */
    private static int alignWidth(int width) {
        int widthAlignment = 256 / 4;
        return (width + widthAlignment - 1) & -widthAlignment;
    }

    private static byte b(double v) {
        int val = (int) Math.round(v * 255.0);
        return (byte) val;
    }

}
