# 视频转Live Photo

一键将视频转为小米相册动态照片 (Motion Photo)。

## 功能

- 📹 相册选视频或分享入口，转 Live Photo
- ✂ 双滑块精确选取转换片段（开头~结尾）
- 📸 封面帧实时预览
- 🎚 4 档画质模式：极速 / 推荐 / 高清 / 无损
- 🚀 GPU 硬编加速（SDR 视频 1-3 秒完成）
- 🔔 后台转换 + 通知栏进度
- 🖼 输出到 DCIM/Live/，相册直接识别

## 使用

1. **选视频** — 从相册选择或通过相册分享入口
2. **选片段** — 拖动双滑块确定开头和结尾（默认全视频）
3. **选画质** — 默认推荐档，平衡速度与质量
4. **点转换** — 通知栏看进度，完成后点击直接打开相册

### 分享入口

| 入口 | 行为 |
|------|------|
| **调节参数** | 打开主界面调参后转换 |
| **后台转换** | 一键直转，默认参数，通知栏看进度 |

## 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 限制

- **小米设备**专为小米相册设计，其他品牌可能不识别 Live Photo
- **杜比视界** 视频 GPU 不兼容，CPU 编码较慢 → [TODO] 寻找带 zscale/tonemap 的 ffmpeg 编译版
- Android 8.0+ (API 26)

## 构建

```bash
# 设置 Android SDK + JDK 21
export JAVA_HOME="path/to/jdk"
./gradlew assembleDebug
```

## 技术

详见 [TECHNICAL.md](./TECHNICAL.md)
