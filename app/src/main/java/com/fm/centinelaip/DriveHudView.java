package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** HUD 2.5D inicial. No representa geometría métrica ni carriles detectados todavía. */
final class DriveHudView extends View {
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active = true;

    DriveHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(2f));
        guidePaint.setColor(Color.argb(205, 34, 211, 238));
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(28, 34, 211, 238));
        horizonPaint.setStyle(Paint.Style.STROKE);
        horizonPaint.setStrokeWidth(dp(1f));
        horizonPaint.setColor(Color.argb(105, 148, 163, 184));
    }

    void setActive(boolean value) {
        active = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || getWidth() <= 0 || getHeight() <= 0) return;

        float width = getWidth();
        float height = getHeight();
        float horizon = height * 0.43f;
        float center = width * 0.5f;
        float nearHalf = width * 0.26f;
        float farHalf = width * 0.055f;

        Path road = new Path();
        road.moveTo(center - farHalf, horizon);
        road.lineTo(center + farHalf, horizon);
        road.lineTo(center + nearHalf, height);
        road.lineTo(center - nearHalf, height);
        road.close();
        canvas.drawPath(road, fillPaint);

        canvas.drawLine(center - farHalf, horizon, center - nearHalf, height, guidePaint);
        canvas.drawLine(center + farHalf, horizon, center + nearHalf, height, guidePaint);
        canvas.drawLine(center, horizon, center, height, guidePaint);
        canvas.drawLine(width * 0.32f, horizon, width * 0.68f, horizon, horizonPaint);

        for (int index = 1; index <= 4; index++) {
            float progress = index / 5f;
            float y = horizon + (height - horizon) * progress * progress;
            float half = farHalf + (nearHalf - farHalf) * progress;
            canvas.drawLine(center - half, y, center + half, y, horizonPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
