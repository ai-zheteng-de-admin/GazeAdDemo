package com.experiment.gazeforcedad;

import java.util.Locale;

/**
 * 全部可调参数集中在这里，方便按机型/体验调灵敏度。
 *
 * <p>判定思路：先在校准阶段记录用户正对屏幕时的头部角度与虹膜位置（“专注锚点”），
 * 之后每帧只比较“相对锚点的偏差”，因此不依赖设备摄像头安装角度，也不需要用户做复杂标定。
 */
public final class WatchPolicy {

    private WatchPolicy() {
    }

    // ======================= 校准 / 专注锚点 =======================
    /** 校准阶段最短时长（毫秒）。期间广告暂停，避免白看。 */
    public static final long CALIBRATION_MIN_MS = 3_000L;
    /** 校准需要的最少有效帧数。 */
    public static final int CALIBRATION_MIN_SAMPLES = 12;
    /** 校准超时：超过后仍然只提示原因，绝不降级锁定（否则等于给“看着别处校准”开门）。 */
    public static final long CALIBRATION_TIMEOUT_MS = 20_000L;

    // ====== 校准期间的“正脸 + 看向摄像头”绝对校验（防止先看别处校准、之后一直看别处） ======
    /** 校准样本允许的最大头部偏航（绝对角度，阈值内才算正对摄像头）。 */
    public static final float CALIBRATION_MAX_YAW_DEG = 14f;
    /** 校准样本允许的最大头部俯仰。 */
    public static final float CALIBRATION_MAX_PITCH_DEG = 14f;
    /** 校准样本虹膜距眼眶中心的水平偏移上限（0~1 比例，0.5 为中心）。 */
    public static final float CALIBRATION_MAX_IRIS_OFFSET_X = 0.20f;
    /** 垂直方向放宽一些：看向屏幕中央时眼睛相对摄像头本来就会略微下移。 */
    public static final float CALIBRATION_MAX_IRIS_OFFSET_Y = 0.28f;
    /** 没有虹膜数据时，用眼球 blendshape 兜底的偏移上限。 */
    public static final float CALIBRATION_MAX_EYE_LOOK = 0.55f;

    // ======================= 偏离容差（相对锚点） =======================
    /** 头部左右转动超过该角度（度）判定为“看别处”。 */
    public static final float YAW_TOLERANCE_DEG = 16f;
    /** 头部俯仰超过该角度（度）判定为“看别处”。 */
    public static final float PITCH_TOLERANCE_DEG = 14f;
    /** 虹膜在眼眶内的水平归一化位置偏移容差（0~1 比例）。 */
    public static final float IRIS_X_TOLERANCE = 0.18f;
    /** 虹膜垂直位置偏移容差。 */
    public static final float IRIS_Y_TOLERANCE = 0.22f;
    /** MediaPipe 眼球 blendshape 的组合偏移容差（0~1）。作为虹膜缺失时的兜底。 */
    public static final float EYE_LOOK_TOLERANCE = 0.55f;

    // ======================= 时间迟滞 =======================
    /** 偏离需要连续维持多久才判定违规（防抖，避免眨眼/瞬间扭头误判）。 */
    public static final long DEVIATION_GRACE_MS = 700L;
    /** 违规后视线回到屏幕并稳定多久才自动重新播放。 */
    public static final long RECOVERY_HOLD_MS = 1_200L;

    // ======================= 眨眼 / 遮挡 =======================
    /** eyeOpenness 低于该值视为闭眼或遮挡。 */
    public static final float EYE_CLOSED_THRESHOLD = 0.45f;
    /** 重新视为睁眼的阈值（迟滞，避免抖动重复计数）。 */
    public static final float EYE_OPEN_THRESHOLD = 0.70f;
    /** 持续闭眼/遮挡超过该时长判违规。 */
    public static final long EYES_CLOSED_VIOLATION_MS = 1_500L;
    /** 开启“长时间不眨眼 = 疑似静态照片”的活性校验。 */
    public static final boolean BLINK_LIVENESS_ENABLED = true;
    /** 活性窗口：该时长内一次眨眼都没有则判违规。 */
    public static final long BLINK_LIVENESS_WINDOW_MS = 20_000L;

    // ======================= 多人（替看）校验 =======================
    /** 开启后，画面中出现第二张人脸也视为违规。 */
    public static final boolean MULTI_FACE_CHECK_ENABLED = true;
    public static final int MAX_ALLOWED_FACES = 1;

    // ======================= 性能 =======================
    /** 送入 MediaPipe 的分析帧率上限（预览不受影响）。 */
    public static final int ANALYSIS_TARGET_FPS = 12;

    /** 给首页展示的策略摘要。 */
    public static String summary() {
        return String.format(Locale.US,
                "专注锚点：正对摄像头且眼睛看向屏幕，连续稳定 %.1f 秒后锁定（中途看别处会重新计时）\n"
                        + "判定为离开：头部偏航 > %.0f° / 俯仰 > %.0f° / 虹膜偏移 > %.2f\n"
                        + "触发违规：连续偏离 > %.1f 秒\n"
                        + "重新播放：视线回到屏幕稳定 %.1f 秒后，从 0 秒重播\n"
                        + "活性校验：%s（%d 秒内无眨眼判违规）；%s（检测到第二张人脸判违规）\n"
                        + "分析频率：%d fps（本地推理，无网络请求）",
                CALIBRATION_MIN_MS / 1000f,
                YAW_TOLERANCE_DEG, PITCH_TOLERANCE_DEG, IRIS_X_TOLERANCE,
                DEVIATION_GRACE_MS / 1000f,
                RECOVERY_HOLD_MS / 1000f,
                BLINK_LIVENESS_ENABLED ? "开启" : "关闭", BLINK_LIVENESS_WINDOW_MS / 1000,
                MULTI_FACE_CHECK_ENABLED ? "开启" : "关闭",
                ANALYSIS_TARGET_FPS);
    }
}
