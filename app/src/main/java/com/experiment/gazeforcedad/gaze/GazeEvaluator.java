package com.experiment.gazeforcedad.gaze;

import com.experiment.gazeforcedad.WatchPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 注意力状态机：把逐帧的 {@link GazeSample} 变成 {@link GazeVerdict}。
 *
 * <p>职责划分：这里只做“判定”，不碰 Android API、不控制播放器，因此可以用纯 JVM 单元测试覆盖
 * 全部迟滞逻辑（见 {@code GazeEvaluatorTest}）。
 *
 * <p>两阶段：
 * <ol>
 *   <li>CALIBRATING：采集用户注视屏幕中央时的样本，算出“专注锚点”；</li>
 *   <li>FOCUSED / VIOLATION：与锚点比较，带防抖迟滞（偏离需持续一段时间才判违规，
 *       回看需稳定一段时间才恢复）。</li>
 * </ol>
 */
public final class GazeEvaluator {

    private final List<GazeSample> calibrationSamples = new ArrayList<>();

    private GazeAnchor anchor;
    private long calibrationStartMs = -1L;
    /** 当前“连续合格”窗口的起点；一旦出现不合格帧就重置。 */
    private long calibrationWindowStartMs = -1L;

    private GazeVerdict.State state = GazeVerdict.State.CALIBRATING;
    private GazeVerdict.Reason reason = GazeVerdict.Reason.NOT_CALIBRATED;
    private String lastDetail = "";

    private long candidateSinceMs = -1L;
    private GazeVerdict.Reason candidateReason = GazeVerdict.Reason.NONE;

    private long eyesClosedSinceMs = -1L;
    private boolean eyesWereClosed = false;
    private int blinkCount = 0;
    private long livenessWindowStartMs = -1L;

    private long recoverySinceMs = -1L;
    private long lastTimestampMs = -1L;

    // ===================== 对外 API =====================

    public synchronized GazeVerdict accept(GazeSample sample) {
        lastTimestampMs = Math.max(lastTimestampMs, sample.timestampMs);
        if (anchor == null) {
            return calibrate(sample);
        }
        return evaluate(sample);
    }

    /** 外部强制判违规（例如应用被切到后台、相机被占用）。 */
    public synchronized GazeVerdict forceViolation(GazeVerdict.Reason reason, String detail, long nowMs) {
        this.state = GazeVerdict.State.VIOLATION;
        this.reason = reason;
        this.lastDetail = detail;
        this.recoverySinceMs = -1L;
        this.candidateSinceMs = -1L;
        this.candidateReason = GazeVerdict.Reason.NONE;
        return GazeVerdict.violation(reason, 0f, 0f, 0f, 0f, 0L, detail);
    }

    public synchronized boolean isCalibrated() {
        return anchor != null;
    }

    public synchronized GazeAnchor anchor() {
        return anchor;
    }

    public synchronized int blinkCount() {
        return blinkCount;
    }

    // ===================== 校准阶段 =====================

    /**
     * 校准阶段：只接受「正脸面对摄像头 + 眼睛基本看着屏幕」的帧，并且要求<b>连续</b>合格。
     *
     * <p>任何一帧不合格都会清空已采集样本并重新计时——这是为了堵住“先看别处把锚点校准偏、
     * 之后一直看别处也判合格”的漏洞。校准不达标时广告不会开始，也绝不降级锁定锚点。
     */
    private GazeVerdict calibrate(GazeSample sample) {
        long now = sample.timestampMs;
        if (calibrationStartMs < 0) {
            calibrationStartMs = now;
        }

        PoseCheck pose = checkCalibrationPose(sample);
        if (!pose.valid) {
            calibrationSamples.clear();
            calibrationWindowStartMs = -1L;
            return GazeVerdict.calibrating(0f, pose.message);
        }

        if (calibrationWindowStartMs < 0) {
            calibrationWindowStartMs = now;
        }
        calibrationSamples.add(sample);

        long windowMs = now - calibrationWindowStartMs;
        float timeProgress = Math.min(1f, windowMs / (float) WatchPolicy.CALIBRATION_MIN_MS);
        float sampleProgress = Math.min(1f,
                calibrationSamples.size() / (float) WatchPolicy.CALIBRATION_MIN_SAMPLES);
        float progress = Math.min(timeProgress, sampleProgress);

        if (windowMs >= WatchPolicy.CALIBRATION_MIN_MS
                && calibrationSamples.size() >= WatchPolicy.CALIBRATION_MIN_SAMPLES) {
            return lockAnchor(now, String.format(Locale.US,
                    "专注锚点已锁定（%d 帧，正脸注视），开始播放广告", calibrationSamples.size()));
        }
        if (now - calibrationStartMs >= WatchPolicy.CALIBRATION_TIMEOUT_MS) {
            return GazeVerdict.calibrating(progress,
                    "需要正对摄像头并看着屏幕中央：请保证光线充足、full脸入镜且不要遮挡");
        }
        return GazeVerdict.calibrating(progress, "请正对摄像头、看着屏幕中央并保持不动，正在记录专注锚点…");
    }

