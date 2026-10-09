package com.experiment.gazeforcedad;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.experiment.gazeforcedad.gaze.FaceLandmarkerAnalyzer;
import com.experiment.gazeforcedad.gaze.GazeEvaluator;
import com.experiment.gazeforcedad.gaze.GazeSample;
import com.experiment.gazeforcedad.gaze.GazeVerdict;
import com.experiment.gazeforcedad.ui.FocusRingView;
import com.experiment.gazeforcedad.ui.PreviewFrameRenderer;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 广告播放页：视频播放 + 前置摄像头本地视线检测 + 强制重看闭环。
 *
 * <p>状态流转：
 * <pre>
 *   校准中(广告暂停) --锚点锁定--> 播放中 --偏离超过容忍--> 中断并显示“请重新看广告”
 *        ^                                                       |
 *        |                                          视线回到屏幕并稳定 1.2s
 *        +------------------- 从 0 秒重新播放 &lt;----------------+
 * </pre>
 *
 * <p>视频来源：主页面设置的自定义视频（SAF URI），未设置则用内置示例广告；
 * 广告时长完全由视频本身决定（播完才算通过）。
 *
 * <p>预览有三种形态，都不影响送模型检测的原始帧：
 * <ol>
 *   <li>不显示预览（设置里关闭）；</li>
 *   <li>普通预览（CameraX Preview，30fps 实时画面）；</li>
 *   <li>打马预览（用分析帧自己渲染，块状马赛克，仅这一层被处理）。</li>
 * </ol>
 */
public final class AdActivity extends AppCompatActivity implements FaceLandmarkerAnalyzer.Listener {

    private static final String TAG = "GazeAdDemo";
    private static final long DOUBLE_BACK_EXIT_MS = 2_000L;
    private static final int ANALYZER_ERROR_TOLERANCE = 20;
    private static final float MOSAIC_BLOCK_DP = 14f;
    /** 内置广告：assets 里保留原始文件名，直接覆盖文件即可替换默认广告。 */
    private static final String BUNDLED_AD_ASSET = "default_ad.mp4";
    private static final String BUNDLED_AD_CACHE_FILE = "bundled_default_ad.mp4";
    private static final long COUNTDOWN_TICK_MS = 500L;
    /** 最后几秒倒计时高亮成警示色。 */
    private static final int COUNTDOWN_HIGHLIGHT_SECONDS = 5;
    /** 播放/重播状态提示在屏幕底部显示多久后自动隐藏。 */
    private static final long TRANSIENT_STATUS_VISIBLE_MS = 2_500L;

    private VideoView videoView;
    private View previewContainer;
    private PreviewView previewView;
    private ImageView debugPreview;
    private TextView previewBadge;
    private FocusRingView focusRing;
    private View hudPanel;
    private TextView hudTitle;
    private TextView hudAnchor;
    private TextView hudMetrics;
    private TextView statusText;
    private TextView calibrationText;
    private TextView violationReason;
    private TextView violationCountdown;
    private TextView completionStats;
    private TextView adCountdown;
    private TextView errorText;
    private View violationOverlay;
    private View completionOverlay;
    private View errorOverlay;
    private Button backButton;
    private Button errorExitButton;

    private final GazeEvaluator evaluator = new GazeEvaluator();
    private final AtomicInteger analyzerErrors = new AtomicInteger();

    private DemoSettings settings;
    private FaceLandmarkerAnalyzer analyzer;
    private ExecutorService analyzerExecutor;
    private ProcessCameraProvider cameraProvider;
    private PreviewFrameRenderer previewRenderer;

    private boolean previewEnabled;
    private boolean mosaicPreviewEnabled;
    private boolean showFacePoints;
    /** 需要打马或画关键点时，预览改为“用分析帧自绘”，不再使用 CameraX Preview 用例。 */
    private boolean selfRenderedPreview;
    private int mosaicBlockPx;
    private volatile boolean mirrorPreview = true;

