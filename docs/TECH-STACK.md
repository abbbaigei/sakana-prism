# Sakana Prism · 技术栈规划

> 适用：Salt Player for Windows 工坊插件（`.spmod`）与同源独立应用（`SakanaPrism.exe`）
> 版本基线：插件 0.6.1 / 独立应用 0.6.1

本文回答三件事：**现在用了什么、为什么用它、下一阶段往哪走**。

---

## 一、约束先行

| 约束 | 影响 |
|---|---|
| 插件运行在宿主的 JVM 进程内（Salt Player 1.19.x，JBR 25） | 语言必须是 JVM 系；不能起独立进程；不能打包自己的运行时 |
| 宿主通过 `spw-api.jar` 暴露能力（2.2 MB，内含 shaded JNA） | 所有宿主交互（UI hook、配置、按键、权限）都绕不开它 |
| 插件包体要小（工坊分发） | 不引入大依赖；音频解码复用宿主的 BASS |
| 独立版要“双击即用” | 需要 jpackage 打包自带运行时 |
| 实时可视化，60 fps | 渲染必须走 GPU 友好的批量接口，不能逐元素绘制 |

---

## 二、技术栈总览

| 层 | 选型 | 版本 | 为什么是它 |
|---|---|---|---|
| 语言 | **Kotlin** | 2.4.20 | 空安全、协程、与 Compose 同生态；宿主本身是 Compose 应用 |
| 目标 JVM | JVM 25（`jvmToolchain(25)`） | JDK 25 | 与宿主运行时一致，可直接用宿主的 BASS 原生库 |
| UI 框架 | **Compose Multiplatform Desktop** | 1.10.0 | 与宿主同一套 UI 技术栈，插件可直接挂在宿主的组合树上；Canvas 直通 Skia |
| 渲染后端 | **Skia**（经 Compose） | skiko 0.9.37.3 | `drawVertices` / `drawPoints` 批绘制；顶点色插值；无自建纹理 |
| 音频解码 | **BASS**（`bass.dll` 等） | 2.4.18 | 宿主自带、格式覆盖广（flac/mp3/m4a/opus/dsd…）；JNA 直接调 |
| 原生桥 | **JNA**（随 spw-api 提供） | shaded | 不引入额外依赖即可调 Win32/BASS |
| 宿主集成 | **spw-api** | 随宿主 | 字节码 hook（CLASS_TRANSFORM）、UI 注入、配置、按键绑定 |
| 构建 | **Gradle** + Kotlin/Compose 插件 | Gradle 9.6.0 | 官方组合；`jar` 产插件、`jpackage` 产独立应用 |
| 打包（插件） | 自定义 `package.ps1` | — | 生成 `META-INF/MANIFEST.MF` + `classes/**` 的 `.spmod`（zip） |
| 打包（独立） | **jpackage**（AppImage / MSI） | JDK 25 | 自带运行时、单文件安装包 |
| 持久化（插件） | 宿主 `ConfigHelper` | — | 配置由宿主统一存放，随插件卸载清理 |
| 持久化（独立） | 属性文件 | — | 独立版不依赖宿主 API |
| 国际化 | 自建 `Strings` 表 | — | 9 种语言、按 Windows 语言自动选择，无需资源编译器 |
| 诊断 | 自建日志导出 + probe 主类 | — | 用户一键导出环境/性能快照；`gradlew probe` 跑单点验证 |

### 依赖规模（实测）

| 项 | 数值 |
|---|---|
| 插件 `.spmod` | ≈ 424 KB（101 个文件） |
| 独立应用目录 | ≈ 129 MB（其中 `runtime/` 约 90 MB） |
| MSI 安装包 | ≈ 63 MB |
| spw-api.jar | 2.2 MB（仅编译期 + 运行时由宿主提供） |

---

## 三、模块划分

```
dev.sakana.spw.prism
├── MainPlugin              插件入口：权限检查、按键注册、页面注入
├── host/                   宿主耦合层（唯一的“脏”边界）
│   ├── HostUiIntegration   字节码 hook 的安装与失败回退
│   ├── SpectrumPageHook    把页面挂到宿主内容区
│   ├── SpectrumNavigationHook / TitlebarEntry / OutsideClickGate
│   └── SpectrumPlayback    读宿主当前曲目与播放进度
├── audio/                  与 UI 无关的音频分析
│   ├── BassNative / BassPaths / BassBootstrap   JNA 绑定与原生库定位
│   ├── SpectrumAnalyzer    16384 点主 FFT + 512 点对数网格
│   ├── LowBandAnalyzer     32768 点低频 FFT（独立线程）
│   ├── SpectrumLayout      20 Hz – 20 kHz 对数坐标（唯一定义处）
│   └── LoudnessTags        ReplayGain / R128 标签读取
├── preview/                SpectrumController：分析线程 + 显示线程
├── ui/                     纯 Compose，可在插件与独立版之间复用
│   ├── SpectrumDesign      页面骨架 + 设置栏
│   ├── SpectrumPanel       柱状 / 曲线 / 镜像 / 声谱图
│   ├── Spectrogram3D       三角网格 + 脊线 + 辉光
│   ├── SoundFieldPanel     极坐标粒子 / 电平 / 李萨如 / Levels
│   └── RenderMetrics       实时 fps 与耗时
├── settings/               配置项（含持久化 key 与默认值）
├── i18n/                   语言检测 + 文案表
├── diagnostics/            日志导出
└── app/                    独立应用：StandalonePlayer / StandaloneApp
```

