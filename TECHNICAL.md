# VideoToLive 技术文档

## 项目概述

Android 视频转 Live Photo (动态照片) 工具。将视频转换为小米相册可识别的 Google Motion Photo 格式。

| 项目 | 详情 |
|------|------|
| 包名 | `com.videotolive.app` |
| minSdk / targetSdk | 26 (Android 8.0) / 34 |
| 语言 | Java 11 |
| 构建 | Gradle 8.13 + AGP 8.5.2 |
| FFmpeg | 预编译 GPL 版 v7.1 (内置 APK) |
| APK 体积 | ~33MB (含 27MB ffmpeg + 24MB ffprobe) |

---

## 核心原理

### Motion Photo 文件格式

```
┌──────────────────────────────────┐
│  JPEG 图像 (含 EXIF + XMP)       │
│  ├─ SOI (FFD8)                   │
│  ├─ APP1 (XMP 元数据) ← 关键位置 │
│  ├─ SOS (FFDA) → 压缩图像数据    │
│  └─ EOI (FFD9)                   │
├──────────────────────────────────┤
│  MP4 视频数据                    │
└──────────────────────────────────┘
```

**关键规则：**
- XMP APP1 段必须注入在 **SOS 之前**，小米相册才能识别
- `Item:Length` 必须填写实际 JPEG 字节数（不能为 0），否则大视频无法播放
- 视频数据直接追加在 JPEG 末尾，无分隔符

### XMP 结构

```xml
<x:xmpmeta>
  <rdf:RDF>
    <rdf:Description
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoVersion="1">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li>
            <Item:Mime>image/jpeg</Item:Mime>
            <Item:Semantic>Primary</Item:Semantic>
            <Item:Length>JPEG_BYTE_SIZE</Item:Length>
          </rdf:li>
          <rdf:li>
            <Item:Mime>video/mp4</Item:Mime>
            <Item:Semantic>MotionPhoto</Item:Semantic>
            <Item:Length>MP4_BYTE_SIZE</Item:Length>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
```

---

## FFmpeg 集成

### 二进制来源

