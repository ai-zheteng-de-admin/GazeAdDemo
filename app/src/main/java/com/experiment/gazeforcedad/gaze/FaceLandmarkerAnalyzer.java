package com.experiment.gazeforcedad.gaze;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.experiment.gazeforcedad.WatchPolicy;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX 分析帧 -> MediaPipe FaceLandmarker（LIVE_STREAM，本地推理）-> GazeSample/GazeVerdict。
 *
 * <p>性能要点：
 * <ul>
 *   <li>帧率限制在 {@link WatchPolicy#ANALYSIS_TARGET_FPS}，摄像头预览仍是 30fps，省电省算力；</li>
 *   <li>复用同一块 Bitmap 作为推理输入缓冲，避免每帧分配（MediaPipe 在 detectAsync 内同步完成像素拷贝）；</li>
 *   <li>优先 GPU delegate，创建失败自动回退 CPU delegate。</li>
 * </ul>
 */
public final class FaceLandmarkerAnalyzer implements ImageAnalysis.Analyzer {

    /**
     * 预览帧出口：在分析线程被同步调用，参数是一份与检测帧完全相同的位图。
     * 实现方只能同步消费（例如立刻渲染成马赛克预览），不能跨帧持有该 Bitmap——
     * 它的缓冲会被下一帧复用，而送进 MediaPipe 的同一份像素不受影响。
     */
    public interface PreviewSink {
        /**
         * @param normalizedFacePoints 首张人脸的归一化关键点 [x0,y0,...]（可能比当前帧晚一帧，肉眼无感），
         *                             没有人脸时为 null
         */
        void onAnalysisFrame(@NonNull Bitmap frame, int width, int height, int rotationDegrees,
                             @Nullable float[] normalizedFacePoints);
    }

    public interface Listener {
        /** 注意：回调发生在 MediaPipe 的结果线程，更新 UI 需要自行切主线程。 */
        void onGaze(@NonNull GazeSample sample, @NonNull GazeVerdict verdict);

        void onAnalyzerError(@NonNull Throwable error);
    }

    private static final String TAG = "GazeAnalyzer";
    private static final String MODEL_ASSET = "face_landmarker.task";

    private final Context context;
    private final GazeEvaluator evaluator;
    private final Listener listener;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final long frameIntervalMs = Math.max(1L, 1000L / WatchPolicy.ANALYSIS_TARGET_FPS);

    private volatile PreviewSink previewSink;
    /** 最近一次推理得到的人脸关键点（归一化、已按旋转角摆正）。 */
    private volatile float[] latestFacePoints;
    private FaceLandmarker landmarker;
    private Bitmap bitmapBuffer;
    private Bitmap cropBuffer;
    private long lastAnalyzeMs = 0L;
    private long lastTimestampMs = 0L;

    private volatile String delegateName = "CPU";
    private volatile float fps = 0f;
    private long fpsWindowStartMs = 0L;
    private int fpsFrames = 0;

    public FaceLandmarkerAnalyzer(@NonNull Context context, @NonNull GazeEvaluator evaluator,
                                  @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.evaluator = evaluator;
        this.listener = listener;
    }

    /** 需要在后台线程调用：首次创建模型约 0.2~1 秒。 */
    public void init() {
        try {
            landmarker = create(Delegate.GPU);
            delegateName = "GPU";
        } catch (Throwable gpuError) {
            Log.w(TAG, "GPU delegate 不可用，回退 CPU", gpuError);
            landmarker = create(Delegate.CPU);
            delegateName = "CPU";
        }
    }

    private FaceLandmarker create(Delegate delegate) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build();
        FaceLandmarker.FaceLandmarkerOptions options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                // 2 张：1 张用于跟踪观看者，多出来的那张用于“有人替看”校验
                .setNumFaces(2)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener(this::handleResult)
                .setErrorListener(error -> listener.onAnalyzerError(error))
                .build();
        return FaceLandmarker.createFromOptions(context, options);
    }

    private void handleResult(FaceLandmarkerResult result, MPImage input) {
        try {
            latestFacePoints = extractFacePoints(result);
            GazeSample sample = GazeMetricsMapper.map(result);
            GazeVerdict verdict = evaluator.accept(sample);
            updateFps(result.timestampMs());
            listener.onGaze(sample, verdict);
        } catch (Throwable t) {
            listener.onAnalyzerError(t);
        }
    }

    private void updateFps(long nowMs) {
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs;
            return;
        }
        fpsFrames++;
        long elapsed = nowMs - fpsWindowStartMs;
        if (elapsed >= 1000L) {
            fps = fpsFrames * 1000f / elapsed;
            fpsFrames = 0;
            fpsWindowStartMs = nowMs;
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            if (paused.get() || landmarker == null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastAnalyzeMs < frameIntervalMs) {
                return;
            }
            lastAnalyzeMs = now;

            Bitmap bitmap = toBitmap(image);
            if (bitmap == null) {
                return;
            }
            // MediaPipe 要求时间戳严格递增
            long timestampMs = Math.max(now, lastTimestampMs + 1L);
            lastTimestampMs = timestampMs;

            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            ImageProcessingOptions imageOptions = ImageProcessingOptions.builder()
                    .setRotationDegrees(image.getImageInfo().getRotationDegrees())
                    .build();
            landmarker.detectAsync(mpImage, imageOptions, timestampMs);

            PreviewSink sink = previewSink;
            if (sink != null) {
                // 检测已同步取走原始像素，这里再拿同一帧做预览渲染（打马/描点只影响这一份显示）
                sink.onAnalysisFrame(bitmap, image.getWidth(), image.getHeight(),
                        image.getImageInfo().getRotationDegrees(), latestFacePoints);
            }
        } catch (Throwable t) {
            listener.onAnalyzerError(t);
        } finally {
            image.close();
        }
    }

    /** ImageProxy(RGBA_8888) -> 复用 Bitmap。 */
    private Bitmap toBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int paddedWidth = pixelStride > 0 ? rowStride / pixelStride : width;
        if (paddedWidth < width) {
            paddedWidth = width;
        }

        if (bitmapBuffer == null || bitmapBuffer.getWidth() != paddedWidth
                || bitmapBuffer.getHeight() != height) {
            recycle(bitmapBuffer);
            recycle(cropBuffer);
            bitmapBuffer = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            cropBuffer = paddedWidth == width ? null
                    : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }

        buffer.rewind();
        bitmapBuffer.copyPixelsFromBuffer(buffer);

        if (paddedWidth == width) {
            return bitmapBuffer;
        }
        // 有行填充：裁掉右侧多余像素后再送进模型
        new Canvas(cropBuffer).drawBitmap(bitmapBuffer, 0f, 0f, null);
        return cropBuffer;
    }

    /** 设置预览出口；传 null 表示关闭（普通预览模式下由 CameraX Preview 负责显示）。 */
    public void setPreviewSink(@Nullable PreviewSink sink) {
        this.previewSink = sink;
    }

    /** 把首张人脸的 478 个归一化关键点摊平成 [x0,y0,...]，没有人脸返回 null。 */
    @Nullable
    private static float[] extractFacePoints(FaceLandmarkerResult result) {
        List<List<NormalizedLandmark>> faces = result.faceLandmarks();
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        List<NormalizedLandmark> landmarks = faces.get(0);
        if (landmarks == null || landmarks.isEmpty()) {
            return null;
        }
        float[] points = new float[landmarks.size() * 2];
        for (int i = 0; i < landmarks.size(); i++) {
            points[i * 2] = landmarks.get(i).x();
            points[i * 2 + 1] = landmarks.get(i).y();
        }
        return points;
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    public String delegateName() {
        return delegateName;
    }

    public String fpsText() {
        return String.format(Locale.US, "%.1f", fps);
    }

    /** 释放 native 模型与缓冲。 */
    public void close() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
        recycle(bitmapBuffer);
        recycle(cropBuffer);
        bitmapBuffer = null;
        cropBuffer = null;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