**关键原则**：`ui/` 与 `audio/` 不允许 import `host/`。宿主耦合只出现在 `host/` 和 `MainPlugin`，这样独立应用才能复用同一套界面。

---

## 四、性能预算（当前实测）

| 指标 | 目标 | 现状 |
|---|---|---|
| 帧率 | 60 fps | 60（独立版 footer 实测） |
| 单帧绘制 | < 2 ms | 0.4 – 1.5 ms |
| 单帧分析 | < 1 ms | 0.5 ms |
| 进程 CPU | < 60% 单核 | 58%（60 fps，频谱页） |
| 声场页 CPU | — | 44%（60 fps） |
| 内存 | < 200 MB | 独立版约 230 MB 工作集 |

已经踩过并解决的坑（写进 HANDOFF，避免重犯）：

1. **逐元素绘制**：声谱图从“每条频带一个填充路径”改成一次 `drawVertices`；粒子从“每点 `drawCircle`”改成按亮度分桶的 `drawPoints`。
2. **帧等待**：`onSpinWait` 空转 4 ms/帧 ≈ 半核；`parkNanos` 在 Windows 粒度 15.6 ms 会把 60 fps 打到 16 fps；最终用 `sleep(剩余-2ms) + 自旋`。
3. **分析位置**：给分析器的播放位置必须是 BASS 的精确值，不能用 80 ms 刷新一次的 UI 缓存。
4. **隐藏视图**：`AnalysisScope.spectrumVisible / fieldVisible` 让不可见视图不计算。

---

## 五、授权与合规

| 组件 | 授权 | 说明 |
|---|---|---|
| 本项目代码 | MIT | 仓库内 `LICENSE` |
| BASS | 个人/免费软件免费；**商业需购买** | 分发独立应用时要注意；插件形态下 BASS 由宿主提供，不含在我们的包内 |
| Compose Multiplatform / Skia | Apache 2.0 | 可随应用分发 |
| 宿主 spw-api | 随宿主 | 仅编译期引用，不打包进插件 |

---

## 六、已知风险与备选

| 风险 | 现状 | 备选 |
|---|---|---|
| 宿主 API 标注 experimental，升级可能破坏 hook | 用 `@file:OptIn` 包裹；hook 失败会 toast 并降级 | 监控宿主版本，把 hook 点集中在 `host/` |
| BASS 商用授权 | 独立应用依赖 BASS | 换 FFmpeg（LGPL 需动态链接）或 platform decoder |
| Skia 无 GPU 时退回软件渲染 | 虚拟显卡环境下掉帧 | 检测后自动降质（已留 `SpectrogramGlow` 手动挡位） |
| Windows 非 ASCII 路径 | `jlink` 读 args 文件按本地代码页解码 → 乱码目录 | 打包固定用 ASCII 路径 `C:\spwbuild` |
| 未签名 exe | SmartScreen 拦截 | 购入代码签名证书 |
| 阿拉伯语 RTL | 只翻译文字，布局仍 LTR | Canvas 用的是绝对坐标，真正 RTL 需单独适配 |

---

## 七、演进路线

**近期（0.6.x）**
- 独立版记住上次音量/目录/视图（配置文件）
- 声场粒子尺寸与用户设置项打通（现在是常量）
- CI：编译 + probe 冒烟（无 GPU 环境用离屏渲染）

**中期**
- 「系统输出捕获」模式（`basswasapi` loopback），不选文件也能可视化
- 自适应画质：按实测绘制耗时自动降网格密度/关闭辉光
- 安装包签名 + 自动更新检查（仅提示，不自动下载）

**长期**
- 若宿主开放更稳定的插件 API，把 `host/` 的字节码 hook 换成官方扩展点
- 音频后端抽象层（BASS / FFmpeg 二选一），摆脱单一授权依赖

---

## 八、开发环境清单

| 项 | 要求 |
|---|---|
| JDK | 25（编译与 jpackage）；Android Studio 的 JBR 缺 `jpackage.exe`，打包需完整 JDK |
| Gradle | 用仓库自带 wrapper（9.6.0），不要用系统 Gradle |
| 图像转换 | ImageMagick（SVG → PNG/ICO） |
| Python | 仅 `tools/*.py` 的映射验证脚本需要（Pillow） |
| 常用命令 | `gradlew compileKotlin` / `gradlew probe -PprobeMain=...` / `package.ps1 -Bump -Install` / `gradlew createDistributable`（在 ASCII 路径下） |
