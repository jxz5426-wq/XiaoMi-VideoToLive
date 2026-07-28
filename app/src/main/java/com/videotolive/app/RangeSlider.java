package com.videotolive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 双滑块范围选择器 — 左右拇指分别代表起止位置，值域 0.0~1.0
 */
public class RangeSlider extends View {

    private float left = 0f, right = 1f;   // 归一化值 0..1
    private final Paint paintTrack = new Paint();
    private final Paint paintActive = new Paint();
    private final Paint paintThumb = new Paint();
    private final Paint paintThumbBorder = new Paint();
    private float thumbR;      // 拇指半径
    private float trackY;      // 轨道 Y 坐标
    private float trackL, trackR; // 轨道左右边界
    private int activeThumb = -1;  // -1=无, 0=左, 1=右
    private OnRangeChangeListener listener;

    public RangeSlider(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp = ctx.getResources().getDisplayMetrics().density;
        thumbR = 10 * dp;
        paintTrack.setColor(0xFF333355);
        paintTrack.setStrokeWidth(4 * dp);
        paintTrack.setStrokeCap(Paint.Cap.ROUND);
        paintActive.setColor(0xFFE94560);
        paintActive.setStrokeWidth(4 * dp);
        paintActive.setStrokeCap(Paint.Cap.ROUND);
        paintThumb.setColor(0xFFE94560);
        paintThumb.setStyle(Paint.Style.FILL);
        paintThumbBorder.setColor(0xFFFFFFFF);
        paintThumbBorder.setStyle(Paint.Style.STROKE);
        paintThumbBorder.setStrokeWidth(2 * dp);
    }

    public void setValues(float l, float r) {
        left = Math.max(0, Math.min(l, right - 0.01f));
        right = Math.min(1, Math.max(r, left + 0.01f));
        invalidate();
        if (listener != null) listener.onRangeChanged(left, right);
    }

    public float getLeftValue() { return left; }
    public float getRightValue() { return right; }

    public void setOnRangeChangeListener(OnRangeChangeListener l) { listener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        trackL = thumbR + 4;
        trackR = w - thumbR - 4;
        trackY = h / 2f;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        // 轨道
        c.drawLine(trackL, trackY, trackR, trackY, paintTrack);
        // 激活范围
        float al = trackL + left * (trackR - trackL);
        float ar = trackL + right * (trackR - trackL);
        c.drawLine(al, trackY, ar, trackY, paintActive);
        // 拇指
        c.drawCircle(al, trackY, thumbR, paintThumb);
        c.drawCircle(al, trackY, thumbR, paintThumbBorder);
        c.drawCircle(ar, trackY, thumbR, paintThumb);
        c.drawCircle(ar, trackY, thumbR, paintThumbBorder);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float al = trackL + left * (trackR - trackL);
        float ar = trackL + right * (trackR - trackL);

        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                float dl = Math.abs(x - al), dr = Math.abs(x - ar);
                if (dl < thumbR * 2 || dr < thumbR * 2) {
                    activeThumb = dl <= dr ? 0 : 1;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (activeThumb < 0) return false;
                float val = (x - trackL) / (trackR - trackL);
                val = Math.max(0, Math.min(1, val));
                if (activeThumb == 0) left = Math.min(val, right - 0.01f);
                else right = Math.max(val, left + 0.01f);
                invalidate();
                if (listener != null) listener.onRangeChanged(left, right);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeThumb = -1;
                if (listener != null) listener.onRangeChanged(left, right);
                return true;
        }
        return super.onTouchEvent(e);
    }

    public interface OnRangeChangeListener {
        void onRangeChanged(float left, float right);
    }
}
