package com.experiment.gazeforcedad.gaze;

/**
 * 单帧注意力特征快照。不可变、纯数据，便于单元测试。
 *
 * <p>所有角度都是从 MediaPipe 面部变换矩阵里按同一套约定解出来的欧拉角，
 * 因为判定只使用“相对锚点的差值”，所以具体符号约定不影响结果。
 */
public final class GazeSample {

    public final long timestampMs;
    /** 画面中检测到的人脸数量（numFaces 上限内）。 */
    public final int faceCount;
    private final boolean facePresent;

    /** 头部偏航（左右转头）角度。 */
    public final float yawDeg;
    /** 头部俯仰（抬头/低头）角度。 */
    public final float pitchDeg;
    /** 头部翻滚（歪头）角度。不参与判定，仅展示。 */
    public final float rollDeg;

    /** 虹膜中心在眼眶内的归一化位置（0~1，约 0.5 为中心）；NaN 表示本帧没有虹膜数据。 */
    public final float irisX;
    public final float irisY;

    /** 由 MediaPipe 眼球 blendshape 组合出的视线偏移（0 附近表示看向正前方）。 */
    public final float lookHorizontal;
    public final float lookVertical;

    /** 睁眼程度：1 = 完全睁开，0 = 闭眼。用于眨眼计数与遮挡判定。 */
    public final float eyeOpenness;

    private GazeSample(long timestampMs, int faceCount, boolean facePresent,
                       float yawDeg, float pitchDeg, float rollDeg,
                       float irisX, float irisY,
                       float lookHorizontal, float lookVertical, float eyeOpenness) {
        this.timestampMs = timestampMs;
        this.faceCount = faceCount;
        this.facePresent = facePresent;
        this.yawDeg = yawDeg;
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
        this.irisX = irisX;
        this.irisY = irisY;
        this.lookHorizontal = lookHorizontal;
        this.lookVertical = lookVertical;
        this.eyeOpenness = eyeOpenness;
    }

    /** 没有检测到可用人脸（含完全无人脸）。 */
    public static GazeSample absent(long timestampMs, int faceCount) {
        return new GazeSample(timestampMs, faceCount, false,
                0f, 0f, 0f, Float.NaN, Float.NaN, 0f, 0f, 1f);
    }

    public static GazeSample present(long timestampMs, int faceCount,
                                     float yawDeg, float pitchDeg, float rollDeg,
                                     float irisX, float irisY,
                                     float lookHorizontal, float lookVertical, float eyeOpenness) {
        return new GazeSample(timestampMs, faceCount, true,
                yawDeg, pitchDeg, rollDeg, irisX, irisY,
                lookHorizontal, lookVertical, eyeOpenness);
    }

    public boolean hasFace() {
        return facePresent;
    }

    public boolean hasIris() {
        return !Float.isNaN(irisX) && !Float.isNaN(irisY);
    }
}
