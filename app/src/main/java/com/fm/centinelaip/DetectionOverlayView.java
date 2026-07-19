package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DetectionOverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roadTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        lanePaint.setStyle(Paint.Style.STROKE);
        lanePaint.setStrokeWidth(dp(4f));
        lanePaint.setStrokeCap(Paint.Cap.ROUND);
        lanePaint.setStrokeJoin(Paint.Join.ROUND);
        roadFillPaint.setStyle(Paint.Style.FILL);
        roadFillPaint.setColor(Color.argb(36, 34, 211, 238));
        roadTextPaint.setColor(Color.WHITE);
        roadTextPaint.setTextSize(dp(10f));
        roadTextPaint.setFakeBoldText(true);
    }

    void setDetections(List<Detection> values, int width, int height) {
        detections = values == null ? new ArrayList<>() : new ArrayList<>(values);
        sourceWidth = width;
        sourceHeight = height;
        notifyHudSourceSize(width, height);
        invalidate();
    }

    void clear() {
        detections.clear();
        sourceWidth = 0;
        sourceHeight = 0;
        RoadPerceptionStore.clear();
        notifyHudSourceSize(0, 0);
        invalidate();
    }

    int sourceWidth() {
        return sourceWidth;
    }

    int sourceHeight() {
        return sourceHeight;
    }

    private void notifyHudSourceSize(int width, int height) {
        if (!(getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof DriveHudView) {
                ((DriveHudView) child).setSourceSize(width, height);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        float[] viewport = MediaViewport.fitCenter(
                getWidth(), getHeight(), sourceWidth, sourceHeight);
        if (!MediaViewport.valid(viewport)) return;

        float viewportWidth = viewport[2] - viewport[0];
        float scale = viewportWidth / sourceWidth;
        float offsetX = viewport[0];
        float offsetY = viewport[1];
        float viewportRight = viewport[2];
        float viewportBottom = viewport[3];

        canvas.save();
        canvas.clipRect(viewport[0], viewport[1], viewportRight, viewportBottom);
        drawRoadPerception(canvas, scale, offsetX, offsetY);
        drawDetections(canvas, scale, offsetX, offsetY, viewportRight, viewportBottom);
        canvas.restore();
    }

    private void drawRoadPerception(Canvas canvas, float scale, float offsetX, float offsetY) {
        RoadPerception road = RoadPerceptionStore.latest(sourceWidth, sourceHeight);
        if (road == RoadPerception.EMPTY) return;

        if (road.hasCorridor()) {
            Path corridor = new Path();
            corridor.moveTo(mapX(road.left.x[0], scale, offsetX),
                    mapY(road.left.y[0], scale, offsetY));
            for (int index = 1; index < road.left.x.length; index++) {
                corridor.lineTo(mapX(road.left.x[index], scale, offsetX),
                        mapY(road.left.y[index], scale, offsetY));
            }
            for (int index = road.right.x.length - 1; index >= 0; index--) {
                corridor.lineTo(mapX(road.right.x[index], scale, offsetX),
                        mapY(road.right.y[index], scale, offsetY));
            }
            corridor.close();
            roadFillPaint.setAlpha(Math.round(24 + road.corridorConfidence * 42));
            canvas.drawPath(corridor, roadFillPaint);
        }

        drawLane(canvas, road.left, scale, offsetX, offsetY);
        drawLane(canvas, road.right, scale, offsetX, offsetY);

        float best = Math.max(road.left.confidence, road.right.confidence);
        if (best >= 0.25f) {
            String text = String.format(Locale.US, "VÍA %.0f%%", best * 100f);
            canvas.drawText(text, offsetX + dp(8f), offsetY + dp(16f), roadTextPaint);
        }
    }

    private void drawLane(Canvas canvas, RoadPerception.LanePath lane,
                          float scale, float offsetX, float offsetY) {
        if (!lane.isValid()) return;
        int color;
        if (lane.markingColor == RoadPerception.LanePath.COLOR_YELLOW) {
            color = Color.rgb(255, 205, 48);
        } else if (lane.markingColor == RoadPerception.LanePath.COLOR_WHITE) {
            color = Color.WHITE;
        } else {
            color = Color.rgb(34, 211, 238);
        }
        lanePaint.setColor(color);
        lanePaint.setAlpha(Math.round(95 + lane.confidence * 160));
        lanePaint.setShadowLayer(dp(4f), 0f, 0f, color);
        Path path = new Path();
        path.moveTo(mapX(lane.x[0], scale, offsetX), mapY(lane.y[0], scale, offsetY));
        for (int index = 1; index < lane.x.length; index++) {
            path.lineTo(mapX(lane.x[index], scale, offsetX), mapY(lane.y[index], scale, offsetY));
        }
        canvas.drawPath(path, lanePaint);
    }

    private void drawDetections(Canvas canvas, float scale, float offsetX, float offsetY,
                                float viewportRight, float viewportBottom) {
        for (Detection detection : detections) {
            float left = offsetX + detection.left * scale;
            float top = offsetY + detection.top * scale;
            float right = offsetX + detection.right * scale;
            float bottom = offsetY + detection.bottom * scale;
            boxPaint.setColor(detection.color);
            boxPaint.setShadowLayer(dp(6), 0f, 0f, detection.color);
            canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(5), dp(5), boxPaint);

            String label = formatLabel(detection);
            float availableWidth = Math.max(0f, viewportRight - left - dp(8));
            float textWidth = Math.min(textPaint.measureText(label), availableWidth);
            float labelHeight = dp(23);
            float labelTop = Math.max(offsetY, top - labelHeight);
            labelPaint.setColor(detection.color);
            float labelRight = Math.min(viewportRight, left + textWidth + dp(14));
            float labelBottom = Math.min(viewportBottom, labelTop + labelHeight);
            canvas.drawRoundRect(new RectF(left, labelTop, labelRight, labelBottom),
                    dp(5), dp(5), labelPaint);
            canvas.save();
            canvas.clipRect(left, labelTop, labelRight, labelBottom);
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

    private float mapX(float value, float scale, float offset) {
        return offset + value * scale;
    }

    private float mapY(float value, float scale, float offset) {
        return offset + value * scale;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