    /** 校准阶段的绝对姿态校验结果。 */
    private static final class PoseCheck {
        final boolean valid;
        final String message;

        PoseCheck(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
    }

    /**
     * 绝对（不依赖锚点）校验：正脸 + 眼睛基本看向摄像头/屏幕。
     * 阈值见 {@link WatchPolicy} 的 CALIBRATION_* 常量，留了容错但足以拦住“盯着别处校准”。
     */
    private static PoseCheck checkCalibrationPose(GazeSample sample) {
        if (!sample.hasFace()) {
            return new PoseCheck(false, "请把脸对准前置摄像头");
        }
        if (WatchPolicy.MULTI_FACE_CHECK_ENABLED
                && sample.faceCount > WatchPolicy.MAX_ALLOWED_FACES) {
            return new PoseCheck(false, "校准只允许一个人：请独自面对摄像头");
        }
        if (sample.eyeOpenness < WatchPolicy.EYE_OPEN_THRESHOLD) {
            return new PoseCheck(false, "请睁眼看着屏幕中央");
        }
        float yaw = Math.abs(sample.yawDeg);
        if (yaw > WatchPolicy.CALIBRATION_MAX_YAW_DEG) {
            return new PoseCheck(false, String.format(Locale.US,
                    "请正对摄像头：头部左右偏了 %.0f°（超过 %.0f°）",
                    yaw, WatchPolicy.CALIBRATION_MAX_YAW_DEG));
        }
        float pitch = Math.abs(sample.pitchDeg);
        if (pitch > WatchPolicy.CALIBRATION_MAX_PITCH_DEG) {
            return new PoseCheck(false, String.format(Locale.US,
                    "请正对摄像头：抬头/低头 %.0f°（超过 %.0f°）",
                    pitch, WatchPolicy.CALIBRATION_MAX_PITCH_DEG));
        }
        if (sample.hasIris()) {
            float offsetX = Math.abs(sample.irisX - 0.5f);
            float offsetY = Math.abs(sample.irisY - 0.5f);
            if (offsetX > WatchPolicy.CALIBRATION_MAX_IRIS_OFFSET_X
                    || offsetY > WatchPolicy.CALIBRATION_MAX_IRIS_OFFSET_Y) {
                return new PoseCheck(false, "眼睛请看屏幕中央：检测到视线偏开");
            }
        } else {
            float look = Math.max(Math.abs(sample.lookHorizontal), Math.abs(sample.lookVertical));
            if (look > WatchPolicy.CALIBRATION_MAX_EYE_LOOK) {
                return new PoseCheck(false, "眼睛请看屏幕中央：检测到视线偏开");
            }
        }
        return new PoseCheck(true, null);
    }

    private GazeVerdict lockAnchor(long nowMs, String detail) {
        anchor = GazeAnchor.fromSamples(calibrationSamples, nowMs);
        state = GazeVerdict.State.FOCUSED;
        reason = GazeVerdict.Reason.NONE;
        lastDetail = detail;
        candidateSinceMs = -1L;
        candidateReason = GazeVerdict.Reason.NONE;
        recoverySinceMs = -1L;
        calibrationWindowStartMs = -1L;
        livenessWindowStartMs = nowMs;
        return GazeVerdict.focused(0f, 0f, 0f, 0f, 0L, detail);
    }

