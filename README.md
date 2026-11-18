# PoseGuard Lite

相机 + 悬浮窗骨架标注。识别用 **YOLO11n-pose**，推理用 **ONNX Runtime**（纯 Kotlin，无需 JNI / NDK）。

## 数据流

```
CameraX 出帧 (YUV_420_888)
  → NV21 → JPEG → Bitmap → 旋转 90° 摆正
  → letterbox 到 640×640（补黑边）
  → ARGB → NCHW float[0,1]
  → ORT 推理 images → output0 [1,56,8400]
  → 通道优先解码：cx,cy,w,h,conf + 17×3 关键点
  → 置信度过滤 + NMS + 反 letterbox
  → 归一化坐标 → 悬浮窗绘制
```

## 关键常量

| 项 | 值 |
|---|---|
| 输入名 | `images` |
| 输出名 | `output0` |
| 输入尺寸 | 640×640 |
| 输出形状 | [1, 56, 8400] |
| 关键点 | 17 个 COCO 顺序 |
| conf 阈值 | 0.28 |
| NMS IoU | 0.45 |

## 构建

推送到 GitHub 触发 workflow，或本地 `./gradlew assembleDebug`。

## 安装后

1. 点「申请权限」→ 相机、通知
2. 设置 → 应用 → PoseGuard Lite → 显示在其他应用上层 → 允许
3. 点「开始识别」

状态栏会显示 FPS、目标人数，以及「引擎：推理 N 帧 / 有人 M 帧」——这行能直接看出是没检测到还是没绘制。
