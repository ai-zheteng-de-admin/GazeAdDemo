# GazeAdDemo

一个 Android 端侧视线检测实验 Demo：播放视频时使用前置摄像头判断用户是否注视屏幕。视线离开后视频中断，回到屏幕并稳定注视后从头播放。

> 本项目仅用于学习、研究和技术交流。源码公开不等于开源授权，未经作者书面许可，不得用于商业产品、生产环境、实际广告投放或用户监控。
>
> 第三方模型、依赖和素材遵循其各自许可证，不在本项目许可范围内。

## 功能

- 前置摄像头本地分析头部姿态、眼睛状态和视线偏移。
- 校准后持续判断注意力，视线离开则中断视频。
- 视线回到屏幕并保持稳定后，视频从 0 秒重新播放。
- 支持眨眼活性、多人脸、闭眼和遮挡检测。
- 支持关闭预览、马赛克预览和人脸关键点预览。
- 支持选择本地视频，也可以使用内置默认视频。
- 处理过程在设备本地完成，应用不上传摄像头画面。

## 运行环境

- Android 12 或更高版本。
- 带前置摄像头的 Android 真机，模拟器效果不代表真机表现。
- Android Studio，建议使用较新版本。
- JDK 11。
- Gradle Wrapper 已包含在项目中，无需单独安装 Gradle。

## 下载安装

普通用户无需配置开发环境，可以直接在 Gitee 的 **发行版 / Releases** 中下载 APK，安装到 Android 12 或更高版本的手机。

> 安装时如果系统提示风险，请确认 APK 来源后，按手机提示允许本次安装。

## 自行构建

开发者可以按照以下步骤自行构建：

1. Face Landmarker 模型已经包含在：

```text
app/src/main/assets/face_landmarker.task
```

如需更新模型，可以从官方地址下载并覆盖：

```text
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
```

2. 默认示例视频已经包含在：

```text
app/src/main/assets/default_ad.mp4
```

该视频为项目作者自制，可以直接随源码使用。也可以替换成自己的视频；请勿上传或分发没有授权的广告、音乐和影视素材。

3. 构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

4. 安装到真机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 启动应用并允许摄像头权限。
2. 在首页选择视频、预览方式和调试信息显示选项。
3. 进入观看页后，注视屏幕中央的圆环完成校准。
4. 校准通过后视频自动播放。
5. 视线离开屏幕会中断视频；回到屏幕并稳定注视后从头重播。
6. 完整播放结束后显示完成页面和统计信息。

## 隐私说明

- 摄像头画面只在内存中处理，项目本身不保存、不上传人脸图像。
- MediaPipe 的传递依赖可能引入 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限，但本项目的推理路径不依赖网络。
- 如果项目用于真实用户，请根据平台要求补充隐私政策和数据安全声明。

## 已知限制

- 本项目使用的是启发式视线判断，不是专业眼动仪。
- 强逆光、暗光、墨镜、口罩反光和脸部角度会影响准确率。
- 项目没有实现完整的活体检测，照片、视频回放或 3D 面具仍可能绕过。
- 仅适合技术验证和研究学习，不建议直接用于生产环境。

## 项目结构

```text
app/src/main/java/              Java 源码
app/src/main/res/               布局、资源和样式
app/src/main/assets/            模型和默认视频
app/src/main/AndroidManifest.xml
app/build.gradle.kts            应用构建配置
build.gradle.kts                项目构建配置
gradle/                         Gradle 配置和 Wrapper
```

## 许可范围

本项目源码仅授权用于个人学习、学术研究、技术验证和非商业交流。

允许在非商业研究学习范围内查看、运行和修改源码，但必须保留原始版权和许可声明。

未经作者书面许可，禁止：

- 商业使用、销售、收费服务或二次售卖；
- 部署到生产环境；
- 用于实际广告强制投放、用户监控或其他未经用户同意的场景；
- 以本项目为基础发布商业应用或闭源商业产品。

第三方库、模型、数据集、字体、图片和视频不适用本许可，请遵守其原始许可证。

项目内自制的示例视频随项目提供，仅允许在上述学习研究范围内使用。

## 第三方组件

- [MediaPipe](https://github.com/google-ai-edge/mediapipe)：端侧人脸关键点与表情分析。
- [CameraX](https://developer.android.com/training/camerax)：摄像头取流和生命周期管理。
- [AndroidX](https://developer.android.com/jetpack/androidx)：Android 基础组件。
- [Material Components for Android](https://github.com/material-components/material-components-android)：界面组件。
- Face Landmarker 模型：请以官方模型页面和模型条款为准。
