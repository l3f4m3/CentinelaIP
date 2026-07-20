package com.fm.centinelaip;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.camera.view.PreviewView;

/** HUD visual ajustable. Todavía no representa carriles detectados ni geometría métrica. */
final class DriveHudView extends View {
    private static final String PREFS = "ada_hud_manual";
    private static final String KEY_FILL_SCREEN = "display_fill_screen";
    private static final long DOUBLE_TAP_MS = 360L;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint helpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences preferences;

    private boolean active = true;
    private boolean calibrated;
    private boolean gestureActive;
    private boolean multiTouchSequence;
    private float pitchDegrees;
    private float rollDegrees;
    private int sourceWidth;
    private int sourceHeight;
    private String profileKey = "";

    private float horizonOffset;
    private float centerOffset;
    private float corridorScale = 1f;
    private float manualRoll;

    private float startMidX;
    private float startMidY;
    private float startDistance;
    private float startAngle;
    private float baseHorizonOffset;
    private float baseCenterOffset;
    private float baseCorridorScale;
    private float baseManualRoll;
    private long lastTapMs;
    private float lastTapX;
    private float lastTapY;

    DriveHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        DisplayModeStore.setFillScreen(preferences.getBoolean(KEY_FILL_SCREEN, true));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(2f));
        fillPaint.setStyle(Paint.Style.FILL);
        horizonPaint.setStyle(Paint.Style.STROKE);
        horizonPaint.setStrokeWidth(dp(1f));
        helpPaint.setColor(Color.WHITE);
        helpPaint.setTextSize(dp(11f));
        helpPaint.setFakeBoldText(true);
        helpPaint.setShadowLayer(dp(4f), 0f, 0f, Color.BLACK);
        updatePalette();
    }

    void setActive(boolean value) {
        active = value;
        invalidate();
    }

    void setSourceSize(int width, int height) {
        if (width == sourceWidth && height == sourceHeight) return;
        sourceWidth = Math.max(0, width);
        sourceHeight = Math.max(0, height);
        String nextKey = buildProfileKey(sourceWidth, sourceHeight);
        if (!nextKey.equals(profileKey)) {
            profileKey = nextKey;
            loadProfile();
        }
        applyDisplayModeToSiblings();
        invalidate();
    }

    void setAttitude(float pitchDegrees, float rollDegrees, boolean calibrated) {
        this.pitchDegrees = Float.isFinite(pitchDegrees) ? pitchDegrees : 0f;
        this.rollDegrees = Float.isFinite(rollDegrees) ? rollDegrees : 0f;
        this.calibrated = calibrated;
        updatePalette();
        invalidate();
    }

    private String buildProfileKey(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        int aspectBucket = Math.round(((float) width / height) * 100f);
        return (width >= height ? "landscape_" : "portrait_") + aspectBucket;
    }

    private void loadProfile() {
        if (profileKey.isEmpty()) {
            horizonOffset = 0f;
            centerOffset = 0f;
            corridorScale = 1f;
            manualRoll = 0f;
            return;
        }
        horizonOffset = preferences.getFloat(profileKey + "_horizon", 0f);
        centerOffset = preferences.getFloat(profileKey + "_center", 0f);
        corridorScale = preferences.getFloat(profileKey + "_scale", 1f);
        manualRoll = preferences.getFloat(profileKey + "_roll", 0f);
    }

    private void saveProfile() {
        if (profileKey.isEmpty()) return;
        preferences.edit()
                .putFloat(profileKey + "_horizon", horizonOffset)
                .putFloat(profileKey + "_center", centerOffset)
                .putFloat(profileKey + "_scale", corridorScale)
                .putFloat(profileKey + "_roll", manualRoll)
                .apply();
    }

    private void resetProfile() {
        horizonOffset = 0f;
        centerOffset = 0f;
        corridorScale = 1f;
        manualRoll = 0f;
        saveProfile();
        invalidate();
    }

    private void updatePalette() {
        int accent = calibrated ? Color.rgb(34, 211, 238) : Color.rgb(245, 158, 11);
        guidePaint.setColor(Color.argb(215, Color.red(accent), Color.green(accent), Color.blue(accent)));
        fillPaint.setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)));
        horizonPaint.setColor(Color.argb(125, 148, 163, 184));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || getWidth() <= 0 || getHeight() <= 0
                || sourceWidth <= 0 || sourceHeight <= 0) return;

        float[] viewport = MediaViewport.visible(
                getWidth(), getHeight(), sourceWidth, sourceHeight);
        if (!MediaViewport.valid(viewport)) return;

        float left = viewport[0];
        float top = viewport[1];
        float right = viewport[2];
        float bottom = viewport[3];
        float width = right - left;
        float height = bottom - top;

        float pitchOffset = calibrated
                ? DriveTelemetryMath.clamp(pitchDegrees, -20f, 20f) / 100f : 0f;
        float horizonRatio = DriveTelemetryMath.clamp(
                0.43f + pitchOffset + horizonOffset, 0.16f, 0.82f);
        float horizon = top + height * horizonRatio;
        float center = left + width * (0.5f + centerOffset);
        float nearHalf = width * 0.26f * corridorScale;
        float farHalf = width * 0.055f * corridorScale;
        nearHalf = Math.min(nearHalf, Math.max(1f, Math.min(center - left, right - center)));
        farHalf = Math.min(farHalf, nearHalf * 0.45f);
        float sensorRoll = calibrated
                ? DriveTelemetryMath.clamp(-rollDegrees * 0.35f, -10f, 10f) : 0f;
        float totalRoll = DriveTelemetryMath.clamp(sensorRoll + manualRoll, -18f, 18f);

        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        canvas.rotate(totalRoll, center, horizon);

        Path road = new Path();
        road.moveTo(center - farHalf, horizon);
        road.lineTo(center + farHalf, horizon);
        road.lineTo(center + nearHalf, bottom);
        road.lineTo(center - nearHalf, bottom);
        road.close();
        canvas.drawPath(road, fillPaint);

        canvas.drawLine(center - farHalf, horizon, center - nearHalf, bottom, guidePaint);
        canvas.drawLine(center + farHalf, horizon, center + nearHalf, bottom, guidePaint);
        canvas.drawLine(center, horizon, center, bottom, guidePaint);
        canvas.drawLine(Math.max(left, center - width * 0.18f), horizon,
                Math.min(right, center + width * 0.18f), horizon, horizonPaint);

        for (int index = 1; index <= 4; index++) {
            float progress = index / 5f;
            float y = horizon + (bottom - horizon) * progress * progress;
            float half = farHalf + (nearHalf - farHalf) * progress;
            canvas.drawLine(center - half, y, center + half, y, horizonPaint);
        }
        canvas.restore();

        String mode = DisplayModeStore.isFillScreen() ? "LLENAR" : "AJUSTAR";
        String state = (calibrated ? "HUD CALIBRADO" : "HUD VISUAL · MONTAJE SIN CALIBRAR")
                + " · " + mode;
        canvas.drawText(state, left + dp(10f), top + dp(18f), helpPaint);
        if (gestureActive) {
            canvas.drawText("2 dedos: mover, pellizcar y girar · 3 dedos: restablecer",
                    left + dp(10f), top + dp(36f), helpPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!active || sourceWidth <= 0 || sourceHeight <= 0) return false;
        float[] viewport = MediaViewport.visible(
                getWidth(), getHeight(), sourceWidth, sourceHeight);
        if (!MediaViewport.valid(viewport)) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            return inside(viewport, event.getX(), event.getY());
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchSequence = true;
            if (event.getPointerCount() >= 3) {
                gestureActive = false;
                resetProfile();
                return true;
            }
            if (event.getPointerCount() == 2) {
                float midX = midpointX(event);
                float midY = midpointY(event);
                if (!inside(viewport, midX, midY)) return true;
                gestureActive = true;
                startMidX = midX;
                startMidY = midY;
                startDistance = Math.max(1f, pointerDistance(event));
                startAngle = pointerAngle(event);
                baseHorizonOffset = horizonOffset;
                baseCenterOffset = centerOffset;
                baseCorridorScale = corridorScale;
                baseManualRoll = manualRoll;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && gestureActive && event.getPointerCount() >= 2) {
            float viewportWidth = viewport[2] - viewport[0];
            float viewportHeight = viewport[3] - viewport[1];
            float dx = midpointX(event) - startMidX;
            float dy = midpointY(event) - startMidY;
            float scale = pointerDistance(event) / startDistance;
            float angleDelta = normalizedAngle(pointerAngle(event) - startAngle);

            centerOffset = DriveTelemetryMath.clamp(
                    baseCenterOffset + dx / viewportWidth, -0.38f, 0.38f);
            horizonOffset = DriveTelemetryMath.clamp(
                    baseHorizonOffset + dy / viewportHeight, -0.30f, 0.30f);
            corridorScale = DriveTelemetryMath.clamp(
                    baseCorridorScale * scale, 0.45f, 1.85f);
            manualRoll = DriveTelemetryMath.clamp(
                    baseManualRoll + angleDelta, -18f, 18f);
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (gestureActive) saveProfile();
            gestureActive = false;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (gestureActive) saveProfile();
            gestureActive = false;
            if (multiTouchSequence) {
                multiTouchSequence = false;
                invalidate();
                return true;
            }
            handleTap(event.getX(), event.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            gestureActive = false;
            multiTouchSequence = false;
            invalidate();
            return true;
        }
        return true;
    }

    private void handleTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        float dx = x - lastTapX;
        float dy = y - lastTapY;
        if (now - lastTapMs <= DOUBLE_TAP_MS && dx * dx + dy * dy <= dp(48f) * dp(48f)) {
            boolean fill = DisplayModeStore.toggle();
            preferences.edit().putBoolean(KEY_FILL_SCREEN, fill).apply();
            lastTapMs = 0L;
            applyDisplayModeToSiblings();
            invalidate();
            return;
        }
        lastTapMs = now;
        lastTapX = x;
        lastTapY = y;
    }

    private void applyDisplayModeToSiblings() {
        if (!(getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child instanceof DetectionOverlayView) {
                child.invalidate();
            } else if (child instanceof TextureView && sourceWidth > 0 && sourceHeight > 0) {
                TextureView texture = (TextureView) child;
                if (texture.getWidth() <= 0 || texture.getHeight() <= 0) continue;
                float[] scale = VideoGeometry.fitScale(
                        texture.getWidth(), texture.getHeight(), sourceWidth, sourceHeight);
                Matrix matrix = new Matrix();
                matrix.setScale(scale[0], scale[1],
                        texture.getWidth() / 2f, texture.getHeight() / 2f);
                texture.setTransform(matrix);
            } else if (child instanceof PreviewView) {
                ((PreviewView) child).setScaleType(DisplayModeStore.isFillScreen()
                        ? PreviewView.ScaleType.FILL_CENTER
                        : PreviewView.ScaleType.FIT_CENTER);
            }
        }
    }

    private boolean inside(float[] rect, float x, float y) {
        return x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3];
    }

    private float midpointX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2f;
    }

    private float midpointY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }

    private float pointerDistance(MotionEvent event) {
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    private float pointerAngle(MotionEvent event) {
        return (float) Math.toDegrees(Math.atan2(
                event.getY(1) - event.getY(0), event.getX(1) - event.getX(0)));
    }

    private float normalizedAngle(float value) {
        float result = value % 360f;
        if (result > 180f) result -= 360f;
        if (result < -180f) result += 360f;
        return result;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