    // ===================== 判定阶段 =====================

    private GazeVerdict evaluate(GazeSample sample) {
        long now = sample.timestampMs;

        float dYaw = 0f;
        float dPitch = 0f;
        float dIrisX = 0f;
        float dIrisY = 0f;
        float dLookH = 0f;
        float dLookV = 0f;

        GazeVerdict.Reason candidate = null;
        String detail = null;

        if (!sample.hasFace()) {
            candidate = GazeVerdict.Reason.NO_FACE;
            detail = "画面中找不到人脸，请回到屏幕前";
        } else if (WatchPolicy.MULTI_FACE_CHECK_ENABLED && sample.faceCount > WatchPolicy.MAX_ALLOWED_FACES) {
            candidate = GazeVerdict.Reason.MULTIPLE_FACES;
            detail = "检测到第二张人脸，广告只对观看者本人有效";
        } else if (sample.eyeOpenness < WatchPolicy.EYE_CLOSED_THRESHOLD) {
            // 眨眼/闭眼：不做视线判定，但闭太久一样算离开
            eyesWereClosed = true;
            if (eyesClosedSinceMs < 0) {
                eyesClosedSinceMs = now;
            }
            if (now - eyesClosedSinceMs >= WatchPolicy.EYES_CLOSED_VIOLATION_MS) {
                candidate = GazeVerdict.Reason.EYES_CLOSED;
                detail = "闭眼或被遮挡太久，请睁眼看着屏幕";
            }
        } else {
            eyesClosedSinceMs = -1L;
            if (eyesWereClosed && sample.eyeOpenness >= WatchPolicy.EYE_OPEN_THRESHOLD) {
                eyesWereClosed = false;
                blinkCount++;
                livenessWindowStartMs = now; // 一次眨眼刷新活性窗口
            }

            dYaw = Math.abs(sample.yawDeg - anchor.yawDeg);
            dPitch = Math.abs(sample.pitchDeg - anchor.pitchDeg);
            if (sample.hasIris() && !Float.isNaN(anchor.irisX)) {
                dIrisX = Math.abs(sample.irisX - anchor.irisX);
                dIrisY = Math.abs(sample.irisY - anchor.irisY);
            }
            dLookH = Math.abs(sample.lookHorizontal - anchor.lookHorizontal);
            dLookV = Math.abs(sample.lookVertical - anchor.lookVertical);

            boolean headBad = dYaw > WatchPolicy.YAW_TOLERANCE_DEG || dPitch > WatchPolicy.PITCH_TOLERANCE_DEG;
            boolean irisBad = dIrisX > WatchPolicy.IRIS_X_TOLERANCE || dIrisY > WatchPolicy.IRIS_Y_TOLERANCE;
            boolean lookBad = dLookH > WatchPolicy.EYE_LOOK_TOLERANCE || dLookV > WatchPolicy.EYE_LOOK_TOLERANCE;
            // 有虹膜关键点时以虹膜为准；没有则退回 blendshape（灵敏度较低）
            boolean gazeBad = sample.hasIris() ? irisBad : lookBad;

            if (headBad) {
                candidate = GazeVerdict.Reason.HEAD_TURNED;
                detail = String.format(Locale.US, "头部偏离屏幕（yaw %.1f° / pitch %.1f°）", dYaw, dPitch);
            } else if (gazeBad) {
                candidate = GazeVerdict.Reason.GAZE_OFF_SCREEN;
                detail = String.format(Locale.US, "眼珠看着别处（虹膜偏移 %.2f / %.2f）", dIrisX, dIrisY);
            } else if (WatchPolicy.BLINK_LIVENESS_ENABLED
                    && livenessWindowStartMs > 0
                    && now - livenessWindowStartMs >= WatchPolicy.BLINK_LIVENESS_WINDOW_MS) {
                candidate = GazeVerdict.Reason.NO_BLINK;
                detail = String.format(Locale.US, "%d 秒内没有眨眼，疑似静态照片",
                        WatchPolicy.BLINK_LIVENESS_WINDOW_MS / 1000);
            }
        }

        float headDeviation = Math.max(dYaw, dPitch);
        float irisDeviation = Math.max(dIrisX, dIrisY);
        float deviation = max(
                dYaw / WatchPolicy.YAW_TOLERANCE_DEG,
                dPitch / WatchPolicy.PITCH_TOLERANCE_DEG,
                dIrisX / WatchPolicy.IRIS_X_TOLERANCE,
                dIrisY / WatchPolicy.IRIS_Y_TOLERANCE,
                sample.hasIris() ? 0f : dLookH / WatchPolicy.EYE_LOOK_TOLERANCE,
                sample.hasIris() ? 0f : dLookV / WatchPolicy.EYE_LOOK_TOLERANCE);

        return applyHysteresis(now, candidate, detail, deviation, headDeviation, irisDeviation);
    }

