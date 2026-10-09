package com.experiment.gazeforcedad;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;

/**
 * 入口页：观看设置（自定义广告视频 / 是否显示预览 / 预览是否打马）+ 策略说明 + 摄像头权限 + 进入广告页。
 */
public final class MainActivity extends AppCompatActivity {

    private static final String TAG = "GazeAdDemo";
    private static final int REQUEST_CAMERA = 1001;
    private static final int REQUEST_PICK_VIDEO = 1002;

    private DemoSettings settings;
    private TextView videoSummary;
    private SwitchMaterial showPreviewSwitch;
    private SwitchMaterial mosaicPreviewSwitch;
    private SwitchMaterial showFacePointsSwitch;
    private SwitchMaterial showDebugHudSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new DemoSettings(this);

        TextView policyText = findViewById(R.id.policyText);
        policyText.setText(WatchPolicy.summary());

        setupSettingsViews();

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(v -> ensureCameraPermissionThenStart());
    }

    // ===================== 设置项 =====================

    private void setupSettingsViews() {
        videoSummary = findViewById(R.id.videoSummary);
        showPreviewSwitch = findViewById(R.id.showPreviewSwitch);
        mosaicPreviewSwitch = findViewById(R.id.mosaicPreviewSwitch);
        showFacePointsSwitch = findViewById(R.id.showFacePointsSwitch);
        showDebugHudSwitch = findViewById(R.id.showDebugHudSwitch);

        // 先同步开关状态，再挂监听，避免初始化时触发写入
        showPreviewSwitch.setChecked(settings.showPreview());
        mosaicPreviewSwitch.setChecked(settings.mosaicPreview());
        mosaicPreviewSwitch.setEnabled(settings.showPreview());
        showFacePointsSwitch.setChecked(settings.showFacePoints());
        showFacePointsSwitch.setEnabled(settings.showPreview());
        showDebugHudSwitch.setChecked(settings.showDebugHud());
        updateVideoSummary();

        showPreviewSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.setShowPreview(isChecked);
            mosaicPreviewSwitch.setEnabled(isChecked);
            showFacePointsSwitch.setEnabled(isChecked);
        });
        mosaicPreviewSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> settings.setMosaicPreview(isChecked));
        showFacePointsSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> settings.setShowFacePoints(isChecked));
        showDebugHudSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> settings.setShowDebugHud(isChecked));

        Button pickVideoButton = findViewById(R.id.pickVideoButton);
        pickVideoButton.setOnClickListener(v -> pickVideo());

        Button resetVideoButton = findViewById(R.id.resetVideoButton);
        resetVideoButton.setOnClickListener(v -> {
            settings.clearCustomVideo();
            updateVideoSummary();
            Toast.makeText(this, "已恢复内置示例广告", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateVideoSummary() {
        if (!settings.hasCustomVideo()) {
            videoSummary.setText(R.string.ad_video_builtin);
            return;
        }
        long durationMs = settings.videoDurationMs();
        if (durationMs > 0) {
            videoSummary.setText(String.format(Locale.US, "当前：%s（%.1f 秒）",
                    settings.videoName(), durationMs / 1000f));
        } else {
            videoSummary.setText("当前：" + settings.videoName());
        }
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VIDEO || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            // 持久化读权限：广告页隔天打开也能继续读这个视频
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "takePersistableUriPermission failed", e);
        }
        readVideoMetaAsync(uri);
    }

    private void readVideoMetaAsync(Uri uri) {
        videoSummary.setText(R.string.video_meta_reading);
        new Thread(() -> {
            String name = queryDisplayName(uri);
            long durationMs = queryDurationMs(uri);
            runOnUiThread(() -> {
                settings.setCustomVideo(uri, name, durationMs);
                updateVideoSummary();
                Toast.makeText(this, R.string.custom_video_ready, Toast.LENGTH_SHORT).show();
            });
        }, "video-meta").start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "query display name failed", e);
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "本地视频" : fallback;
    }

    private long queryDurationMs(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            Log.w(TAG, "read video duration failed", e);
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Android 14+ 的 release() 可能抛 IOException
            }
        }
    }

    // ===================== 摄像头权限 / 进入广告 =====================

    private void ensureCameraPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startAd();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAd();
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void startAd() {
        startActivity(new Intent(this, AdActivity.class));
    }
}