- 来源: [yearsyan/ffmpeg-android-build](https://github.com/yearsyan/ffmpeg-android-build) v7.1-beta.16
- 版本: **GPL** 版（含 libx264/libx265）
- 架构: aarch64 (arm64-v8a)，静态编译
- 首次启动通过 `FFmpegProvider.extractFromAssets()` 释放到 `files/bin/`

### 版本管理

`FFmpegProvider` 使用 `.version` 文件标记。递增 `curVer` 常量强制重新释放：

```java
String curVer = "2";
```

### 执行方式

**核心坑：Android 10+ SELinux 禁止从 app 私有目录执行二进制。**

| 方式 | 结果 |
|------|------|
| `Runtime.exec()` | ❌ Permission denied |
| `sh -c` | ❌ 同上 |
| `ProcessBuilder` | ❌ 同上 |
| **`/system/bin/linker64`** | ✅ **唯一可行方案** |

`linker64` 是系统 ELF 加载器，SELinux 允许它加载 PIE 可执行文件。`exec()` 方法优先走 linker64，失败则尝试其他方式作为兜底。

### 滤镜语法注意

从 `sh -c` 切换到 ProcessBuilder 后，单引号不再被 shell 解析：

- ❌ `scale='min(1080,iw)':...` — 引号变为字面量
- ✅ `scale=1080:-2` — 纯字面参数
- ❌ `force_original_aspect_ratio=decrease` — ffmpeg 7.x 不再支持
- ✅ `scale=1080:-2` / `scale=-2:1080` — 基础语法，全版本兼容

### 日志文件

每次启动在 `filesDir/logs/vtl_yyyyMMdd_HHmmss.log` 创建日志。每条日志 `flush()` 到磁盘，崩溃不丢失。

---

## 编码策略

### 三级自动降级

```
1. GPU MediaCodec (Buffer 模式)
   → 硬件 H.264 编码，1-3 秒完成
   ↓ 失败
2. 流拷贝 -c:v copy
   → H.264 源无需重编码，秒级
   ↓ 失败或体积过大 (>5MB/s)
3. CPU libx264 veryfast
   → 软件兜底，兼容一切格式
```

### GPU 编码 (GpuConverter)

```
MediaExtractor → MediaCodec 解码 → ByteBuffer → MediaCodec 编码(H.264) → MediaMuxer → output.mp4
```

- 解码和编码都用硬件加速
- Buffer 模式（非 Surface 直连），兼容性优先
- Dolby Vision 轨道自动跳过，选实际 HEVC 轨道
- 码率由画质模式决定：6/15/25/40 Mbps

### 为什么 h264_mediacodec CLI 不能用

| 方案 | 结论 |
|------|------|
| `h264_mediacodec` ffmpeg CLI | ❌ 需要 Android Surface 上下文，后台进程无此上下文 → 卡死 |
| `h264_v4l2m2m` | ❌ 需要 `/dev/video*` 权限，普通 app 无权限 |
| Java `MediaCodec` API | ✅ 当前 GpuConverter 用的就是这个 |

### 速度优化清单

- ✅ 音频流拷贝 `-c:a copy`，失败才 AAC
- ✅ `-preset veryfast` 平衡速度与画质
- ✅ `-tune zerolatency` 禁用 lookahead
- ✅ `-r 30` 限制帧率
- ✅ 封面 `-ss` input seeking
- ✅ >1080p 自动缩放
- ✅ 同名避免时间戳唯一文件名

---

## 画质模式

4 档滑块，同时控制 GPU 码率和 CPU CRF：

| 模式 | GPU 码率 | CPU CRF | 10s 视频预估 |
|------|---------|---------|------------|
| ⚡极速 | 6 Mbps | 30 | <1s |
| 👍推荐 | 15 Mbps | 25 | ~5s (默认) |
| 🎯高清 | 25 Mbps | 20 | ~15s |
| 💎无损 | 40 Mbps | 15 | ~30s |

---

## 关键 BUG 与修复记录

### 1. AGP 8.2.0 + JDK 21 jlink 崩溃
→ 升级 AGP 8.5.2 + Gradle 8.13

### 2. ffmpeg 无 libx264 编码器
→ 换 GPL 版 ffmpeg

### 3. Android 10+ 二进制执行 Permission denied
→ `/system/bin/linker64` 加载

### 4. scale 滤镜 Invalid argument
→ 去掉单引号，用 `scale=1080:-2` 基础语法

### 5. 大视频无法播放
→ XMP `Item:Length` 从 0 改为实际字节数

### 6. XMP EOI 位置 → 无法识别 Live Photo
→ 回滚到 SOS 之前注入

### 7. 同名文件 EEXIST
→ 文件名加入 HHmmss 时间戳

### 8. 实时日志丢失
→ `fromFile()` 异步化 + 文件直写 flush

### 9. 相册不刷新
→ `MediaScannerConnection.scanFile()` + broadcast 双重触发

### 10. GPU 编码 Dolby Vision 0 帧
→ 跳过 `video/dolby-vision` 轨道，选 HEVC 轨道

### 11. GPU Surface 直连 0 输出
→ 改用 Buffer 模式（解码→ByteBuffer→编码）

### 12. GPU Buffer 模式 BufferOverflow
→ GPU 不缩放，编码器匹配解码器分辨率

### 13. 杜比视界色彩偏灰
→ 缺 `zscale`/`tonemap` 滤镜，暂未修复。选视频时弹窗提示用剪映

### 14. 前台服务通知不弹横幅 (小米 HyperOS)
→ 系统策略限制，Toast 提示用户查看通知栏

---

## 版本自动递增

```groovy
def vc = ((System.currentTimeMillis() / 60000) - 29600000).toInteger()
versionCode vc
```

---

## HDR/Dolby Vision 状态

| 项目 | 状态 |
|------|------|
| 色彩映射 | ❌ 缺 zscale/tonemap 滤镜 |
| GPU 兼容 | ❌ MediaCodec 无色彩处理 |
| 用户提示 | ✅ 选视频时弹窗建议用剪映 |
| 速度 | ✅ 正常 CPU 编码速度 |

---

## 后续方向

1. **带 zscale/tonemap 的 ffmpeg 编译版** — 解决 HDR 色彩
2. **MediaCodec OpenGL 色彩转换** — GPU 路径支持 HDR
3. **超级岛接入** — 需上架小米商店后申请
4. **后台转换队列** — 多视频排队

---

*最后更新: 2026-07-28*