    private GazeVerdict applyHysteresis(long now, GazeVerdict.Reason candidate, String detail,
                                        float deviation, float headDeviation, float irisDeviation) {
        if (state == GazeVerdict.State.VIOLATION) {
            if (candidate == null) {
                if (recoverySinceMs < 0) {
                    recoverySinceMs = now;
                }
                long held = now - recoverySinceMs;
                if (held >= WatchPolicy.RECOVERY_HOLD_MS) {
                    state = GazeVerdict.State.FOCUSED;
                    reason = GazeVerdict.Reason.NONE;
                    lastDetail = "视线已回到屏幕，继续观看";
                    recoverySinceMs = -1L;
                    return GazeVerdict.focused(deviation, headDeviation, irisDeviation, 0f, 0L, lastDetail);
                }
                return GazeVerdict.violation(reason, deviation, headDeviation, irisDeviation,
                        held / (float) WatchPolicy.RECOVERY_HOLD_MS, held, lastDetail);
            }
            // 仍未回看：刷新原因，恢复进度清零
            recoverySinceMs = -1L;
            if (candidateReason != candidate) {
                candidateReason = candidate;
                candidateSinceMs = now;
            }
            reason = candidate;
            lastDetail = detail;
            return GazeVerdict.violation(candidate, deviation, headDeviation, irisDeviation, 0f,
                    now - candidateSinceMs, detail);
        }

        // FOCUSED
        if (candidate == null) {
            candidateReason = GazeVerdict.Reason.NONE;
            candidateSinceMs = -1L;
            return GazeVerdict.focused(deviation, headDeviation, irisDeviation, 0f, 0L, "专注观看中");
        }
        if (candidateReason != candidate) {
            candidateReason = candidate;
            candidateSinceMs = now;
        }
        long elapsed = now - candidateSinceMs;
        if (isPreDebounced(candidate) || elapsed >= WatchPolicy.DEVIATION_GRACE_MS) {
            state = GazeVerdict.State.VIOLATION;
            reason = candidate;
            lastDetail = detail;
            recoverySinceMs = -1L;
            return GazeVerdict.violation(candidate, deviation, headDeviation, irisDeviation, 0f, elapsed, detail);
        }
        return GazeVerdict.focused(deviation, headDeviation, irisDeviation,
                elapsed / (float) WatchPolicy.DEVIATION_GRACE_MS, elapsed,
                detail + String.format(Locale.US, "（%.1f 秒后中断广告）",
                        (WatchPolicy.DEVIATION_GRACE_MS - elapsed) / 1000f));
    }

    /** 闭眼/不眨眼这类原因本身已带较长的持续时间门槛，不再叠加偏离防抖。 */
    private static boolean isPreDebounced(GazeVerdict.Reason reason) {
        return reason == GazeVerdict.Reason.EYES_CLOSED || reason == GazeVerdict.Reason.NO_BLINK;
    }

    private static float max(float... values) {
        float m = 0f;
        for (float v : values) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }
}
