package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DetectionOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Detection> detections = new ArrayList<>();
    private int sourceWidth;
    private int sourceHeight;

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2.5f));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(11f));
        textPaint.setFakeBoldText(true);
        labelPaint.setStyle(Paint.Style.FILL);
    }

    void setDetections(List<Detection> values, int width, int height) {
        detections = values == null ? new ArrayList<>() : new ArrayList<>(values);
        sourceWidth = width;
        sourceHeight = height;
        invalidate();
    }

    void clear() {
        detections.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sourceWidth <= 0 || sourceHeight <= 0 || detections.isEmpty()) return;
        float scale = Math.min((float) getWidth() / sourceWidth, (float) getHeight() / sourceHeight);
        float offsetX = (getWidth() - sourceWidth * scale) / 2f;
        float offsetY = (getHeight() - sourceHeight * scale) / 2f;

        for (Detection detection : detections) {
            float left = offsetX + detection.left * scale;
            float top = offsetY + detection.top * scale;
            float right = offsetX + detection.right * scale;
            float bottom = offsetY + detection.bottom * scale;
            boxPaint.setColor(detection.color);
            boxPaint.setShadowLayer(dp(6), 0f, 0f, detection.color);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(5), dp(5), boxPaint);

            String label = formatLabel(detection);
            float textWidth = Math.min(textPaint.measureText(label), getWidth() - left - dp(8));
            float labelHeight = dp(23);
            float labelTop = Math.max(0f, top - labelHeight);
            labelPaint.setColor(detection.color);
            canvas.drawRoundRect(new RectF(left, labelTop,
                            Math.min(getWidth(), left + textWidth + dp(14)), labelTop + labelHeight),
                    dp(5), dp(5), labelPaint);
            canvas.save();
            canvas.clipRect(left, labelTop, Math.min(getWidth(), left + textWidth + dp(12)),
                    labelTop + labelHeight);
            canvas.drawText(label, left + dp(7), labelTop + dp(15.5f), textPaint);
            canvas.restore();
        }
    }

    private String formatLabel(Detection detection) {
        StringBuilder value = new StringBuilder();
        if (detection.trackId >= 0) value.append('#').append(detection.trackId).append(' ');
        value.append(detection.label).append(' ')
                .append(Math.round(detection.confidence * 100)).append('%');
        if (Float.isFinite(detection.distanceMeters)) {
            value.append(String.format(Locale.US, " · ~%.0f m", detection.distanceMeters));
        }
        if (Float.isFinite(detection.closingSpeedMps) && detection.closingSpeedMps > 0.5f) {
            value.append(String.format(Locale.US, " · +%.0f km/h", detection.closingSpeedMps * 3.6f));
        }
        if (Float.isFinite(detection.ttcSeconds) && detection.ttcSeconds < 30f) {
            value.append(String.format(Locale.US, " · TTC %.1f s", detection.ttcSeconds));
        }
        return value.toString();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
