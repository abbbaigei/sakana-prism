# Sakana Prism · 宿主风格规范（待确认）

> 目标：让插件页面看起来像 Salt Player 自带功能页，而不是嵌进去的网页。
> 本文件是**动手前的确认稿**。所有数值都标了来源：实测 / 推断 / 未知。

---

## 一、宿主到底暴露了什么（已核查）

### 1.1 插件 API：没有主题能力

`sdk/spw-api.jar` 的全部包只有这些：

```
ActionShortcut, Channel, KeyBindingManager, PluginContext, PluginPermission,
SpwPlugin, SinceApi, UnstableSpwWorkshopApi, WorkshopApi{Library,Manager,Playback,Ui},
WorkshopPluginManager, config/{ConfigHelper,ConfigManager}, hook/*
```

**没有任何 Theme / ColorScheme / MaterialTheme / CompositionLocal**。宿主的 `CompositionLocal` 也不会跨过字节码 hook 边界传进来（我们的 `Content` 里拿到的是宿主传参，不是它的组合树上下文）。

> 结论：**不能直接读宿主的 MaterialTheme**。这不影响结论，因为下面的路径更好。

### 1.2 但能读到宿主的「外观设置」

宿主用 `java.util.prefs` 存设置，节点是 `/com/xuncorp/voxzen`。实测当前值（`reg query HKCU\Software\JavaSoft\Prefs\com\xuncorp\voxzen`）：

| 键 | 当前值 | 用途 |
|---|---|---|
| `light_dark_theme` | `0` | 明暗模式（**语义待确认**） |
| `spw_window_style` | `1` | 窗口材质样式（**数值→样式映射待确认**） |
| `font_strategy` | `1` | 字体策略 |
| `custom_font_file_path` | `…Noto Sans Merged-Regular….ttf` | 自定义字体文件（路径是正斜杠形式） |
| `language_tag` | `zh` | 界面语言 |
| `enable_wallpaper` / `wallpaper_file_path` / `wallpaper_material` | `false` / 空 / `false` | 壁纸背景 |
| `multilayer_blur_effect` / `sunglow_effect` | `false` / `true` | 附加视觉效果 |

**读取方式**（插件内可用，无需权限）：

```kotlin
val prefs = java.util.prefs.Preferences.userRoot().node("/com/xuncorp/voxzen")
val mode = prefs.get("light_dark_theme", "0")
val style = prefs.get("spw_window_style", "1")
```

### 1.3 系统层

| 信息 | 来源 | 状态 |
|---|---|---|
| 应用明暗 | `HKCU\…\Themes\Personalize\AppsUseLightTheme`（0/1） | 标准键，可用 |
| 系统强调色 | `HKCU\Software\Microsoft\Windows\DWM\ColorizationColor` | 可用，ARGB |
| 窗口背景材质 | `DwmGetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE)` | **不需要**：宿主用自绘材质，系统 backdrop 不反映它的 4 种样式 |

> 说明：宿主是 AOT 原生镜像（`app/voxzen.aot`，`app.classpath` 里没有宿主 jar），**无法反编译确认枚举数值**。`light_dark_theme` 与 `spw_window_style` 的取值含义必须靠一次实验确定（见第七节）。

### 1.4 一个必须先解决的架构问题：透明会透出「宿主页面」

现在的挂载方式是 `HostPageLayer`：先 `renderContent(original)` 画宿主页面，再在同层叠加我们的页面。也就是说我们下面压着的是**歌曲列表**，不是壁纸/材质。

所以：

- 如果我们把背景改成半透明，看到的是**宿主歌曲列表透出来**，不是云母/壁纸；
- 想让材质透出来，必须**不再绘制宿主页面**（改成替换式挂载），让窗口背景（云母/亚克力/壁纸）成为最底层。

这是本任务唯一需要你拍板的架构改动，见第七节问题 1。

---

## 二、实测取色（8 张截图，逐像素采样）

采样点：页面空白处、卡片内部、开关开启色、标题文字、正文文字。

