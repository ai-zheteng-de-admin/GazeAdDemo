package com.experiment.gazeforcedad;

/**
 * 广告视频的显示策略（纯计算，可单元测试）：
 *
 * <ul>
 *   <li><b>方向跟随视频</b>：横屏视频 → 横屏播放，竖屏视频 → 竖屏播放，
 *       屏幕方向由视频自身比例决定，而不是写死竖屏；</li>
 *   <li><b>不拉伸变形</b>：视频始终保持原始宽高比。只有视频比例和屏幕比例
 *       几乎一致（差异 ≤ {@link #ASPECT_FILL_TOLERANCE}）时才铺满屏幕，
 *       否则一律完整显示（两侧/上下留黑边），既不平铺拉伸也不裁切内容。</li>
 * </ul>
 */
public final class VideoDisplayPolicy {

    /** 允许铺满屏幕的最大比例差异（相对值）。6% 以内的裁切肉眼几乎不可见。 */
    public static final float ASPECT_FILL_TOLERANCE = 0.06f;

    private VideoDisplayPolicy() {
    }

    /** 把视频旋转元数据考虑进去后的显示宽度（旋转 90°/270° 时宽高互换）。 */
    public static int displayWidth(int rawWidth, int rawHeight, int rotationDegrees) {
        return isQuarterTurn(rotationDegrees) ? rawHeight : rawWidth;
    }

    /** 把视频旋转元数据考虑进去后的显示高度。 */
    public static int displayHeight(int rawWidth, int rawHeight, int rotationDegrees) {
        return isQuarterTurn(rotationDegrees) ? rawWidth : rawHeight;
    }

    /** 视频是否应当横屏观看。 */
    public static boolean isLandscape(int width, int height) {
        return width > height;
    }

    /**
     * 能否用「铺满 + 裁切」模式显示。
     *
     * @return true  = 视频比例与容器比例足够接近，可铺满（裁切量可忽略）
     *         false = 比例差异较大，必须按原始比例完整显示，避免拉伸/裁切
     */
    public static boolean canFillScreen(int videoWidth, int videoHeight,
                                        int containerWidth, int containerHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return false;
        }
        float videoAspect = videoWidth / (float) videoHeight;
        float containerAspect = containerWidth / (float) containerHeight;
        return Math.abs(videoAspect - containerAspect) / containerAspect <= ASPECT_FILL_TOLERANCE;
    }

    /**
     * 广告剩余秒数（向上取整）：刚开始时显示完整时长，最后不足 1 秒显示 1，播完显示 0。
     * 用播放器真实进度计算，所以暂停/中断时倒计时自然停住。
     */
    public static int remainingSeconds(long positionMs, long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        long remain = durationMs - positionMs;
        if (remain <= 0) {
            return 0;
        }
        return (int) ((remain + 999L) / 1000L);
    }

    private static boolean isQuarterTurn(int rotationDegrees) {
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        return normalized == 90 || normalized == 270;
    }
}
