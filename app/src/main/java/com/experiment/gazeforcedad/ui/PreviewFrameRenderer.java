package com.experiment.gazeforcedad.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import com.experiment.gazeforcedad.LandmarkProjector;

/**
 * 自绘预览渲染器：把 CameraX 分析帧渲染成预览位图，可选「打马赛克」与「人脸关键点叠加」。
 *
 * <p>两条重要约定：
 * <ul>
 *   <li>渲染用的是分析帧的一份<b>显示拷贝</b>，送进 MediaPipe 的原始像素不受影响，
 *       所以打马/画点只影响预览，不影响检测精度；</li>
 *   <li>关键点来自 MediaPipe（归一化、已按旋转角摆正），与预览位图同一个坐标系，
 *       因此只需按归一化坐标乘显示尺寸，前置摄像头再翻转横轴即可。</li>
 * </ul>
 *
 * <p>三缓冲轮转，避免 UI 线程还在绘制时被下一帧覆盖。
 */
public final class PreviewFrameRenderer {

    private static final int BUFFER_COUNT = 3;
    /** 虹膜关键点在 478 点模型里的起始下标。 */
    private static final int IRIS_START_INDEX = 468;
    private static final int MAX_LANDMARKS = 478;

    private final Paint smoothPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint blockyPaint = new Paint();
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint irisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final RectF preRotateRect = new RectF();
    private final RectF smallPreRotateRect = new RectF();
    private final float pointRadius;
    private final float irisRadius;

    private final Bitmap[] buffers = new Bitmap[BUFFER_COUNT];
    private Bitmap small;
    private int cursor;
    private int outWidth;
    private int outHeight;
    private int smallWidth;
    private int smallHeight;

    public PreviewFrameRenderer(float density) {
        blockyPaint.setFilterBitmap(false);
        blockyPaint.setAntiAlias(false);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(0xFF00E5FF);
        irisPaint.setStyle(Paint.Style.FILL);
        irisPaint.setColor(0xFFFFD54F);
        pointRadius = Math.max(1f, 1.1f * density);
        irisRadius = Math.max(1.6f, 2.0f * density);
    }

    /**
     * @param source               分析帧（RGBA_8888，sourceWidth x sourceHeight）
     * @param rotationDegrees      摆正所需旋转角（ImageProxy.getImageInfo()）
     * @param mirror               前置摄像头预览需要水平镜像
     * @param mosaicBlockPx        马赛克块大小；<= 0 表示不打马（原画质预览）
     * @param normalizedFacePoints 关键点归一化坐标 [x0,y0,...]；null 表示不画点
     * @return 可直接交给 ImageView 的位图（三缓冲轮转，约 250ms 后才会被复用）
     */
    public Bitmap render(Bitmap source, int sourceWidth, int sourceHeight, int rotationDegrees,
                         boolean mirror, int mosaicBlockPx,
                         @Nullable float[] normalizedFacePoints) {
        int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        boolean swapAxes = normalizedRotation == 90 || normalizedRotation == 270;
        int targetWidth = swapAxes ? sourceHeight : sourceWidth;
        int targetHeight = swapAxes ? sourceWidth : sourceHeight;
        boolean mosaic = mosaicBlockPx > 0;
        ensureBuffers(targetWidth, targetHeight, mosaic ? mosaicBlockPx : 0);

        Bitmap target = buffers[cursor];
        cursor = (cursor + 1) % BUFFER_COUNT;

        srcRect.set(0, 0, sourceWidth, sourceHeight);
        float centerX = targetWidth / 2f;
        float centerY = targetHeight / 2f;
        float preWidth = swapAxes ? targetHeight : targetWidth;
        float preHeight = swapAxes ? targetWidth : targetHeight;
        preRotateRect.set(centerX - preWidth / 2f, centerY - preHeight / 2f,
                centerX + preWidth / 2f, centerY + preHeight / 2f);

        Canvas targetCanvas = new Canvas(target);
        if (mosaic) {
            // 先缩到 1/block 的缩略图（信息损失即马赛克），再最近邻放大成硬块
            float smallCenterX = smallWidth / 2f;
            float smallCenterY = smallHeight / 2f;
            float smallPreWidth = swapAxes ? smallHeight : smallWidth;
            float smallPreHeight = swapAxes ? smallWidth : smallHeight;
            smallPreRotateRect.set(smallCenterX - smallPreWidth / 2f, smallCenterY - smallPreHeight / 2f,
                    smallCenterX + smallPreWidth / 2f, smallCenterY + smallPreHeight / 2f);

            Canvas smallCanvas = new Canvas(small);
            if (mirror) {
                // 画布变换后乘：先旋转摆正，再水平镜像（与系统前置预览观感一致）
                smallCanvas.scale(-1f, 1f, smallCenterX, smallCenterY);
            }
            if (normalizedRotation != 0) {
                smallCanvas.rotate(normalizedRotation, smallCenterX, smallCenterY);
            }
            smallCanvas.drawBitmap(source, srcRect, smallPreRotateRect, smoothPaint);

            dstRect.set(0, 0, targetWidth, targetHeight);
            targetCanvas.drawBitmap(small, null, dstRect, blockyPaint);
        } else {
            targetCanvas.save();
            if (mirror) {
                targetCanvas.scale(-1f, 1f, centerX, centerY);
            }
            if (normalizedRotation != 0) {
                targetCanvas.rotate(normalizedRotation, centerX, centerY);
            }
            targetCanvas.drawBitmap(source, srcRect, preRotateRect, smoothPaint);
            targetCanvas.restore();
        }

        if (normalizedFacePoints != null && normalizedFacePoints.length >= 2) {
            drawFacePoints(targetCanvas, normalizedFacePoints, targetWidth, targetHeight, mirror);
        }
        return target;
    }

    /** 关键点画在马赛克之后，保证点本身清晰可见。 */
    private void drawFacePoints(Canvas canvas, float[] normalizedPoints, int width, int height,
                                boolean mirror) {
        float[] display = LandmarkProjector.toDisplay(normalizedPoints, width, height, mirror);
        int pairs = Math.min(display.length / 2, MAX_LANDMARKS);
        for (int i = 0; i < pairs; i++) {
            float x = display[i * 2];
            float y = display[i * 2 + 1];
            if (Float.isNaN(x) || Float.isNaN(y)) {
                continue;
            }
            boolean iris = i >= IRIS_START_INDEX;
            canvas.drawCircle(x, y, iris ? irisRadius : pointRadius, iris ? irisPaint : pointPaint);
        }
    }

    /**
     * Activity 销毁时调用：只丢引用不 recycle——这些位图可能仍挂在 ImageView 上，
     * 显式回收会让 UI 线程绘制到已回收位图而崩溃，交给 GC 更安全。
     */
    public void release() {
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = null;
        }
        small = null;
        outWidth = 0;
        outHeight = 0;
    }

    private void ensureBuffers(int width, int height, int mosaicBlockPx) {
        boolean needSmall = mosaicBlockPx > 0;
        boolean sizeMatches = width == outWidth && height == outHeight && buffers[0] != null;
        if (sizeMatches && (!needSmall || small != null)) {
            return;
        }
        release();
        outWidth = width;
        outHeight = height;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            buffers[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        if (needSmall) {
            smallWidth = Math.max(1, width / mosaicBlockPx);
            smallHeight = Math.max(1, height / mosaicBlockPx);
            small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
        }
    }
}