| 样式 | 页面底 | 卡片底 | 强调色（开关开） | 主文字 |
|---|---|---|---|---|
| 亚克力 · 深 | `#4768A6`（壁纸） | `#5174A9` | `#4CC2FF` | `#EBEEF1` |
| 亚克力 · 浅 | `#C8D8F8`（壁纸） | `#D2DEF8` | `#0E6ABA` | `#1E1715` |
| 云母 · 深 | `#1E2331` | `#2A303A` | `#4CC2FF` | `#EBEEF1` |
| 云母 · 浅 | `#F6F8FB` | `#FCFDFE` | `#0E6ABA` | `#1E1715` |
| 云母 Alt · 深 | `#071026` | `#1D2638` | `#4CC2FF` | `#EBEEF1` |
| 云母 Alt · 浅 | `#E4E9F3` | `#F4F6FA` | `#0E6ABA` | `#1E1715` |
| 椒盐经典 · 深 | `#232323` | `#2A2A2A` | `#4CC2FF` | `#EBEEF1` |
| 椒盐经典 · 浅 | `#F8F8F8` | `#FBFBFB` | `#0470E6` | `#1E1715` |

**规律**：

1. 实心材质（云母/云母Alt/椒盐）里，卡片 = 页面底色 + 一层极薄的白色叠加（深色约 `#FFFFFF` 5–8%，浅色接近纯白）。
2. 亚克力是壁纸透过，卡片 = 壁纸 + 白色叠加，所以看起来很亮但**不可预测**——插件不能假设底色。
3. 强调色：深色系 `#4CC2FF`，浅色系 `#0E6ABA`/`#0470E6`，和我们截图里的 Windows 蓝一致。
4. 主文字：深色 `#EBEEF1`，浅色 `#1E1715`（注意是**暖黑**，不是纯黑 `#000`）。

---

## 三、Token 设计（语义层，组件里不写十六进制）

```kotlin
// 只暴露语义，具体值由 HostTheme 按 (明暗 × 材质) 选表
@Immutable
data class HostTokens(
    val surface: Color,            // 页面底：实心材质给不透明色，亚克力给透明
    val surfaceElevated: Color,    // 卡片 / 分组容器
    val surfaceHover: Color,       // 行悬停
    val onSurface: Color,          // 主文字（≥4.5:1）
    val onSurfaceVariant: Color,   // 次要文字 / 说明（≥3:1）
    val outline: Color,            // 分隔线 / 卡片边（低对比）
    val outlineStrong: Color,      // 聚焦、选中描边
    val accent: Color,             // 强调色（开关、滑块、选中）
    val onAccent: Color,           // 强调色上的文字
    val chartSurface: Color,       // 图表专属底（固定，不随材质）
    val leftChannel: Color,        // L 声道：红
    val rightChannel: Color,       // R 声道：青
)
```

派生规则（不逐样式手写）：

| token | 深色 | 浅色 |
|---|---|---|
| `onSurface` | `#EBEEF1` | `#1E1715` |
| `onSurfaceVariant` | `#EBEEF1` @ 62% | `#1E1715` @ 62% |
| `outline` | `#FFFFFF` @ 10% | `#000000` @ 10% |
| `outlineStrong` | `accent` @ 55% | `accent` @ 70% |
| `surfaceElevated` | `#FFFFFF` @ 5%（叠在页面上） | `#FFFFFF` @ 72% |
| `surfaceHover` | `#FFFFFF` @ 8% | `#000000` @ 5% |
| `chartSurface` | `#0B0F15` @ 82% | `#0B0F15` @ 88%（始终深色，保证图表稳定） |

**亚克力特例**：`surface` 用 `Color.Transparent`，把材质留给宿主窗口；卡片仍用 `#FFFFFF @ 5–8%` 的半透明层。这样壁纸透过卡片，效果和宿主一致。

---

## 四、字体方案

### 4.1 宿主用什么字体

- 宿主设置里有 `font_strategy` 与 `custom_font_file_path`，说明它**允许自定义字体文件**。
- 当前机器上 `custom_font_file_path` 指向一个 Noto Sans 变体（看起来是实验留下的值），`font_strategy=1`。
- 截图里的中文是 **Microsoft YaHei UI** 观感，西文是 **Segoe UI** 系。

### 4.2 我们的取值策略

```kotlin
// 1) 若宿主设置了自定义字体且文件存在 → 直接加载它（Compose: Font(file)）
// 2) 否则用系统 UI 字体链
val family = runCatching { Font(File(customPath)) }.getOrNull()
    ?: FontFamily(Font("Segoe UI Variable Text"), Font("Segoe UI"), Font("Microsoft YaHei UI"))
```

