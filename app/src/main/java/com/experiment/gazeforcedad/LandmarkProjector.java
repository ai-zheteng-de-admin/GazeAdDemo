package com.experiment.gazeforcedad;

/**
 * 人脸关键点坐标映射（纯计算，可单元测试）。
 *
 * <p>MediaPipe 输出的关键点是“已经按旋转角摆正”的图像坐标（0~1 归一化）。
 * 预览位图同样是摆正后再做前置镜像的，所以映射规则很简单：
 * 不镜像时直接用归一化坐标乘显示尺寸；镜像时横坐标取 {@code 1 - x}。
 */
public final class LandmarkProjector {

    private LandmarkProjector() {
    }

    /**
     * @param normalized   [x0, y0, x1, y1, ...] 归一化坐标，可为 null
     * @param displayWidth 预览位图宽度（像素）
     * @param displayHeight 预览位图高度（像素）
     * @param mirror       前置摄像头预览是否水平镜像
     * @return 显示坐标数组 [x0, y0, x1, y1, ...]；输入为 null 时返回空数组
     */
    public static float[] toDisplay(float[] normalized, int displayWidth, int displayHeight,
                                    boolean mirror) {
        if (normalized == null || normalized.length < 2) {
            return new float[0];
        }
        float[] display = new float[normalized.length];
        for (int i = 0; i + 1 < normalized.length; i += 2) {
            float x = normalized[i];
            float y = normalized[i + 1];
            if (Float.isNaN(x) || Float.isNaN(y)) {
                display[i] = Float.NaN;
                display[i + 1] = Float.NaN;
                continue;
            }
            display[i] = (mirror ? 1f - x : x) * displayWidth;
            display[i + 1] = y * displayHeight;
        }
        return display;
    }
}
