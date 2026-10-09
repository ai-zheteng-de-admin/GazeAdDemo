package com.experiment.gazeforcedad.gaze;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link FaceLandmarkerResult} -> {@link GazeSample} 的纯计算映射。
 *
 * <p>用到三路信号：
 * <ul>
 *   <li>面部变换矩阵 -> 头部 yaw / pitch / roll；</li>
 *   <li>虹膜关键点（478 点模型里的 468~477）+ 眼角/眼睑关键点 -> 眼球在眼眶内的归一化位置；</li>
 *   <li>眼球 blendshape（eyeLook*）-> 虹膜不可用时的兜底视线偏移。</li>
 * </ul>
 *
 * <p>注意：这里刻意只做“原始指标”，不掺杂任何阈值判断——阈值与迟滞全部集中在
 * {@link GazeEvaluator}，方便单独测试与调参。
 */
final class GazeMetricsMapper {

    // MediaPipe Face Mesh 关键点索引（478 点，含虹膜）
    private static final int[] EYE_A_CORNERS = {33, 133};   // 图像左侧眼睛（本人的右眼）
    private static final int[] EYE_A_LIDS = {159, 145};
    private static final int[] EYE_B_CORNERS = {362, 263};  // 图像右侧眼睛（本人的左眼）
    private static final int[] EYE_B_LIDS = {386, 374};
    private static final int IRIS_A_START = 468;
    private static final int IRIS_B_START = 473;
    private static final int IRIS_POINTS = 5;
    private static final int MIN_LANDMARK_COUNT_WITH_IRIS = 478;

    private GazeMetricsMapper() {
    }

    static GazeSample map(FaceLandmarkerResult result) {
        long timestampMs = result.timestampMs();
        List<List<NormalizedLandmark>> faces = result.faceLandmarks();
        int faceCount = faces == null ? 0 : faces.size();
        if (faceCount == 0) {
            return GazeSample.absent(timestampMs, 0);
        }
        List<NormalizedLandmark> landmarks = faces.get(0);
        if (landmarks == null || landmarks.size() < 400) {
            return GazeSample.absent(timestampMs, faceCount);
        }

        float yawDeg = 0f;
        float pitchDeg = 0f;
        float rollDeg = 0f;
        Optional<List<float[]>> matrices = result.facialTransformationMatrixes();
        if (matrices.isPresent() && !matrices.get().isEmpty()) {
            float[] m = matrices.get().get(0);
            if (m != null && m.length >= 16) {
                // MediaPipe 的矩阵按列主序存储（Eigen 约定）：m[col * 4 + row]
                // R = Rz(roll) * Ry(yaw) * Rx(pitch)
                yawDeg = (float) Math.toDegrees(Math.asin(clamp(-m[2], -1f, 1f)));
                pitchDeg = (float) Math.toDegrees(Math.atan2(m[6], m[10]));
                rollDeg = (float) Math.toDegrees(Math.atan2(m[1], m[0]));
            }
        }

        Map<String, Float> bs = blendshapes(result);
        float blink = Math.max(score(bs, "eyeBlinkLeft"), score(bs, "eyeBlinkRight"));
        float eyeOpenness = clamp(1f - blink, 0f, 1f);

        float outL = score(bs, "eyeLookOutLeft");
        float inL = score(bs, "eyeLookInLeft");
        float outR = score(bs, "eyeLookOutRight");
        float inR = score(bs, "eyeLookInRight");
        float upL = score(bs, "eyeLookUpLeft");
        float upR = score(bs, "eyeLookUpRight");
        float downL = score(bs, "eyeLookDownLeft");
        float downR = score(bs, "eyeLookDownRight");
        float lookHorizontal = ((outL + inR) - (inL + outR)) * 0.5f;
        float lookVertical = ((upL + upR) - (downL + downR)) * 0.5f;

        float irisX = Float.NaN;
        float irisY = Float.NaN;
        float[] iris = irisRatios(landmarks);
        if (iris != null) {
            irisX = iris[0];
            irisY = iris[1];
        }

        return GazeSample.present(timestampMs, faceCount, yawDeg, pitchDeg, rollDeg,
                irisX, irisY, lookHorizontal, lookVertical, eyeOpenness);
    }

