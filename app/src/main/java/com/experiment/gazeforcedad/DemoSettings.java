package com.experiment.gazeforcedad;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * 主页面“观看设置”的本地持久化：
 * <ul>
 *   <li>自定义开屏广告视频（SAF 选择，持久化读权限）；</li>
 *   <li>测试时是否显示摄像头预览；</li>
 *   <li>预览是否打马赛克（只影响预览显示，送模型检测的始终是原始帧）。</li>
 * </ul>
 */
public final class DemoSettings {

    private static final String PREFS = "gaze_ad_demo_settings";
    private static final String KEY_VIDEO_URI = "ad_video_uri";
    private static final String KEY_VIDEO_NAME = "ad_video_name";
    private static final String KEY_VIDEO_DURATION = "ad_video_duration_ms";
    private static final String KEY_SHOW_PREVIEW = "show_camera_preview";
    private static final String KEY_MOSAIC_PREVIEW = "mosaic_camera_preview";
    private static final String KEY_SHOW_FACE_POINTS = "show_face_points";
    private static final String KEY_SHOW_DEBUG_HUD = "show_debug_hud";
    private static final String KEY_PREVIEW_POS_X = "preview_pos_x";
    private static final String KEY_PREVIEW_POS_Y = "preview_pos_y";

    private final SharedPreferences prefs;

    public DemoSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ===================== 广告视频 =====================

    /** 自定义广告视频，null 表示使用内置示例广告。 */
    public Uri videoUri() {
        String value = prefs.getString(KEY_VIDEO_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    public String videoName() {
        return prefs.getString(KEY_VIDEO_NAME, null);
    }

    public long videoDurationMs() {
        return prefs.getLong(KEY_VIDEO_DURATION, 0L);
    }

    public void setCustomVideo(Uri uri, String name, long durationMs) {
        prefs.edit()
                .putString(KEY_VIDEO_URI, uri.toString())
                .putString(KEY_VIDEO_NAME, name)
                .putLong(KEY_VIDEO_DURATION, durationMs)
                .apply();
    }

    public void clearCustomVideo() {
        prefs.edit()
                .remove(KEY_VIDEO_URI)
                .remove(KEY_VIDEO_NAME)
                .remove(KEY_VIDEO_DURATION)
                .apply();
    }

    public boolean hasCustomVideo() {
        return videoUri() != null;
    }

    // ===================== 预览设置 =====================

    /** 测试时是否显示摄像头预览（默认显示）。 */
    public boolean showPreview() {
        return prefs.getBoolean(KEY_SHOW_PREVIEW, true);
    }

    public void setShowPreview(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_PREVIEW, show).apply();
    }

    /** 预览是否打马赛克（默认关闭）。 */
    public boolean mosaicPreview() {
        return prefs.getBoolean(KEY_MOSAIC_PREVIEW, false);
    }

    public void setMosaicPreview(boolean mosaic) {
        prefs.edit().putBoolean(KEY_MOSAIC_PREVIEW, mosaic).apply();
    }

    /** 是否在预览画面里叠加人脸关键点（默认关闭）。 */
    public boolean showFacePoints() {
        return prefs.getBoolean(KEY_SHOW_FACE_POINTS, false);
    }

    public void setShowFacePoints(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_FACE_POINTS, show).apply();
    }

    // ===================== 预览小窗位置 =====================

    /** 是否记录过预览小窗位置（按可用范围归一化到 0~1，转屏/换分辨率都能还原）。 */
    public boolean hasPreviewPosition() {
        return prefs.contains(KEY_PREVIEW_POS_X) && prefs.contains(KEY_PREVIEW_POS_Y);
    }

    public float previewPositionX() {
        return prefs.getFloat(KEY_PREVIEW_POS_X, 0f);
    }

    public float previewPositionY() {
        return prefs.getFloat(KEY_PREVIEW_POS_Y, 0f);
    }

    public void setPreviewPosition(float x, float y) {
        prefs.edit()
                .putFloat(KEY_PREVIEW_POS_X, Math.max(0f, Math.min(1f, x)))
                .putFloat(KEY_PREVIEW_POS_Y, Math.max(0f, Math.min(1f, y)))
                .apply();
    }

    /** 广告页是否显示调试信息面板（fps / 头偏 / 虹膜 / 违规次数等，默认显示）。 */
    public boolean showDebugHud() {
        return prefs.getBoolean(KEY_SHOW_DEBUG_HUD, true);
    }

    public void setShowDebugHud(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_DEBUG_HUD, show).apply();
    }
}
