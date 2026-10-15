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

import javafx.geometry.VPos;
import javafx.scene.effect.BlendMode;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;

/**
 * The ContextState.
 * @author Naotsugu Kobayashi
 */
class ContextState {

    Paint fill;
    Paint stroke;
    double linewidth;
    StrokeLineCap linecap;
    StrokeLineJoin linejoin;
    double miterLimit;
    double[] lineDashes;
    double lineDashOffset;
    String fontFamily;
    double fontSize;
    double fontWeight;
    TextAlignment textAlign;
    VPos textBaseline;
    double globalAlpha;
    FillRule fillRule;
    Affine transform;
    BlendMode blendMode;

    ContextState() {
        init();
    }

    final void init() {
        var defaultFont = Font.getDefault();
        set(Color.BLACK,
            Color.BLACK,
            1.0,
            StrokeLineCap.SQUARE,
            StrokeLineJoin.MITER,
            10.0,
            null,
            0.0,
            defaultFont.getFamily(),
            defaultFont.getSize(),
            700,
            TextAlignment.LEFT,
            VPos.BASELINE,
            1.0,
            FillRule.NON_ZERO,
            new Affine(),
            BlendMode.SRC_OVER);
    }

    ContextState(ContextState copy) {
        set(copy.fill,
            copy.stroke,
            copy.linewidth,
            copy.linecap,
            copy.linejoin,
            copy.miterLimit,
            copy.lineDashes,
            copy.lineDashOffset,
            copy.fontFamily,
            copy.fontSize,
            copy.fontWeight,
            copy.textAlign,
            copy.textBaseline,
            copy.globalAlpha,
            copy.fillRule,
            copy.transform,
            copy.blendMode);
    }

    final void set(
            Paint fill,
            Paint stroke,
            double linewidth,
            StrokeLineCap linecap,
            StrokeLineJoin linejoin,
            double miterLimit,
            double[] lineDashes,
            double lineDashOffset,
            String fontFamily,
            double fontSize,
            double fontWeight,
            TextAlignment textAlign,
            VPos textBaseline,
            double globalAlpha,
            FillRule fillRule,
            Affine transform,
            BlendMode blendMode) {
        this.fill = fill;
        this.stroke = stroke;
        this.linewidth = linewidth;
        this.linecap = linecap;
        this.linejoin = linejoin;
        this.miterLimit = miterLimit;
        this.lineDashes = lineDashes;
        this.lineDashOffset = lineDashOffset;
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.fontWeight = fontWeight;
        this.textAlign = textAlign;
        this.textBaseline = textBaseline;
        this.globalAlpha = globalAlpha;
        this.fillRule = fillRule;
        this.transform = transform;
        this.blendMode = blendMode;
    }

    ContextState copy() {
        return new ContextState(this);
    }

    void restore(GraphicsContext ctx) {
        ctx.setFill(fill);
        ctx.setStroke(stroke);
        ctx.setLineWidth(linewidth);
        ctx.setLineCap(linecap);
        ctx.setLineJoin(linejoin);
        ctx.setMiterLimit(miterLimit);
        ctx.setLineDashes(lineDashes);
        ctx.setLineDashOffset(lineDashOffset);
        ctx.setFontSize(fontSize);
        ctx.setFontWeight(fontWeight);
        ctx.setTextAlign(textAlign);
        ctx.setTextBaseline(textBaseline);
        ctx.setGlobalAlpha(globalAlpha);
        ctx.setFillRule(fillRule);
        ctx.setTransform(transform);
        //ctx.setBlendMode(blendMode);
    }

}