    private static Map<String, Float> blendshapes(FaceLandmarkerResult result) {
        Map<String, Float> map = new HashMap<>();
        Optional<List<List<Category>>> blendshapes = result.faceBlendshapes();
        if (blendshapes.isPresent() && !blendshapes.get().isEmpty()) {
            List<Category> first = blendshapes.get().get(0);
            if (first != null) {
                for (Category category : first) {
                    map.put(category.categoryName(), category.score());
                }
            }
        }
        return map;
    }

    private static float score(Map<String, Float> map, String name) {
        Float value = map.get(name);
        return value == null ? 0f : value;
    }

    /** 返回 {水平比例, 垂直比例}，均以眼睛中心约 0.5 为基准；无虹膜时返回 null。 */
    private static float[] irisRatios(List<NormalizedLandmark> landmarks) {
        if (landmarks.size() < MIN_LANDMARK_COUNT_WITH_IRIS) {
            return null;
        }
        float[] irisA = centroid(landmarks, IRIS_A_START);
        float[] irisB = centroid(landmarks, IRIS_B_START);
        if (irisA == null || irisB == null) {
            return null;
        }
        float[] eyeAMid = midpoint(landmarks, EYE_A_CORNERS);
        float[] eyeBMid = midpoint(landmarks, EYE_B_CORNERS);
        if (eyeAMid == null || eyeBMid == null) {
            return null;
        }
        // 不依赖虹膜点组与眼睛的左右命名，直接按“离哪个眼眶更近”配对
        float[] ratioA;
        float[] ratioB;
        if (distance2(irisA, eyeAMid) <= distance2(irisA, eyeBMid)) {
            ratioA = eyeRatio(landmarks, irisA, EYE_A_CORNERS, EYE_A_LIDS);
            ratioB = eyeRatio(landmarks, irisB, EYE_B_CORNERS, EYE_B_LIDS);
        } else {
            ratioA = eyeRatio(landmarks, irisB, EYE_A_CORNERS, EYE_A_LIDS);
            ratioB = eyeRatio(landmarks, irisA, EYE_B_CORNERS, EYE_B_LIDS);
        }
        if (ratioA == null || ratioB == null) {
            return null;
        }
        return new float[]{(ratioA[0] + ratioB[0]) * 0.5f, (ratioA[1] + ratioB[1]) * 0.5f};
    }

    private static float[] eyeRatio(List<NormalizedLandmark> landmarks, float[] iris,
                                    int[] corners, int[] lids) {
        float x1 = landmarks.get(corners[0]).x();
        float x2 = landmarks.get(corners[1]).x();
        float y1 = landmarks.get(lids[0]).y();
        float y2 = landmarks.get(lids[1]).y();
        float spanX = Math.abs(x2 - x1);
        float spanY = Math.abs(y2 - y1);
        if (spanX < 1e-4f || spanY < 1e-4f) {
            return null;
        }
        float minX = Math.min(x1, x2);
        float minY = Math.min(y1, y2);
        return new float[]{(iris[0] - minX) / spanX, (iris[1] - minY) / spanY};
    }

    private static float[] centroid(List<NormalizedLandmark> landmarks, int startIndex) {
        if (startIndex + IRIS_POINTS > landmarks.size()) {
            return null;
        }
        float sumX = 0f;
        float sumY = 0f;
        for (int i = startIndex; i < startIndex + IRIS_POINTS; i++) {
            sumX += landmarks.get(i).x();
            sumY += landmarks.get(i).y();
        }
        return new float[]{sumX / IRIS_POINTS, sumY / IRIS_POINTS};
    }

    private static float[] midpoint(List<NormalizedLandmark> landmarks, int[] indices) {
        if (indices[0] >= landmarks.size() || indices[1] >= landmarks.size()) {
            return null;
        }
        return new float[]{
                (landmarks.get(indices[0]).x() + landmarks.get(indices[1]).x()) * 0.5f,
                (landmarks.get(indices[0]).y() + landmarks.get(indices[1]).y()) * 0.5f};
    }

    private static float distance2(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
