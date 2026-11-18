package com.tvkouame.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Icône de l'application dessinée en code : livre ouvert + croix sur fond dégradé (même dessin que l'icône du lanceur). */
public class LogoView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path livreG = new Path(), livreD = new Path(), croix = new Path();

    public LogoView(Context c) {
        super(c);
        livreG.moveTo(54, 46); livreG.cubicTo(46, 41, 36, 40, 28, 43); livreG.lineTo(28, 72); livreG.cubicTo(36, 69, 46, 70, 54, 75); livreG.close();
        livreD.moveTo(54, 46); livreD.cubicTo(62, 41, 72, 40, 80, 43); livreD.lineTo(80, 72); livreD.cubicTo(72, 69, 62, 70, 54, 75); livreD.close();
        croix.moveTo(51, 22); croix.lineTo(57, 22); croix.lineTo(57, 29); croix.lineTo(64, 29); croix.lineTo(64, 35); croix.lineTo(57, 35);
        croix.lineTo(57, 44); croix.lineTo(51, 44); croix.lineTo(51, 35); croix.lineTo(44, 35); croix.lineTo(44, 29); croix.lineTo(51, 29); croix.close();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float s = Math.min(getWidth(), getHeight()) / 108f;
        cv.save();
        cv.scale(s, s);
        p.setShader(new LinearGradient(0, 0, 108, 108, 0xFF1B1F4B, 0xFF6A3DB8, Shader.TileMode.CLAMP));
        cv.drawRoundRect(new RectF(0, 0, 108, 108), 26, 26, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF); cv.drawPath(livreG, p);
        p.setColor(0xFFE8E1FF); cv.drawPath(livreD, p);
        p.setColor(0xFFFFC857); cv.drawPath(croix, p);
        cv.restore();
    }
}