    private MediaPlayer mediaPlayer;
    private int videoWidth;
    private int videoHeight;
    private int appliedScalingMode = -1;

    private boolean videoPrepared;
    private boolean adStarted;
    private boolean adInterrupted;
    private boolean adCompleted;
    private boolean usingCustomVideo;
    private int violationCount;
    private long lastBackPressMs;
    private long adDurationMs;

    private GazeSample lastSample;
    private GazeVerdict lastVerdict;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusHeld;

    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private boolean countdownRunning;
    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            updateCountdown();
            if (countdownRunning) {
                countdownHandler.postDelayed(this, COUNTDOWN_TICK_MS);
            }
        }
    };

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTransientStatus = () -> {
        if (statusText != null) {
            statusText.setVisibility(View.GONE);
        }
    };

    // ===================== 生命周期 =====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ad);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        settings = new DemoSettings(this);
        enterImmersiveMode();
        bindViews();
        setupPreviewMode();
        setupVideo();
        startVisionPipeline();
    }

    private void bindViews() {
        videoView = findViewById(R.id.videoView);
        previewContainer = findViewById(R.id.previewContainer);
        previewView = findViewById(R.id.previewView);
        debugPreview = findViewById(R.id.debugPreview);
        previewBadge = findViewById(R.id.previewBadge);
        focusRing = findViewById(R.id.focusRing);
        hudPanel = findViewById(R.id.hudPanel);
        hudTitle = findViewById(R.id.hudTitle);
        hudAnchor = findViewById(R.id.hudAnchor);
        hudMetrics = findViewById(R.id.hudMetrics);
        statusText = findViewById(R.id.statusText);
        calibrationText = findViewById(R.id.calibrationText);
        violationReason = findViewById(R.id.violationReason);
        violationCountdown = findViewById(R.id.violationCountdown);
        completionStats = findViewById(R.id.completionStats);
        adCountdown = findViewById(R.id.adCountdown);
        adCountdown.setVisibility(View.GONE);
        errorText = findViewById(R.id.errorText);
        violationOverlay = findViewById(R.id.violationOverlay);
        completionOverlay = findViewById(R.id.completionOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        backButton = findViewById(R.id.backButton);
        errorExitButton = findViewById(R.id.errorExitButton);

        // 与 VideoView(SurfaceView) 同屏时用 TextureView 模式，避免图层遮挡
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewContainer.setOnClickListener(v -> {
            boolean show = previewContainer.getVisibility() != View.VISIBLE;
            previewContainer.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        });
        focusRing.setMode(FocusRingView.Mode.HIDDEN);
        // 调试信息面板开关（关闭后广告页不再显示 fps/头偏/虹膜/违规等参数）
        hudPanel.setVisibility(settings.showDebugHud() ? View.VISIBLE : View.GONE);
        hudAnchor.setText("锚点：未建立");
        showPersistentStatus("正在初始化本地人脸模型…");

        backButton.setOnClickListener(v -> finish());
        errorExitButton.setOnClickListener(v -> finish());
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        setupAudioFocus();
        setupDraggablePreview();
    }

    /**
     * 按设置决定预览形态：
     * <ul>
     *   <li>关闭预览：不创建任何预览用例；</li>
     *   <li>普通预览：CameraX Preview，原生 30fps；</li>
     *   <li>打马 / 画人脸关键点：用分析帧自绘（约 12fps），两者可任意组合。</li>
     * </ul>
     */
    private void setupPreviewMode() {
        float density = getResources().getDisplayMetrics().density;
        previewEnabled = settings.showPreview();
        mosaicPreviewEnabled = previewEnabled && settings.mosaicPreview();
        showFacePoints = previewEnabled && settings.showFacePoints();
        selfRenderedPreview = previewEnabled && (mosaicPreviewEnabled || showFacePoints);
        mosaicBlockPx = mosaicPreviewEnabled ? (int) (MOSAIC_BLOCK_DP * density) : 0;

        previewContainer.setVisibility(previewEnabled ? View.VISIBLE : View.GONE);
        previewView.setVisibility(selfRenderedPreview ? View.GONE : View.VISIBLE);
        debugPreview.setVisibility(selfRenderedPreview ? View.VISIBLE : View.GONE);
        previewBadge.setVisibility(selfRenderedPreview ? View.VISIBLE : View.GONE);

        if (selfRenderedPreview) {
            previewBadge.setText(mosaicPreviewEnabled && showFacePoints
                    ? R.string.preview_badge_mosaic_points
                    : mosaicPreviewEnabled ? R.string.preview_badge_mosaic
                    : R.string.preview_badge_points);
            previewRenderer = new PreviewFrameRenderer(density);
        }
    }

    /** 广告播放期间拦截返回：手势返回/实体返回键都走这里（predictive back 兼容）。 */
    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            long now = SystemClock.uptimeMillis();
            boolean canExit = adCompleted || now - lastBackPressMs <= DOUBLE_BACK_EXIT_MS;
            if (canExit) {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                return;
            }
            lastBackPressMs = now;
            Toast.makeText(AdActivity.this,
                    "广告播放中，看完才能退出（2 秒内再按一次返回可强制退出 Demo）",
                    Toast.LENGTH_SHORT).show();
        }
    };

    /** 广告页全屏：状态栏与导航栏都隐藏，需要时从边缘上滑临时呼出。 */
    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        previewContainer.post(this::restorePreviewPosition);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 观看途中切后台 = 放弃观看：直接判违规，要求重新看
        if (adStarted && !adCompleted && !adInterrupted) {
            videoView.pause();
            GazeVerdict verdict = evaluator.forceViolation(GazeVerdict.Reason.INTERRUPTED,
                    "观看中途离开了应用，请重新观看广告", SystemClock.uptimeMillis());
            applyGaze(lastSample, verdict);
        }
    }

    @Override
    protected void onDestroy() {
        if (analyzer != null) {
            if (analyzerExecutor != null && !analyzerExecutor.isShutdown()) {
                // 排到分析线程队列尾部：确保没有在途渲染/推理时再释放
                analyzerExecutor.execute(() -> {
                    analyzer.close();
                    if (previewRenderer != null) {
                        previewRenderer.release();
                    }
                });
            } else {
                analyzer.close();
                if (previewRenderer != null) {
                    previewRenderer.release();
                }
            }
        }
        if (analyzerExecutor != null) {
            analyzerExecutor.shutdown();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        abandonAdAudioFocus();
        stopCountdown();
        statusHandler.removeCallbacks(hideTransientStatus);
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }

    // ===================== 视频 =====================

    private void setupVideo() {
        usingCustomVideo = settings.hasCustomVideo();
        if (usingCustomVideo) {
            attachVideo(settings.videoUri());
        } else {
            prepareBundledAdAsync();
        }
    }

    /**
     * 内置广告放在 assets/{@link #BUNDLED_AD_ASSET}（保留原始文件名，方便直接覆盖替换），
     * 首次运行时释放到 cache 再交给播放器；之后按长度校验复用，不重复拷贝。
     */
    private void prepareBundledAdAsync() {
        showPersistentStatus("正在准备内置广告…");
        new Thread(() -> {
            try {
                File file = ensureBundledAdFile();
                runOnUiThread(() -> attachVideo(Uri.fromFile(file)));
            } catch (Throwable t) {
                Log.e(TAG, "extract bundled ad failed", t);
                runOnUiThread(() -> showError("内置广告释放失败：" + t));
            }
        }, "bundled-ad").start();
    }

    private File ensureBundledAdFile() throws IOException {
        File target = new File(getCacheDir(), BUNDLED_AD_CACHE_FILE);
        long assetLength = -1L;
        try (AssetFileDescriptor descriptor = getAssets().openFd(BUNDLED_AD_ASSET)) {
            assetLength = descriptor.getLength();
        } catch (IOException e) {
            Log.w(TAG, "openFd on bundled ad failed, copy unconditionally", e);
        }
        if (target.exists() && target.length() > 0
                && (assetLength <= 0 || target.length() == assetLength)) {
            return target;
        }
        File temp = new File(getCacheDir(), BUNDLED_AD_CACHE_FILE + ".tmp");
        try (InputStream in = getAssets().open(BUNDLED_AD_ASSET);
             OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        if (!temp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                throw new IOException("无法替换旧的缓存广告文件");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("无法写入内置广告缓存");
            }
        }
        return target;
    }

    private void attachVideo(Uri source) {
        videoView.setVideoURI(source);

        videoView.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            videoWidth = mp.getVideoWidth();
            videoHeight = mp.getVideoHeight();
            // 方向跟随视频 + 不拉伸（横屏视频横屏播，竖屏视频竖屏播）
            applyVideoGeometry();
            // 时长完全跟随视频本身：播完 onCompletion 才算通过
            adDurationMs = mp.getDuration();
            mp.setVolume(1f, 1f); // 显式开满媒体音量（不改系统音量，只保证本视频不被静音）
            videoPrepared = true;
            videoView.seekTo(1); // 先渲染首帧，避免黑屏
            startCountdown();    // 校准阶段先显示“广告共 X 秒”，播放中变成剩余秒数
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            if (usingCustomVideo) {
                // 自定义视频失效（被删除/被回收权限）：清理设置并回退内置广告
                Log.w(TAG, "custom video failed, fallback to builtin. what=" + what + " extra=" + extra);
                settings.clearCustomVideo();
                usingCustomVideo = false;
                videoPrepared = false;
                Toast.makeText(this, R.string.custom_video_failed, Toast.LENGTH_LONG).show();
                setupVideo();
                return true;
            }
            showError("广告视频播放失败 what=" + what + " extra=" + extra);
            return true;
        });
        videoView.setOnCompletionListener(mp -> onAdWatchedToEnd());
    }

    // ===================== 广告声音（视频原声） =====================

    /**
     * 广告音轨由 VideoView/MediaPlayer 原声播放（内置与自定义视频一致，代码不做任何静音）。
     * 这里额外申请音频焦点：广告播放时压住后台音乐，避免“画面在放、声音是别人的歌”。
     * 注意：不会去改系统音量，手机静音/媒体音量为 0 时仍然无声，这是系统行为。
     */
    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> Log.i(TAG, "audio focus change=" + change))
                .build();
    }

    private void requestAdAudioFocus() {
        if (audioManager == null || audioFocusRequest == null || audioFocusHeld) {
            return;
        }
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAdAudioFocus() {
        if (audioManager == null || audioFocusRequest == null || !audioFocusHeld) {
            return;
        }
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        audioFocusHeld = false;
    }

    // ===================== 预览小窗可拖动 =====================

    /**
     * 预览小窗支持拖动改变位置：移动超过 touchSlop 才算拖动，否则算轻点（沿用“点一下显示/隐藏”）。
     * 拖动范围限制在广告页内，松手后按可用范围归一化记住位置，转屏/下次进入都能还原。
     */
    private void setupDraggablePreview() {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        previewContainer.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startTranslationX;
            private float startTranslationY;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startTranslationX = view.getTranslationX();
                        startTranslationY = view.getTranslationY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging) {
                            view.setTranslationX(clampPreviewX(startTranslationX + dx));
                            view.setTranslationY(clampPreviewY(startTranslationY + dy));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            persistPreviewPosition();
                        } else {
                            view.performClick(); // 触发原有的轻点显示/隐藏
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            persistPreviewPosition();
                        }
                        dragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private float clampPreviewX(float translationX) {
        View parent = (View) previewContainer.getParent();
        if (parent == null) {
            return translationX;
        }
        float baseLeft = previewContainer.getLeft();
        float min = -baseLeft;
        float max = Math.max(min, parent.getWidth() - previewContainer.getWidth() - baseLeft);
        return Math.max(min, Math.min(max, translationX));
    }

    private float clampPreviewY(float translationY) {
        View parent = (View) previewContainer.getParent();
        if (parent == null) {
            return translationY;
        }
        float baseTop = previewContainer.getTop();
        float min = -baseTop;
        float max = Math.max(min, parent.getHeight() - previewContainer.getHeight() - baseTop);
        return Math.max(min, Math.min(max, translationY));
    }

    /** 归一化成 0~1 存储：left/top 占可用范围的比例。 */
    private void persistPreviewPosition() {
        View parent = (View) previewContainer.getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        float spanX = Math.max(1f, parent.getWidth() - previewContainer.getWidth());
        float spanY = Math.max(1f, parent.getHeight() - previewContainer.getHeight());
        float left = previewContainer.getLeft() + previewContainer.getTranslationX();
        float top = previewContainer.getTop() + previewContainer.getTranslationY();
        settings.setPreviewPosition(left / spanX, top / spanY);
    }

    /** 按存储的比例还原位置；父容器还没测量完成时直接跳过。 */
    private void restorePreviewPosition() {
        if (!settings.hasPreviewPosition() || previewContainer == null) {
            return;
        }
        View parent = (View) previewContainer.getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        float spanX = Math.max(1f, parent.getWidth() - previewContainer.getWidth());
        float spanY = Math.max(1f, parent.getHeight() - previewContainer.getHeight());
        float left = settings.previewPositionX() * spanX;
        float top = settings.previewPositionY() * spanY;
        previewContainer.setTranslationX(left - previewContainer.getLeft());
        previewContainer.setTranslationY(top - previewContainer.getTop());
    }

    // ===================== 广告倒计时（爱奇艺/优酷那种角标） =====================

    private void startCountdown() {
        if (countdownRunning || adCountdown == null) {
            return;
        }
        countdownRunning = true;
        countdownTick.run();
    }

    private void stopCountdown() {
        countdownRunning = false;
        countdownHandler.removeCallbacks(countdownTick);
    }

    /** 用播放器真实进度算剩余时间：暂停/中断时倒计时自然停住，默认与自定义视频一视同仁。 */
    private void updateCountdown() {
        if (adCountdown == null) {
            return;
        }
        if (adDurationMs <= 0) {
            adCountdown.setVisibility(View.GONE);
            return;
        }
        adCountdown.setVisibility(View.VISIBLE);
        if (!adStarted) {
            // 校准阶段：广告还没开始，先告诉用户总时长
            adCountdown.setText(getString(R.string.ad_countdown_total,
                    VideoDisplayPolicy.remainingSeconds(0, adDurationMs)));
            adCountdown.setTextColor(ContextCompat.getColor(this, R.color.white));
            return;
        }
        int remaining = VideoDisplayPolicy.remainingSeconds(
                videoView.getCurrentPosition(), adDurationMs);
        adCountdown.setText(getString(R.string.ad_countdown_remaining, remaining));
        adCountdown.setTextColor(ContextCompat.getColor(this,
                remaining <= COUNTDOWN_HIGHLIGHT_SECONDS ? R.color.focus_warn : R.color.white));
    }

    /**
     * 屏幕方向跟随视频比例；缩放在每次布局变化后重算。
     * 注意：AdActivity 已声明 configChanges(orientation|screenSize|...)，旋转不会重建 Activity，
     * 因此广告进度、违规计数、校准锚点都不会因为转屏丢失。
     */
    private void applyVideoGeometry() {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        boolean landscape = VideoDisplayPolicy.isLandscape(videoWidth, videoHeight);
        int requested = landscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        if (getRequestedOrientation() != requested) {
            setRequestedOrientation(requested);
        }
        updateVideoScalingMode();
    }

    /**
     * 只有视频比例与容器比例几乎一致时才铺满（裁切可忽略），否则完整显示留黑边——
     * 既不平铺拉伸，也不裁掉画面内容。
     */
    private void updateVideoScalingMode() {
        if (mediaPlayer == null || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int containerWidth = videoView.getWidth();
        int containerHeight = videoView.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            containerWidth = getResources().getDisplayMetrics().widthPixels;
            containerHeight = getResources().getDisplayMetrics().heightPixels;
        }
        boolean fill = VideoDisplayPolicy.canFillScreen(videoWidth, videoHeight,
                containerWidth, containerHeight);
        int mode = fill
                ? MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                : MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT;
        if (mode == appliedScalingMode) {
            return;
        }
        try {
            mediaPlayer.setVideoScalingMode(mode);
            appliedScalingMode = mode;
        } catch (IllegalStateException e) {
            Log.w(TAG, "setVideoScalingMode failed", e);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后容器尺寸变了，重新决定铺满还是留边
        videoView.post(this::updateVideoScalingMode);
        // 预览小窗按归一化位置重新还原（横竖屏都能落在可拖动范围内）
        previewContainer.post(this::restorePreviewPosition);
    }

    private void startOrRestartAd() {
        if (!videoPrepared) {
            showPersistentStatus("视频加载中…");
            return;
        }
        boolean restart = adInterrupted;
        adStarted = true;
        adInterrupted = false;
        requestAdAudioFocus();
        violationOverlay.setVisibility(View.GONE);
        videoView.seekTo(0);
        videoView.start();
        showTransientStatus(restart
                ? "视线已回来，广告从头重新播放"
                : "广告播放中 · 请注视屏幕");
        if (restart) {
            Toast.makeText(this, R.string.ad_restarted, Toast.LENGTH_SHORT).show();
        }
    }

    private void onAdWatchedToEnd() {
        adCompleted = true;
        abandonAdAudioFocus();
        stopCountdown();
        if (adCountdown != null) {
            adCountdown.setText(R.string.ad_countdown_done);
            adCountdown.setTextColor(ContextCompat.getColor(this, R.color.focus_ok));
        }
        if (analyzer != null) {
            analyzer.setPaused(true);
        }
        focusRing.setMode(FocusRingView.Mode.HIDDEN);
        calibrationText.setVisibility(View.GONE);
        violationOverlay.setVisibility(View.GONE);
        completionStats.setText(String.format(Locale.US,
                "广告时长 %.1f 秒 · 违规中断 %d 次 · 眨眼 %d 次 · 推理设备 %s\n广告在连续注视下播放完毕",
                adDurationMs / 1000f, violationCount, evaluator.blinkCount(),
                analyzer == null ? "-" : analyzer.delegateName()));
        completionOverlay.setVisibility(View.VISIBLE);
        showPersistentStatus("广告已完整播放");
    }

    /** 短暂状态提示：出现 2.5 秒后自动隐藏，避免长期遮挡广告画面。 */
    private void showTransientStatus(CharSequence text) {
        statusHandler.removeCallbacks(hideTransientStatus);
        showStatus(text);
        statusHandler.postDelayed(hideTransientStatus, TRANSIENT_STATUS_VISIBLE_MS);
    }

    /** 持续状态提示：保持显示，直到下一次状态切换。 */
    private void showPersistentStatus(CharSequence text) {
        statusHandler.removeCallbacks(hideTransientStatus);
        showStatus(text);
    }

    private void showStatus(CharSequence text) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(text);
    }

    // ===================== 视觉流水线 =====================

    private void startVisionPipeline() {
        analyzerExecutor = Executors.newSingleThreadExecutor();
        analyzer = new FaceLandmarkerAnalyzer(this, evaluator, this);
        if (previewRenderer != null) {
            // 只在分析线程用“同一帧”渲染预览（可选打马 + 关键点）；送模型的仍是原始像素
            analyzer.setPreviewSink((frame, width, height, rotationDegrees, facePoints) -> {
                Bitmap rendered = previewRenderer.render(frame, width, height, rotationDegrees,
                        mirrorPreview, mosaicBlockPx, showFacePoints ? facePoints : null);
                debugPreview.post(() -> debugPreview.setImageBitmap(rendered));
            });
        }
        analyzerExecutor.execute(() -> {
            try {
                analyzer.init();
                runOnUiThread(() -> {
                    showPersistentStatus(String.format(Locale.US,
                            "本地模型就绪（%s），正在启动摄像头…", analyzer.delegateName()));
                    bindCamera();
                });
            } catch (Throwable t) {
                Log.e(TAG, "init analyzer failed", t);
                runOnUiThread(() -> showError("MediaPipe 模型加载失败：" + t));
            }
        });
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                startCamera();
            } catch (Throwable t) {
                Log.e(TAG, "camera provider failed", t);
                showError("相机初始化失败：" + t);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startCamera() {
        List<UseCase> useCases = new ArrayList<>();
        if (previewEnabled && !selfRenderedPreview) {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            useCases.add(preview);
        }

        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        imageAnalysis.setAnalyzer(analyzerExecutor, analyzer);
        useCases.add(imageAnalysis);

        cameraProvider.unbindAll();
        UseCase[] useCaseArray = useCases.toArray(new UseCase[0]);
        try {
            mirrorPreview = true;
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCaseArray);
        } catch (IllegalArgumentException noFrontCamera) {
            Log.w(TAG, "no front camera, fallback to back camera", noFrontCamera);
            mirrorPreview = false;
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseArray);
            Toast.makeText(this, "未找到前置摄像头，已回退后置摄像头", Toast.LENGTH_LONG).show();
        }
        focusRing.setMode(FocusRingView.Mode.CALIBRATION);
        calibrationText.setVisibility(View.VISIBLE);
        calibrationText.setText("请注视屏幕中央的圆点，正在记录专注锚点…");
    }

    // ===================== 判定回调（MediaPipe 线程） =====================

    @Override
    public void onGaze(@NonNull GazeSample sample, @NonNull GazeVerdict verdict) {
        runOnUiThread(() -> applyGaze(sample, verdict));
    }

    @Override
    public void onAnalyzerError(@NonNull Throwable error) {
        Log.e(TAG, "analyzer error", error);
        if (analyzerErrors.incrementAndGet() == ANALYZER_ERROR_TOLERANCE) {
            runOnUiThread(() -> showError("视觉推理持续异常：" + error));
        }
    }

    // ===================== 主线程状态机 =====================

    private void applyGaze(GazeSample sample, GazeVerdict verdict) {
        if (verdict == null) {
            return;
        }
        lastSample = sample;
        lastVerdict = verdict;
        updateHud(sample, verdict);

        if (adCompleted) {
            return;
        }

        switch (verdict.state) {
            case CALIBRATING:
                focusRing.setMode(FocusRingView.Mode.CALIBRATION);
                focusRing.setAlert(false);
                focusRing.setProgress(verdict.progress);
                calibrationText.setVisibility(View.VISIBLE);
                calibrationText.setText(String.format(Locale.US, "%s（%d%%）",
                        verdict.detail, Math.round(verdict.progress * 100)));
                showPersistentStatus("广告将在专注锚点锁定后开始");
                break;

            case FOCUSED:
                calibrationText.setVisibility(View.GONE);
                focusRing.setMode(FocusRingView.Mode.MONITOR);
                focusRing.setAlert(false);
                focusRing.setProgress(0f);
                if (!adStarted || adInterrupted) {
                    startOrRestartAd();
                } else if (!videoView.isPlaying()) {
                    videoView.start();
                }
                break;

            case VIOLATION:
                focusRing.setMode(FocusRingView.Mode.MONITOR);
                focusRing.setAlert(true);
                if (!adStarted) {
                    // 还没开始播（刚校准完又扭头）不算“中断”，只是等待
                    showPersistentStatus("请回到屏幕中央，准备开始观看");
                    break;
                }
                if (!adInterrupted) {
                    interruptAd(verdict);
                } else {
                    updateViolationOverlay(verdict);
                }
                break;

            default:
                break;
        }
    }

    private void interruptAd(GazeVerdict verdict) {
        adInterrupted = true;
        violationCount++;
        abandonAdAudioFocus();
        videoView.pause();
        videoView.seekTo(0); // 必须从头重看
        violationReason.setText(verdict.detail);
        violationCountdown.setText(String.format(Locale.US,
                "请把视线移回屏幕，稳定 %.1f 秒后自动重新播放完整广告",
                WatchPolicy.RECOVERY_HOLD_MS / 1000f));
        violationOverlay.setVisibility(View.VISIBLE);
        showPersistentStatus("广告已中断 · 请重新观看");
    }

    private void updateViolationOverlay(GazeVerdict verdict) {
        if (verdict.progress > 0.01f) {
            long remainMs = (long) (WatchPolicy.RECOVERY_HOLD_MS * (1f - verdict.progress));
            violationCountdown.setText(String.format(Locale.US,
                    "保持注视 %s，%.1f 秒后重新播放", verdict.detail, remainMs / 1000f));
        } else {
            violationCountdown.setText(String.format(Locale.US, "等待中：%s", verdict.detail));
        }
    }

    private void updateHud(GazeSample sample, GazeVerdict verdict) {
        if (verdict.isViolation()) {
            hudTitle.setText("⚠️ 注意力离开");
        } else if (verdict.isCalibrating()) {
            hudTitle.setText("🎯 专注锚点校准中");
        } else {
            hudTitle.setText("✅ 注意力正常");
        }
        if (evaluator.anchor() != null) {
            hudAnchor.setText("锚点 " + evaluator.anchor().describe());
        }
        String device = String.format(Locale.US, "%.1f fps(%s)",
                fps(), analyzer == null ? "-" : analyzer.delegateName());
        if (verdict.isCalibrating() && sample != null && sample.hasFace()) {
            // 校准阶段显示绝对姿态，方便现场判断有没有正对摄像头（阈值见 WatchPolicy）
            hudMetrics.setText(String.format(Locale.US,
                    "%s · 校准姿态 yaw %+.1f° / pitch %+.1f° · 人脸 %d · 眨眼 %d",
                    device, sample.yawDeg, sample.pitchDeg, sample.faceCount,
                    evaluator.blinkCount()));
        } else {
            hudMetrics.setText(String.format(Locale.US,
                    "%s · 头偏 %.1f° · 虹膜偏 %.2f · 人脸 %d · 眨眼 %d · 违规 %d",
                    device, verdict.headDeviationDeg, verdict.irisDeviation,
                    sample == null ? -1 : sample.faceCount, evaluator.blinkCount(), violationCount));
        }
    }

    private float fps() {
        if (analyzer == null) {
            return 0f;
        }
        try {
            return Float.parseFloat(analyzer.fpsText());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void showError(String message) {
        abandonAdAudioFocus();
        stopCountdown();
        if (adCountdown != null) {
            adCountdown.setVisibility(View.GONE);
        }
        errorText.setText(message + "\n\n提示：该 Demo 必须运行在带前置摄像头的真机上。");
        errorOverlay.setVisibility(View.VISIBLE);
        showPersistentStatus("视觉检测不可用");
        videoView.pause();
        if (analyzer != null) {
            analyzer.setPaused(true);
        }
    }
}
