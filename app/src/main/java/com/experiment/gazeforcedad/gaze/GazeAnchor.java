package com.experiment.gazeforcedad.gaze;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 专注锚点：校准阶段用户注视屏幕中央时，头部姿态与虹膜位置的中位数。
 * 用中位数而不是均值，避免校准期间偶发的抖动/眨眼残留把锚点带偏。
 */
public final class GazeAnchor {

    public final float yawDeg;
    public final float pitchDeg;
    public final float irisX;
    public final float irisY;
    public final float lookHorizontal;
    public final float lookVertical;
    public final int sampleCount;
    public final long createdAtMs;

    private GazeAnchor(float yawDeg, float pitchDeg, float irisX, float irisY,
                       float lookHorizontal, float lookVertical, int sampleCount, long createdAtMs) {
        this.yawDeg = yawDeg;
        this.pitchDeg = pitchDeg;
        this.irisX = irisX;
        this.irisY = irisY;
        this.lookHorizontal = lookHorizontal;
        this.lookVertical = lookVertical;
        this.sampleCount = sampleCount;
        this.createdAtMs = createdAtMs;
    }

    static GazeAnchor fromSamples(List<GazeSample> samples, long createdAtMs) {
        List<Float> yaw = new ArrayList<>();
        List<Float> pitch = new ArrayList<>();
        List<Float> irisX = new ArrayList<>();
        List<Float> irisY = new ArrayList<>();
        List<Float> lookH = new ArrayList<>();
        List<Float> lookV = new ArrayList<>();
        for (GazeSample s : samples) {
            if (!s.hasFace()) {
                continue;
            }
            yaw.add(s.yawDeg);
            pitch.add(s.pitchDeg);
            lookH.add(s.lookHorizontal);
            lookV.add(s.lookVertical);
            if (s.hasIris()) {
                irisX.add(s.irisX);
                irisY.add(s.irisY);
            }
        }
        return new GazeAnchor(
                median(yaw), median(pitch), median(irisX), median(irisY),
                median(lookH), median(lookV), samples.size(), createdAtMs);
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) {
            return Float.NaN;
        }
        values.sort(Float::compare);
        int n = values.size();
        return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2f;
    }

    public String describe() {
        return String.format(Locale.US, "锚点 yaw %+.1f° / pitch %+.1f° / 虹膜 %.2f,%.2f（%d 帧）",
                yawDeg, pitchDeg, irisX, irisY, sampleCount);
    }
}
