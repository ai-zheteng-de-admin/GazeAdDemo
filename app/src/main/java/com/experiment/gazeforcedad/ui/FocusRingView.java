package com.experiment.gazeforcedad.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.experiment.gazeforcedad.R;

/**
 * 屏幕上的“专注锚点”可视化：
 * <ul>
 *   <li>CALIBRATION：屏幕中央的大环，用户注视它完成锚点校准，环形进度即校准进度；</li>
 *   <li>MONITOR：顶部的小环，正常为绿色，偏离时变红，环上的小缺口表示“回看稳定进度”。</li>
 * </ul>
 */
public final class FocusRingView extends View {

    public enum Mode {
        HIDDEN, CALIBRATION, MONITOR
    }

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final float density;

    private Mode mode = Mode.HIDDEN;
    private float progress = 0f;
    private boolean alert = false;

    public FocusRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3f));
        ringPaint.setColor(color(R.color.focus_ok));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(5f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(color(R.color.focus_warn));

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(color(R.color.focus_ok));
    }

    public void setMode(Mode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            invalidate();
        }
    }

    /** 0~1 的进度（校准进度 / 回看稳定进度）。 */
    public void setProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(clamped - this.progress) > 0.001f) {
            this.progress = clamped;
            invalidate();
        }
    }

    public void setAlert(boolean alert) {
        if (this.alert != alert) {
            this.alert = alert;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mode == Mode.HIDDEN) {
            return;
        }
        boolean calibration = mode == Mode.CALIBRATION;
        float cx = getWidth() / 2f;
        float cy = calibration ? getHeight() * 0.42f : dp(52f);
        float radius = calibration ? dp(74f) : dp(18f);

        int mainColor = alert ? R.color.focus_bad : R.color.focus_ok;

        ringPaint.setColor(color(mainColor));
        ringPaint.setAlpha(calibration ? 120 : 170);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        arcPaint.setColor(color(alert ? R.color.focus_bad : R.color.focus_warn));
        arcPaint.setStrokeWidth(calibration ? dp(5f) : dp(3.5f));
        // 从 -90° 开始顺时针画进度
        canvas.drawArc(arcRect, -90f, Math.max(2f, progress * 360f), false, arcPaint);

        dotPaint.setColor(color(mainColor));
        canvas.drawCircle(cx, cy, calibration ? dp(9f) : dp(5f), dotPaint);

        if (calibration) {
            // 中心十字，提示注视点
            Paint cross = ringPaint;
            cross.setColor(color(R.color.white));
            cross.setAlpha(200);
            cross.setStrokeWidth(dp(2f));
            canvas.drawLine(cx - dp(18f), cy, cx + dp(18f), cy, cross);
            canvas.drawLine(cx, cy - dp(18f), cx, cy + dp(18f), cross);
        }
    }

    private int color(int resId) {
        return ContextCompat.getColor(getContext(), resId);
    }

    private float dp(float value) {
        return value * density;
    }
}
