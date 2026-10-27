# Steam 创意工坊文案（Sakana Prism）

每次上传直接把下面两段复制进去。标题有长度上限，简介不要写成"测量仪器"。

## 标题（选一个）

推荐：

```
Sakana Prism 鱼棱镜 · 实时频谱与立体声声场
```

备选：

```
Sakana Prism 鱼棱镜 — Salt Player 频谱 / 声场可视化
```

## 简介（中文）

```
Sakana Prism（鱼棱镜）是给 Salt Player for Windows 的实时频谱与立体声声场可视化插件。
Sakana 是鱼，Prism 是棱镜——把声音拆成频谱和声场，正好是它干的事。

【频谱】
· 16384 点主 FFT；低频单独用 32768 点长窗分析，再与高频平滑融合
· 内部按 512 个对数频点划分（20 Hz – 20 kHz），显示柱数可选 64 / 96 / 128 / 160
· 四种画法：柱状、曲线、镜像、声谱图（3D，带五个视角预设）
· 纵轴 0 ~ -96 dBFS，每 12 dB 一格

【声场】
· 每帧取 768 对真实 L/R PCM 样本
· 极坐标：真实样本散点 + 拖尾；电平是 96 段峰值包络 + 缓降轮廓线（固定 60Hz）
· 李萨如：等比例 XY，单声道落在 45°
· 电平表：L/R 峰值、峰值保持、RMS

【其他】
· 封面自动取色：FLAC、MP3(ID3)、M4A 的内嵌封面都能读，没有内嵌图就用同目录封面图
· 帧率 30 / 60 / 自动；峰值保持时长与回落速度可调
· 入口是标题栏那个鱼图标，快捷键 Ctrl+Shift+S 也能开关
· 切歌不退出页面；点侧栏或页面外空白处退出

【关于数据，先说清楚】
声场散点来自解码后的 PCM，极坐标位置、颜色、透明度都是可视化映射。
相关性、宽度、峰值是当前分析窗口的统计值，不是声学测量、不是 SPL、也不是人头麦克风采集——
请当视觉参考，不要拿去当测试仪。

【权限都用在哪】
· 曲库读取：取当前曲目路径，才能解码分析、读取内嵌封面
· UI Hook：把频谱页画进播放器界面
· 快捷键：注册 Ctrl+Shift+S

【安装】
1. 订阅本物品
2. 到 Salt Player 的创意工坊页启用
3. 授予上面三项权限
4. 重启播放器
5. 点标题栏鱼图标进入

【配置要求】
· 最低：Windows 10 64 位 / 双核 2.0GHz / 4GB 内存 / OpenGL 2.0 核显 / 1280×720 / 10MB 空间
· 推荐：Windows 11 22H2 64 位 / 四核 3.0GHz / 8GB 内存 / OpenGL 3.3 或 DX11 显卡 / 1920×1080 / 20MB 空间
· 插件本体约 360KB；开声谱图 60fps 时额外占用约半个逻辑核心，失焦降到 15fps 后约为 1/4
【已知限制】
· 高频是聚合显示，不是逐频点
· 一轨有多张内嵌封面时只取第一张
· Ogg / Opus 的内嵌封面暂未解析
· 不是测量仪器，仅供视觉参考

【源码】
MIT 开源，欢迎提 issue / PR：https://github.com/abbbaigei/sakana-prism

问题、建议、想加的画法，欢迎在评论里说。
```

## English blurb (optional, append after the Chinese text)

```
Sakana Prism — realtime spectrum and stereo sound field visualizer for Salt Player for Windows.

Spectrum: 16384-point FFT with a separate 32768-point low band, 512 log-spaced bins
(20 Hz – 20 kHz), 64/96/128/160 display bars, and four styles (bars, line, mirror, waterfall).

Sound field: 768 real L/R PCM pairs per frame — polar scatter with trails, a 96-bin peak
envelope with a slow-fall contour, Lissajous XY, and peak / hold / RMS meters.

Colours follow the embedded cover art (FLAC, MP3/ID3, M4A) or a sidecar image.

Note: this is a visualiser. The scatter and meters come from the decoded PCM of the current
analysis window; they are not an acoustic measurement, SPL meter, or binaural capture.
```

## 更新时怎么写

- 小改动：标题、简介都不用动，只在「本次更新说明」里写一句，比如
  `0.4.6：删掉频谱页标题行里多余的「关闭」按钮。`
- 有新增功能：简介的对应小节补一条，更新说明里写清楚改了什么
- 版本号不要写进标题或简介，Steam 条目自己会显示更新时间
