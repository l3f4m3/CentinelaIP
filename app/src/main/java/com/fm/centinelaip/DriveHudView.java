package com.fm.centinelaip;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.camera.view.PreviewView;

/** HUD 2.5D experimental con ajuste táctil y recorte al área real de imagen. */
final class DriveHudView extends View {
    private static final String PREFS = "ada_hud_calibration";
    private static final long CONTENT_PROBE_INTERVAL_MS = 1_000L;
    private static final long DOUBLE_TAP_MS = 360L;

    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences preferences;
    private final RectF contentRect = new RectF();

    private boolean active = true;
    private boolean calibrated;
    private boolean gestureActive;
    private float pitchDegrees;
    private float rollDegrees;
    private long lastContentProbeMs;
    private long lastTapMs;
    private float lastTapX;
    private float lastTapY;
    private String profileKey = "camera_default";
    private HudCalibrationMath.State state = HudCalibrationMath.State.defaults();
    private HudCalibrationMath.State gestureStartState = state;
    private float gestureStartMidX;
    private float gestureStartMidY;
    private float gestureStartDistance;
    private float gestureStartAngle;

    DriveHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClickable(true);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(2f));
        fillPaint.setStyle(Paint.Style.FILL);
        horizonPaint.setStyle(Paint.Style.STROKE);
        horizonPaint.setStrokeWidth(dp(1f));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(dp(11f));
        hintPaint.setFakeBoldText(true);
        updatePalette();
        loadProfile(profileKey);
    }

    void setActive(boolean value) {
        active = value;
        invalidate();
    }

    void setAttitude(float pitchDegrees, float rollDegrees, boolean calibrated) {
        this.calibrated = calibrated;
        // Una orientación absoluta sin calibración del soporte no describe la carretera.
        this.pitchDegrees = calibrated && Float.isFinite(pitchDegrees) ? pitchDegrees : 0f;
        this.rollDegrees = calibrated && Float.isFinite(rollDegrees) ? rollDegrees : 0f;
        updatePalette();
        invalidate();
    }

    private void updatePalette() {
        int accent = calibrated ? Color.rgb(34, 211, 238) : Color.rgb(245, 158, 11);
        guidePaint.setColor(Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)));
        fillPaint.setColor(Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent)));
        horizonPaint.setColor(Color.argb(125, 148, 163, 184));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!active || getWidth() <= 0 || getHeight() <= 0) return;

        maybeProbeContentBounds();
        RectF area = resolvedContentRect();
        float width = area.width();
        float height = area.height();
        if (width <= 1f || height <= 1f) return;

        float pitchOffset = DriveTelemetryMath.clamp(pitchDegrees, -20f, 20f) / 100f;
        float horizonFraction = HudCalibrationMath.clamp(
                state.horizonFraction + pitchOffset, 0.18f, 0.78f);
        float horizon = area.top + height * horizonFraction;
        float center = area.left + width * state.centerFraction;
        float nearHalf = width * state.nearHalfFraction;
        float farHalf = width * state.farHalfFraction;
        float roll = HudCalibrationMath.clamp(
                state.manualRollDegrees - rollDegrees * 0.35f, -18f, 18f);

        canvas.save();
        canvas.clipRect(area);
        canvas.rotate(roll, center, horizon);

        Path road = new Path();
        road.moveTo(center - farHalf, horizon);
        road.lineTo(center + farHalf, horizon);
        road.lineTo(center + nearHalf, area.bottom);
        road.lineTo(center - nearHalf, area.bottom);
        road.close();
        canvas.drawPath(road, fillPaint);

        canvas.drawLine(center - farHalf, horizon, center - nearHalf, area.bottom, guidePaint);
        canvas.drawLine(center + farHalf, horizon, center + nearHalf, area.bottom, guidePaint);
        canvas.drawLine(center, horizon, center, area.bottom, guidePaint);
        canvas.drawLine(area.left + width * 0.25f, horizon,
                area.left + width * 0.75f, horizon, horizonPaint);

        for (int index = 1; index <= 4; index++) {
            float progress = index / 5f;
            float y = horizon + (area.bottom - horizon) * progress * progress;
            float half = farHalf + (nearHalf - farHalf) * progress;
            canvas.drawLine(center - half, y, center + half, y, horizonPaint);
        }

        if (gestureActive) {
            canvas.drawCircle(center, horizon, dp(6f), handlePaint);
            canvas.drawCircle(center - nearHalf, area.bottom - dp(8f), dp(6f), handlePaint);
            canvas.drawCircle(center + nearHalf, area.bottom - dp(8f), dp(6f), handlePaint);
        }
        canvas.restore();

        if (gestureActive) {
            String hint = "HUD: mueve 2 dedos · pellizca · gira · doble toque restaura";
            float textWidth = hintPaint.measureText(hint);
            canvas.drawText(hint, area.centerX() - textWidth / 2f,
                    Math.min(area.bottom - dp(12f), area.top + dp(22f)), hintPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        int action = event.getActionMasked();

        if (event.getPointerCount() >= 2) {
            getParent().requestDisallowInterceptTouchEvent(true);
            if (action == MotionEvent.ACTION_POINTER_DOWN || !gestureActive) {
                beginGesture(event);
            } else if (action == MotionEvent.ACTION_MOVE) {
                updateGesture(event);
            } else if (action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                endGesture();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (gestureActive) {
                endGesture();
                return true;
            }
            long now = SystemClock.uptimeMillis();
            float dx = event.getX() - lastTapX;
            float dy = event.getY() - lastTapY;
            if (now - lastTapMs <= DOUBLE_TAP_MS && dx * dx + dy * dy <= dp(42f) * dp(42f)) {
                state = HudCalibrationMath.State.defaults();
                persistState();
                lastTapMs = 0L;
                invalidate();
                return true;
            }
            lastTapMs = now;
            lastTapX = event.getX();
            lastTapY = event.getY();
        }
        return true;
    }

    private void beginGesture(MotionEvent event) {
        if (event.getPointerCount() < 2) return;
        gestureActive = true;
        gestureStartState = state;
        gestureStartMidX = midpointX(event);
        gestureStartMidY = midpointY(event);
        gestureStartDistance = Math.max(dp(12f), pointerDistance(event));
        gestureStartAngle = pointerAngle(event);
        invalidate();
    }

    private void updateGesture(MotionEvent event) {
        RectF area = resolvedContentRect();
        float width = Math.max(1f, area.width());
        float height = Math.max(1f, area.height());
        float dxFraction = (midpointX(event) - gestureStartMidX) / width;
        float dyFraction = (midpointY(event) - gestureStartMidY) / height;
        float scale = pointerDistance(event) / Math.max(1f, gestureStartDistance);
        float angleDelta = pointerAngle(event) - gestureStartAngle;
        state = HudCalibrationMath.applyGesture(
                gestureStartState, dxFraction, dyFraction, scale, angleDelta);
        invalidate();
    }

    private void endGesture() {
        if (!gestureActive) return;
        gestureActive = false;
        persistState();
        invalidate();
    }

    private float midpointX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) * 0.5f;
    }

    private float midpointY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) * 0.5f;
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

    private void maybeProbeContentBounds() {
        long now = SystemClock.uptimeMillis();
        if (now - lastContentProbeMs < CONTENT_PROBE_INTERVAL_MS) return;
        lastContentProbeMs = now;
        post(this::probeContentBounds);
    }

    private void probeContentBounds() {
        View source = findVisibleSourceView();
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return;
        Bitmap bitmap = null;
        try {
            if (source instanceof PreviewView) {
                bitmap = ((PreviewView) source).getBitmap();
            } else if (source instanceof TextureView) {
                bitmap = ((TextureView) source).getBitmap();
            }
            if (bitmap == null) return;
            Bitmap sample = bitmap;
            if (bitmap.getWidth() > 320 || bitmap.getHeight() > 180) {
                int targetWidth = Math.min(320, bitmap.getWidth());
                int targetHeight = Math.max(1, Math.round(
                        bitmap.getHeight() * (targetWidth / (float) bitmap.getWidth())));
                sample = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false);
            }
            RectF detected = detectNonBlackBounds(sample);
            if (sample != bitmap) sample.recycle();
            if (detected != null) {
                float sx = source.getWidth() / (float) bitmap.getWidth();
                float sy = source.getHeight() / (float) bitmap.getHeight();
                contentRect.set(
                        source.getLeft() + detected.left * bitmap.getWidth() / sampleWidth(bitmap) * sx,
                        source.getTop() + detected.top * bitmap.getHeight() / sampleHeight(bitmap) * sy,
                        source.getLeft() + detected.right * bitmap.getWidth() / sampleWidth(bitmap) * sx,
                        source.getTop() + detected.bottom * bitmap.getHeight() / sampleHeight(bitmap) * sy);
                selectProfile(source, contentRect);
                invalidate();
            }
        } catch (Exception ignored) {
            // La guía conserva el último rectángulo válido.
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private int sampleWidth(Bitmap original) {
        return original.getWidth() > 320 ? 320 : original.getWidth();
    }

    private int sampleHeight(Bitmap original) {
        int width = sampleWidth(original);
        return original.getWidth() > 320
                ? Math.max(1, Math.round(original.getHeight() * (width / (float) original.getWidth())))
                : original.getHeight();
    }

    private View findVisibleSourceView() {
        if (!(getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (child == this || child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof PreviewView || child instanceof TextureView) return child;
        }
        return null;
    }

    private RectF detectNonBlackBounds(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 8 || height < 8) return null;
        int requiredPerColumn = Math.max(2, Math.round(height * 0.06f));
        int requiredPerRow = Math.max(2, Math.round(width * 0.06f));

        int left = 0;
        while (left < width && activePixelsInColumn(bitmap, left) < requiredPerColumn) left++;
        int right = width - 1;
        while (right > left && activePixelsInColumn(bitmap, right) < requiredPerColumn) right--;
        int top = 0;
        while (top < height && activePixelsInRow(bitmap, top) < requiredPerRow) top++;
        int bottom = height - 1;
        while (bottom > top && activePixelsInRow(bitmap, bottom) < requiredPerRow) bottom--;

        if (right - left < width * 0.20f || bottom - top < height * 0.20f) return null;
        return new RectF(left, top, right + 1f, bottom + 1f);
    }

    private int activePixelsInColumn(Bitmap bitmap, int x) {
        int active = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 2) {
            if (!isNearBlack(bitmap.getPixel(x, y))) active += 2;
        }
        return active;
    }

    private int activePixelsInRow(Bitmap bitmap, int y) {
        int active = 0;
        for (int x = 0; x < bitmap.getWidth(); x += 2) {
            if (!isNearBlack(bitmap.getPixel(x, y))) active += 2;
        }
        return active;
    }

    private boolean isNearBlack(int color) {
        return Color.red(color) <= 8 && Color.green(color) <= 8 && Color.blue(color) <= 8;
    }

    private RectF resolvedContentRect() {
        if (contentRect.width() > 1f && contentRect.height() > 1f) return contentRect;
        return new RectF(0f, 0f, getWidth(), getHeight());
    }

    private void selectProfile(View source, RectF area) {
        int aspectBucket = Math.round(area.width() / Math.max(1f, area.height()) * 100f);
        String next = (source instanceof TextureView ? "video_" : "camera_") + aspectBucket;
        if (next.equals(profileKey)) return;
        persistState();
        profileKey = next;
        loadProfile(profileKey);
    }

    private void loadProfile(String key) {
        HudCalibrationMath.State defaults = HudCalibrationMath.State.defaults();
        state = new HudCalibrationMath.State(
                preferences.getFloat(key + "_horizon", defaults.horizonFraction),
                preferences.getFloat(key + "_center", defaults.centerFraction),
                preferences.getFloat(key + "_near", defaults.nearHalfFraction),
                preferences.getFloat(key + "_far", defaults.farHalfFraction),
                preferences.getFloat(key + "_roll", defaults.manualRollDegrees));
    }

    private void persistState() {
        preferences.edit()
                .putFloat(profileKey + "_horizon", state.horizonFraction)
                .putFloat(profileKey + "_center", state.centerFraction)
                .putFloat(profileKey + "_near", state.nearHalfFraction)
                .putFloat(profileKey + "_far", state.farHalfFraction)
                .putFloat(profileKey + "_roll", state.manualRollDegrees)
                .apply();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}