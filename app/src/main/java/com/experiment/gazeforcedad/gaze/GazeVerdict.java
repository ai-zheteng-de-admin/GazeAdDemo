package com.experiment.gazeforcedad.gaze;

import java.util.Locale;

/** 注意力判定结果，供 UI/广告播放控制使用。 */
public final class GazeVerdict {

    public enum State {
        /** 正在采集专注锚点，广告不应开始播放。 */
        CALIBRATING,
        /** 正常观看。 */
        FOCUSED,
        /** 违规（视线离开 / 无人脸 / 闭眼 / 替看等）。 */
        VIOLATION
    }

    public enum Reason {
        NONE,
        NOT_CALIBRATED,
        /** 头部转开。 */
        HEAD_TURNED,
        /** 眼球偏离屏幕（头部没怎么动但眼睛看别处）。 */
        GAZE_OFF_SCREEN,
        /** 画面里找不到人脸。 */
        NO_FACE,
        /** 长时间闭眼或摄像头被遮挡。 */
        EYES_CLOSED,
        /** 画面中出现第二张人脸。 */
        MULTIPLE_FACES,
        /** 长时间没有眨眼，疑似静态照片。 */
        NO_BLINK,
        /** 应用被切到后台/被中断。 */
        INTERRUPTED
    }

    public final State state;
    public final Reason reason;
    /** 综合偏离度：1.0 表示刚好达到容差边界，越大越偏。 */
    public final float deviation;
    /** 头部相对锚点的最大角度偏差（度）。 */
    public final float headDeviationDeg;
    /** 虹膜相对锚点的最大归一化偏移。 */
    public final float irisDeviation;
    /** 进度：校准阶段=校准进度；违规恢复阶段=回看稳定进度；其余为 0。 */
    public final float progress;
    /** 当前偏离/违规已持续的时间（毫秒）。 */
    public final long reasonElapsedMs;
    /** 给用户看的中文说明。 */
    public final String detail;

    private GazeVerdict(State state, Reason reason, float deviation, float headDeviationDeg,
                        float irisDeviation, float progress, long reasonElapsedMs, String detail) {
        this.state = state;
        this.reason = reason;
        this.deviation = deviation;
        this.headDeviationDeg = headDeviationDeg;
        this.irisDeviation = irisDeviation;
        this.progress = progress;
        this.reasonElapsedMs = reasonElapsedMs;
        this.detail = detail;
    }

    static GazeVerdict calibrating(float progress, String detail) {
        return new GazeVerdict(State.CALIBRATING, Reason.NOT_CALIBRATED, 0f, 0f, 0f,
                clamp01(progress), 0L, detail);
    }

    static GazeVerdict focused(float deviation, float headDeviationDeg, float irisDeviation,
                               float progress, long reasonElapsedMs, String detail) {
        return new GazeVerdict(State.FOCUSED, Reason.NONE, deviation, headDeviationDeg,
                irisDeviation, clamp01(progress), reasonElapsedMs, detail);
    }

    static GazeVerdict violation(Reason reason, float deviation, float headDeviationDeg,
                                 float irisDeviation, float progress, long reasonElapsedMs, String detail) {
        return new GazeVerdict(State.VIOLATION, reason, deviation, headDeviationDeg,
                irisDeviation, clamp01(progress), reasonElapsedMs, detail);
    }

    public boolean isCalibrating() {
        return state == State.CALIBRATING;
    }

    public boolean isFocused() {
        return state == State.FOCUSED;
    }

    public boolean isViolation() {
        return state == State.VIOLATION;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s/%s dev=%.2f head=%.1f° iris=%.2f %s",
                state, reason, deviation, headDeviationDeg, irisDeviation, detail);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