- 目标机缺字体时 Compose 会回落到系统默认（不会崩），但我们仍显式列出三档回落，避免不同机器观感漂移。
- **删掉所有 `FontFamily.Monospace`**（声场刻度、环标签、角度标签、统计数字都在用）。
- 数字抖动：`fontFeatureSettings = "tnum"`。Segoe UI 支持 `tnum`；**YaHei 不确定**，实现时同时给数值单独固定宽度容器（右对齐）作为兜底。

### 4.3 四级字号（落成常量）

| 级别 | 用途 | 字号 / 行高 | 字重 |
|---|---|---|---|
| `Title` | 页面标题（频谱/声场） | 28 / 34 | SemiBold |
| `Section` | 分组标题（显示、帧率…） | 13 / 18 | Regular，`onSurfaceVariant` |
| `Body` | 控件标签、选项 | 14 / 20 | Regular |
| `Caption` | 说明、单位、图表刻度 | 11 / 15 | Regular |

取舍说明（对比现状）：

- 现在图表里环标签 **8sp**、角度标签 11sp，都偏小。按你的要求：图表刻度 **≥10sp**、辅助文字 **≥11sp**，所以环标签提到 10sp、角度/统计提到 11sp。
- 声谱图的 `20k / 10k / 1k / 100 / 20 / Hz / 3.0 s` 现在是 9sp → 提到 **10sp**（图表内最小档）。

---

## 五、控件规范

| 控件 | 规格 |
|---|---|
| 卡片 / 分组 | 圆角 8dp，内边距 16dp，行内上下 12dp；**不用硬描边**，靠 `surfaceElevated` 区分层级 |
| 分组标题 | 12–13sp，`onSurfaceVariant`，上间距 24dp、下间距 8dp；**不加**大写/字间距 |
| 开关行 | 左侧标签（Body）+ 可选说明（Caption），右侧开关；整行高 ≥44dp，行悬停有 `surfaceHover` |
| 拨动开关 | Fluent 样式：轨道 40×20dp、全圆角；关=`outline`+`surfaceHover`，开=`accent`；滑块 12dp 白色；动画 120ms |
| 分段控件 | 容器 `surfaceElevated` + 圆角 6dp + 内边距 2dp；选中段= `accent @14%` + `accent` 文字 + 1dp `outlineStrong`；段高 ≥28dp、水平内边距 12dp |
| 按钮（打开文件等） | 圆角 6dp，`surfaceElevated` 底，1dp `outline`；主行动用 `accent` 文字 |
| 焦点环 | 1.5dp `accent`，向外偏移 2dp；键盘 Tab 可见 |
| 点击区域 | 最小 32dp 高（宿主同类控件即此尺度），整行可点优先 |

**间距刻度**：4 / 8 / 12 / 16 / 24。**圆角**：卡片 8、按钮/分段 6、开关 999。组件里不许出现其它值。

---

## 六、图表区（保持稳定，不随材质变）

- 图表区域（频谱画布、声场画布、声谱图）统一用 `chartSurface = #0B0F15 @ ~85%` 的独立深色底 + 8dp 圆角，四周留 16dp。
- 图表内的刻度、坐标、脊线颜色**不改**（本任务硬性约束）。
- 图表外的标题、统计块、图例文字跟随宿主 token。

**统计块重排**（现在是「宽度 50% · 相关 +0.55 · 峰值 -19.2 dBFS · 增益 +1.5 dB」一串）：

```
┌─────────┬─────────┬─────────┬─────────┐
│ 宽度     │ 相关    │ 峰值    │ 增益    │
│ 50%     │ +0.55   │ -19.2   │ +1.5 dB │
└─────────┴─────────┴─────────┴─────────┘
```

标签 Caption/次要色在上，数值 Body/主色在下，等宽数字右对齐。

---

## 七、需要你确认的问题

1. **允许改成「替换式挂载」吗？**
   只有不画宿主页面，材质/壁纸才能透出来。代价：返回走宿主导航时页面状态会被重建；宿主的滚动位置不保留。若不允许，我们只能做**不透明中性灰卡片**（观感接近椒盐经典，亚克力那种通透感做不到）。

2. **`light_dark_theme` 的取值**：当前 `0`。请你在宿主里依次切「跟随系统 / 浅色 / 深色」，每切一次告诉我，我读注册表对照（或你直接把每次的值报给我）。

