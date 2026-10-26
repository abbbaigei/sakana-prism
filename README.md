# Sakana Prism / 鱼棱镜

Salt Player for Windows 的实时频谱与立体声声场可视化插件。

- 创意工坊：<https://steamcommunity.com/sharedfiles/filedetails/?id=3827607898>
- 插件 ID：`dev.sakana.spw.prism`
- 许可证：MIT（见 [LICENSE](LICENSE)）

> Sakana = 鱼。名字里有鱼，也有把声音拆成频谱和声场的意思。

## 功能

**频谱**

- 16384 点主 FFT；低频单独用 32768 点长窗分析，再与高频平滑融合
- 内部按 512 个对数频点划分（20 Hz – 20 kHz），显示柱数可选 64 / 96 / 128 / 160
- 四种画法：柱状、曲线、镜像、瀑布
- 纵轴 0 ~ -96 dBFS，每 12 dB 一格

**声场**

- 每帧取 768 对真实 L/R PCM 样本
- 极坐标：真实样本散点 + 拖尾；电平是 96 段峰值包络 + 缓降轮廓线（固定 60Hz）
- 李萨如：等比例 XY，单声道落在 45°
- 电平表：L/R 峰值、峰值保持、RMS

**其他**

- 封面自动取色：FLAC、MP3(ID3)、M4A 的内嵌封面都能读，没有内嵌图就用同目录封面图
- 只读封面所在的那一段字节，切歌不会把整首曲子读进内存
- 帧率 30 / 60 / 自动；峰值保持时长与回落速度可调
- 入口是标题栏的鱼图标，快捷键 `Ctrl+Shift+S` 也能开关
- 切歌不退出页面；点侧栏或页面外空白处退出

## 数据说明

声场散点来自解码后的 PCM；极坐标位置、颜色和透明度是可视化映射。
相关性、宽度和峰值是当前分析窗口的统计值，**不是声学测量、不是 SPL、也不是人头麦克风采集**，请当视觉参考用。

## 安装

订阅创意工坊条目，然后在 Salt Player 的创意工坊页启用、授予权限、重启播放器即可。
也可以本地导入自己打的 `.spmod`。

## 构建

需要：

- JDK 25（用 Android Studio 自带的 JBR 也行）
- 网络（Gradle 会拉 Kotlin 2.4.20 / Compose Multiplatform 1.10.0）
- `sdk/spw-api.jar`：Salt Player 创意工坊 API，**仓库不提供**。可从官方的
  [spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 项目或本地播放器里取，放到 `sdk/spw-api.jar`。
  （`sdk/` 整个目录都在 `.gitignore` 里，不要提交。）

仓库自带 Gradle Wrapper，不用自己装 Gradle；第一次构建会自动下载 9.6.0。
`build.ps1` / `package.ps1` 只是对 wrapper 的封装，也可以直接用 `.\gradlew.bat`。

```powershell
# 构建 jar
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'   # 换成你的 JDK 25
.\build.ps1                 # -> build\libs\sakana-prism-<version>.jar

# 打包成 .spmod（默认输出到 dist\<version>\）
.\package.ps1

# 升补丁版本 + 出包 + 装进本机 Salt Player 并重启
.\package.ps1 -Bump -Install
```

`package.ps1` 常用开关：

| 开关 | 作用 |
| --- | --- |
| `-Bump` | 补丁号 +1，同时改 `build.gradle.kts` 与 `plugin.properties` 并重新构建 |
| `-Version 0.5.0` | 指定版本（不自动加一） |
| `-OutputRoot <dir>` | 换发布目录 |
| `-NoBuild` / `-NoInstall` / `-NoRestart` | 只出包 / 不安装 / 装了但不重启播放器 |

想固定自己的发布目录，就在仓库根目录建一个 `local.settings.ps1`（已被 git 忽略）：

```powershell
$OutputRoot = 'D:\releases\Sakana Prism'
```

## 目录结构

```text
src/main/kotlin/dev/sakana/spw/prism/
├─ audio/     BASS 解码、FFT、低频分析、频点布局
├─ host/      宿主 UI 的结构化发现、页面与标题栏 hook、播放状态
├─ settings/  配置、主题、图表模式
├─ ui/        频谱与声场绘制、封面取色
├─ preview/   独立预览窗口（不依赖播放器）
└─ tools/     离屏诊断：封面解析、图标渲染
tools/        Python 辅助脚本（封面提取、MP4 探测、商店封面生成）
assets/       Logo 母版与商店封面
```

## 调试

```powershell
# 封面解析自检：读一个路径列表，打印每个音频文件解析到的取色
.\gradlew.bat probe -PprobeArgs="C:\path\to\list.txt"

# 图标各尺寸离屏渲染（看清 20dp 下的实际效果），也接受任意 svg 路径
.\gradlew.bat probe -PprobeMain=dev.sakana.spw.prism.tools.IconProbe -PprobeArgs="src/main/resources/icons/prism-entry.svg"
```

## 第三方与致谢

见 [THIRD-PARTY.md](THIRD-PARTY.md)。宿主 UI 的结构化发现方法改编自 SPW-Listen-Stats（MIT, Jethuit），
其思路又源自 SPW-Lyrics（MIT, GaBoron）。

插件只读取曲库路径、在宿主 UI 上挂载 Compose hook，不检查也不修改播放器的音频内核。

## English

Sakana Prism is a realtime spectrum and stereo sound-field visualizer plugin for
[Salt Player for Windows](https://store.steampowered.com/app/3009140/).

Spectrum: 16384-point FFT plus a separate 32768-point low band, 512 log-spaced bins
(20 Hz – 20 kHz), 64/96/128/160 display bars, four styles (bars, line, mirror, waterfall).

Sound field: 768 real L/R PCM pairs per frame — polar scatter with trails, a 96-bin peak
envelope with a slow-fall contour, Lissajous XY, and peak / hold / RMS meters.

Colours follow the embedded cover art (FLAC, MP3/ID3, M4A) or a sidecar image.

Note: this is a visualiser. Everything is derived from the decoded PCM of the current
analysis window — it is not an acoustic measurement, SPL meter, or binaural capture.

MIT licensed.
