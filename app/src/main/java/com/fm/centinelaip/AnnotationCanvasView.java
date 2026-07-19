package com.fm.centinelaip;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Editor de cajas normalizadas con zoom, paneo, movimiento y tiradores de esquina. */
public final class AnnotationCanvasView extends View {
    interface Listener {
        void onBoxCreated(int index);
        void onSelectionChanged(int index);
    }

    private static final int NONE = 0, DRAW = 1, MOVE = 2, TL = 3, TR = 4, BL = 5, BR = 6, PAN = 7;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final Deque<List<AnnotationBox>> history = new ArrayDeque<>();
    private Bitmap bitmap;
    private List<AnnotationBox> boxes = new ArrayList<>();
    private Listener listener;
    private int selected = -1;
    private int gesture = NONE;
    private boolean panMode;
    private float zoom = 1f, panX, panY, lastX, lastY, startNX, startNY;

    public AnnotationCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2.5f));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(12));
        textPaint.setFakeBoldText(true);
        handlePaint.setStyle(Paint.Style.FILL);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(1f, Math.min(8f, zoom * detector.getScaleFactor()));
                clampPan();
                invalidate();
                return true;
            }
        });
    }

    void setListener(Listener listener) { this.listener = listener; }

    void setBitmap(Bitmap value) {
        bitmap = value;
        boxes = new ArrayList<>();
        selected = -1;
        history.clear();
        resetView();
    }

    void setBoxes(List<AnnotationBox> values) {
        boxes = copy(values);
        selected = boxes.isEmpty() ? -1 : 0;
        history.clear();
        invalidate();
        notifySelection();
    }

    List<AnnotationBox> getBoxes() { return copy(boxes); }
    int getSelectedIndex() { return selected; }
    AnnotationBox getSelected() {
        return selected >= 0 && selected < boxes.size() ? boxes.get(selected) : null;
    }

    void setPanMode(boolean value) {
        panMode = value;
        gesture = NONE;
    }

    void resetView() {
        zoom = 1f;
        panX = panY = 0f;
        invalidate();
    }

    void deleteSelected() {
        if (selected < 0 || selected >= boxes.size()) return;
        checkpoint();
        boxes.remove(selected);
        selected = Math.min(selected, boxes.size() - 1);
        invalidate();
        notifySelection();
    }

    void undo() {
        if (history.isEmpty()) return;
        boxes = history.pop();
        selected = Math.min(selected, boxes.size() - 1);
        invalidate();
        notifySelection();
    }

    void updateSelected(String label, float left, float top, float right, float bottom) {
        AnnotationBox current = getSelected();
        if (current == null) return;
        checkpoint();
        AnnotationBox replacement = new AnnotationBox(left, top, right, bottom, label,
                1f, AnnotationBox.MANUAL, AnnotationBox.ACCEPTED);
        boxes.set(selected, replacement);
        invalidate();
        notifySelection();
    }

    void setSelectedState(String state) {
        AnnotationBox current = getSelected();
        if (current == null) return;
        checkpoint();
        current.state = state;
        invalidate();
        notifySelection();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        computeImageRect();
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);
        for (int i = 0; i < boxes.size(); i++) drawBox(canvas, boxes.get(i), i == selected);
    }

    private void drawBox(Canvas canvas, AnnotationBox box, boolean isSelected) {
        RectF rect = toScreen(box);
        int color = AnnotationBox.REJECTED.equals(box.state) ? Color.rgb(255, 85, 116)
                : AnnotationBox.REVIEW.equals(box.state) ? Color.rgb(255, 200, 87)
                : AnnotationBox.AUTO.equals(box.origin) ? Color.rgb(65, 164, 255)
                : Color.rgb(85, 230, 193);
        boxPaint.setColor(color);
        boxPaint.setStrokeWidth(dp(isSelected ? 3.5f : 2.2f));
        canvas.drawRect(rect, boxPaint);

        String label = box.label + (AnnotationBox.AUTO.equals(box.origin)
                ? " " + Math.round(box.confidence * 100) + "%" : "");
        float width = textPaint.measureText(label) + dp(12);
        float top = Math.max(0, rect.top - dp(24));
        fillPaint.setColor(color);
        canvas.drawRect(rect.left, top, rect.left + width, top + dp(24), fillPaint);
        canvas.drawText(label, rect.left + dp(6), top + dp(17), textPaint);

        if (isSelected) {
            handlePaint.setColor(Color.WHITE);
            float radius = dp(6);
            canvas.drawCircle(rect.left, rect.top, radius, handlePaint);
            canvas.drawCircle(rect.right, rect.top, radius, handlePaint);
            canvas.drawCircle(rect.left, rect.bottom, radius, handlePaint);
            canvas.drawCircle(rect.right, rect.bottom, radius, handlePaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return false;
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            gesture = NONE;
            return true;
        }
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x; lastY = y;
                if (panMode) { gesture = PAN; return true; }
                int hit = findBox(x, y);
                if (hit >= 0) {
                    selected = hit;
                    gesture = hitGesture(boxes.get(hit), x, y);
                    checkpoint();
                    notifySelection();
                } else if (imageRect.contains(x, y)) {
                    checkpoint();
                    selected = boxes.size();
                    startNX = toNormalizedX(x);
                    startNY = toNormalizedY(y);
                    boxes.add(new AnnotationBox(startNX, startNY, startNX, startNY,
                            "placa", 1f, AnnotationBox.MANUAL, AnnotationBox.ACCEPTED));
                    gesture = DRAW;
                    notifySelection();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gesture == PAN) {
                    panX += x - lastX; panY += y - lastY;
                    lastX = x; lastY = y; clampPan(); invalidate(); return true;
                }
                AnnotationBox box = getSelected();
                if (box == null) return true;
                float nx = toNormalizedX(x), ny = toNormalizedY(y);
                if (gesture == DRAW) {
                    box.left = Math.min(startNX, nx); box.right = Math.max(startNX, nx);
                    box.top = Math.min(startNY, ny); box.bottom = Math.max(startNY, ny);
                } else if (gesture == MOVE) {
                    float dx = toNormalizedX(x) - toNormalizedX(lastX);
                    float dy = toNormalizedY(y) - toNormalizedY(lastY);
                    float width = box.right - box.left, height = box.bottom - box.top;
                    box.left = clamp(box.left + dx, 0, 1 - width); box.right = box.left + width;
                    box.top = clamp(box.top + dy, 0, 1 - height); box.bottom = box.top + height;
                } else {
                    resize(box, gesture, nx, ny);
                }
                lastX = x; lastY = y; invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                AnnotationBox completed = getSelected();
                boolean created = gesture == DRAW && completed != null;
                if (created && (completed.right - completed.left < .003f
                        || completed.bottom - completed.top < .003f)) {
                    boxes.remove(selected); selected = -1; created = false;
                }
                gesture = NONE;
                invalidate();
                if (created && listener != null) listener.onBoxCreated(selected);
                notifySelection();
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private int findBox(float x, float y) {
        float handle = dp(22);
        for (int i = boxes.size() - 1; i >= 0; i--) {
            RectF rect = toScreen(boxes.get(i));
            RectF expanded = new RectF(rect.left - handle, rect.top - handle,
                    rect.right + handle, rect.bottom + handle);
            if (expanded.contains(x, y)) return i;
        }
        return -1;
    }

    private int hitGesture(AnnotationBox box, float x, float y) {
        RectF rect = toScreen(box);
        float threshold = dp(24);
        if (distance(x, y, rect.left, rect.top) <= threshold) return TL;
        if (distance(x, y, rect.right, rect.top) <= threshold) return TR;
        if (distance(x, y, rect.left, rect.bottom) <= threshold) return BL;
        if (distance(x, y, rect.right, rect.bottom) <= threshold) return BR;
        return MOVE;
    }

    private void resize(AnnotationBox box, int mode, float x, float y) {
        float minimum = .002f;
        if (mode == TL || mode == BL) box.left = Math.min(x, box.right - minimum);
        if (mode == TR || mode == BR) box.right = Math.max(x, box.left + minimum);
        if (mode == TL || mode == TR) box.top = Math.min(y, box.bottom - minimum);
        if (mode == BL || mode == BR) box.bottom = Math.max(y, box.top + minimum);
        box.left = clamp(box.left, 0, 1); box.right = clamp(box.right, 0, 1);
        box.top = clamp(box.top, 0, 1); box.bottom = clamp(box.bottom, 0, 1);
    }

    private RectF toScreen(AnnotationBox box) {
        return new RectF(
                imageRect.left + box.left * imageRect.width(),
                imageRect.top + box.top * imageRect.height(),
                imageRect.left + box.right * imageRect.width(),
                imageRect.top + box.bottom * imageRect.height());
    }

    private float toNormalizedX(float x) {
        computeImageRect();
        return clamp((x - imageRect.left) / imageRect.width(), 0f, 1f);
    }

    private float toNormalizedY(float y) {
        computeImageRect();
        return clamp((y - imageRect.top) / imageRect.height(), 0f, 1f);
    }

    private void computeImageRect() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float base = Math.min((float) getWidth() / bitmap.getWidth(),
                (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * base * zoom;
        float height = bitmap.getHeight() * base * zoom;
        float left = (getWidth() - width) / 2f + panX;
        float top = (getHeight() - height) / 2f + panY;
        imageRect.set(left, top, left + width, top + height);
    }

    private void clampPan() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        computeImageRect();
        float maxX = Math.max(0, (imageRect.width() - getWidth()) / 2f);
        float maxY = Math.max(0, (imageRect.height() - getHeight()) / 2f);
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
    }

    private void checkpoint() {
        history.push(copy(boxes));
        while (history.size() > 30) history.removeLast();
    }

    private void notifySelection() {
        if (listener != null) listener.onSelectionChanged(selected);
    }

    private static List<AnnotationBox> copy(List<AnnotationBox> source) {
        List<AnnotationBox> result = new ArrayList<>();
        for (AnnotationBox box : source) result.add(new AnnotationBox(
                box.left, box.top, box.right, box.bottom, box.label,
                box.confidence, box.origin, box.state));
        return result;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