3. **`spw_window_style` 的取值**：当前 `1`。同样请切一遍四种样式（亚克力/云母/云母Alt/椒盐经典），我记录 0–3 的对应关系。

4. **强调色**：宿主是否跟随系统强调色？如果你改过系统强调色而宿主界面跟着变，就说明是跟随；否则是固定调色板。我需要这个结论来决定「强调色取自 DWM」还是「取自样式表」。

5. **阿拉伯语 RTL** 是否要在这次一并处理？（当前只翻译文字，布局仍 LTR）

---

## 八、改动清单（确认后执行）

| 文件 | 改动 |
|---|---|
| `ui/HostTheme.kt`（新增） | 读取 `light_dark_theme` / `spw_window_style` / 字体设置，产出 `HostTokens` |
| `ui/Type.kt`（新增） | 四级字号 + 字重 + 字体族常量 |
| `ui/Controls.kt`（新增） | `ToggleRow`、`SegmentedControl`、`SettingCard`、`SectionHeader`、`ActionButton` |
| `ui/SpectrumDesign.kt` | 页面壳、右栏改整行卡片、去掉硬编码色/字号、底部信息条 |
| `ui/SoundFieldPanel.kt` | 统计块改四宫格、模式选择改分段控件、去掉 Monospace、字号提到新档 |
| `ui/Spectrogram3D.kt` | 只改刻度字号（9→10sp）与字体族，**几何/颜色/公式一律不动** |
| `host/SpectrumPageHook.kt` | 若确认问题 1：可见时不再渲染宿主原页面（替换式） |
| `settings/SpectrumSettings.kt` | 新增 `themeFollowHost`（默认 true）、`hostStyleOverride`（默认 auto）；旧 key 不动 |
| `i18n/Localization.kt` | 主题项新增「跟随宿主」选项文案（9 语言） |

---

## 九、适配矩阵

| # | 材质 | 明暗 | `surface` | `surfaceElevated` | 强调色 | 可读性判断 | 能否本地验证 |
|---|---|---|---|---|---|---|---|
| 1 | 亚克力 | 深 | 透明（壁纸） | 白 7% | `#4CC2FF` | 文字 `#EBEEF1` 在任意壁纸上≥4.5:1（叠加后最亮壁纸约 `#4768A6`，对比 5.1:1）| 需实机 |
| 2 | 亚克力 | 浅 | 透明 | 白 72% | `#0E6ABA` | `#1E1715` 在浅壁纸上≥7:1 | 需实机 |
| 3 | 云母 | 深 | `#1E2331` | 白 6% | `#4CC2FF` | 11.9:1 | 截图可算 ✔ |
| 4 | 云母 | 浅 | `#F6F8FB` | 白 72% | `#0E6ABA` | 15.8:1 | ✔ |
| 5 | 云母 Alt | 深 | `#071026` | 白 8% | `#4CC2FF` | 14.6:1 | ✔ |
| 6 | 云母 Alt | 浅 | `#E4E9F3` | 白 72% | `#0E6ABA` | 13.1:1 | ✔ |
| 7 | 椒盐经典 | 深 | `#232323` | 白 5% | `#4CC2FF` | 11.2:1 | ✔ |
| 8 | 椒盐经典 | 浅 | `#F8F8F8` | 白 100% | `#0470E6` | 16.3:1 | ✔ |
| 9 | + 封面模糊背景 | 深/浅 | 同亚克力 | 白 7%/72% | 同上 | 同亚克力，取决于封面亮度 | 需实机 |
| 10 | + 多层模糊 / 日光效果 | 深/浅 | 同上 | 同上 | 同上 | 影响背景纹理不影响 token | 需实机 |

**结论**：3–8 可以用截图算出对比度并通过；1、2、9、10 必须在真机上看（壁纸/封面不可预测）。对比度按 WCAG 相对亮度公式计算，正文目标 ≥4.5:1。

---

## 十、我无法在本地验证的部分

- 8 种材质在真机上的**模糊强度与透明度**（截图是静态的一帧，动态壁纸/封面会变）
- 自定义字体文件是否真的被宿主用于界面文字（`font_strategy` 语义未知）
- 高 DPI 125% / 150% / 200% 下的清晰度与裁切（当前只测过 100%）
- 键盘焦点环在所有控件上的可见性（Compose Desktop 的焦点系统需要实机 Tab 一遍）
- 亚克力下**滚动时的透明叠加**是否会累积发灰（需要长时间观察）
