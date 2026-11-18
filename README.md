# PoseGuard Lite

重写版：**MediaPipe Pose Landmarker** 做骨骼识别 + 悬浮窗标注。

和老版本的区别：

| | 老版本（ncnn） | 本版本（MediaPipe） |
|---|---|---|
| 推理 | 自己写 JNI 调 libncnn.so | 官方 Kotlin API，一行 `createFromOptions` |
| 模型 | 需要从 PyTorch 导出 ncnn | 官方 `.task` 文件，直接放 assets |
| 后处理 | 自己解析 [56,8400] | 返回已归一化的 33 个关键点 |
| 编译 | 需要 NDK + CMake | 纯 Kotlin，无需 NDK |
| 失败点 | 输入名/通道数/letterbox 全要自己对齐 | 基本没有 |

## 构建

推送到 GitHub，workflow 自动出 `PoseGuardLite-debug`。

本地：

```bash
./gradlew assembleDebug
```

## 安装后

1. 点「申请权限」→ 允许相机、通知
2. 设置 → 应用 → PoseGuard Lite → **显示在其他应用上层** → 允许
3. 点「开始识别」
4. 状态栏会显示 FPS 和检测到的人数

## 关键文件

- `PoseEngine.kt` — MediaPipe 封装，`detect()` 返回归一化关键点
- `MonitorOverlay.kt` — 悬浮窗绘制，33 点骨架 + 连线 + 每人不同颜色
- `CameraService.kt` — CameraX 取帧 → 旋转摆正 → 推理 → 更新悬浮窗
- `assets/pose_landmarker_lite.task` — 官方模型（5.6MB）
