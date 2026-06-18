# 代码审查缺陷清单（P0–P4）

> 对象：`com.nx.vfremake` 变量施肥 / 播种深度农机控制器
> 范围：Kotlin / Jetpack Compose / ViewModel / Gradle / 协程 / ArcGIS 控制链路
> 优先级：**P0 止血（崩溃/失控）→ P1 构建 → P2 状态/生命周期 → P3 性能/长稳 → P4 健壮性/清理**
> 状态图例：✅ 已修复并提交　🔲 待处理

本文件汇总历次审查发现的全部缺陷，按落地优先级（P0–P4）组织。每项给出
**涉及文件 / 原因 / 结果 / 修复方案 / 状态**，作为后续迭代的执行清单与回溯依据。

---

## 进度总览

| 优先级 | 主题 | 项数 | 状态 |
|---|---|---|---|
| 现场 | 田间实测两问题（停车空转 / 约10分钟卡死 ANR） | 2 | ✅ `da93f17` |
| P0 | 崩溃 / 失控（安全） | 4 | ✅ `f1b538c` |
| P1 | 构建配置可复现性 | 4 | 🔲 |
| P2 | 状态收敛与生命周期 | 5 | 🔲 |
| P3 | 性能与长时作业稳定性 | 3 | 🔲 |
| P4 | 健壮性与清理 | 7 | 🔲 |

相关提交：
- `da93f17` 停车时单体电机空转不停 + 处方图施肥约10分钟卡死(ANR)
- `f1b538c` 修复 4 个 P0 高风险缺陷（崩溃/失控）

---

## 现场实测问题（已修复 ✅ `da93f17`）

### F1　停车时个别排肥电机（实测 3 号）空转不停 ✅
- **文件**：`funClass/MyArcGisFun.kt`（`dantiPositionAndCtrl` 施肥分支）、`funClass/ConvAndCtrlFun.kt`（`fertToMotorSpeed`）
- **原因**：`forwardSpeed→0` 时 `fertToMotorSpeed` 返回 `-B/A`，某路标定 `B` 与 `A` 异号时为正的低转速；
  且控制下发原本不受车速门控（只有绘制做了门控）。
- **结果**：拖拉机停下后该路电机仍以恒定低速空转；固定前进速度模式下会让全部电机停不下来；停车继续排肥原地堆肥。
- **修复**：施肥分支以真实对地速度 `gnssRawSpeed` 判停，近零速对所有排肥电机发 0 并跳过本帧查询/下发。

### F2　处方图施肥模式跑约 10 分钟后卡死（ANR）✅
- **文件**：`coroutine/DB9reCANseCoroutine.kt`（`onGNSSMsgReceived` 绘制段、`fertGraphicsOverlayExport` 字段）
- **原因**：每条 RMC × 每个开启单体都向 `fertGraphicsOverlayExport` 新增一个多边形且从不裁剪，
  DYNAMIC 模式每帧重绘上万个多边形拖垮渲染线程。
- **结果**：作业约 10 分钟后界面卡死无响应，直至系统提示“已停止运行”。
- **修复**：(a) 绘制按里程抽稀（移动 ≥1.5m 才落笔），多边形数量只与里程相关、与 RMC 频率无关；
  (b) 已施肥图层改用 `GraphicsOverlay.RenderingMode.STATIC`（后台缓存渲染）。

---

## P0　崩溃 / 失控（安全）（已修复 ✅ `f1b538c`）

### H1　参数解析无空安全 → 开机崩溃循环 ✅
- **文件**：`funClass/MySharedPreFun.kt`（`initSettingsParam`）、`funClass/MyGNSSFun.kt`（`forwardSpeedAverageNum`）、
  触发点 `MainActivity.onStart`、输入源 `ui/ParamSettingsScreen.kt`（`TemplatesText_FieldUint`）
- **原因**：6 个 double 参数用裸 `getString(...).toString().toDouble()`，参数页输入框原文落库无校验；
  `rowNumber` 已用安全写法但其余未跟上。
- **结果**：输入 `.`/`-`/`1.2.3`/`12a` 等被持久化 → 下次 `onStart` 调 `initSettingsParam`（未捕获）→
  `NumberFormatException` → **每次启动闪退，崩溃循环**，须清数据才能恢复。
- **修复**：6 个参数改 `?.toDoubleOrNull() ?: 默认`；`forwardSpeedAverageNum` 改 `toIntOrNull() ?: 5` 并 `coerceAtLeast(1)`。

### H2　RMC 解析数组越界 → 控制协程被杀 / 闪退 ✅
- **文件**：`funClass/MyGNSSFun.kt`（`extractAndParseRMC` 守卫）、`coroutine/DB9reCANseCoroutine.kt`（`onGNSSMsgReceived` 调用处）
- **原因**：守卫仅查 `parts.size > 9` 却访问 `parts[10]`(磁偏角)/`parts[11]`(校验和)，需 ≥12 段；
  `start1` 仅捕获 `IOException`。
- **结果**：截断/畸形且状态恰为 A/D 的 RMC（10/11 段）→ `IndexOutOfBoundsException` 逃逸 launched 协程 →
  **控制循环被杀 / App 闪退**。
- **修复**：守卫改 `parts.size >= 12 && (parts[2]=="A"||"D")` 并清理重复条件；两处 `onGNSSMsgReceived`
  调用包 `try/catch(Throwable)`，放行 `CancellationException`，单条坏报文不致死协程。

### H3　电机转速无上限钳位 → 指令字节回绕（安全）✅
- **文件**：`funClass/ConvAndCtrlFun.kt`（`motorSpeedrpmSend`）
- **原因**：仅对 NaN/Inf 归零，无上限；整数转速 `toInt().toUByte()` 编码进 1 字节，超 255 模 256 截断。
- **结果**：大转速（如 300）静默回绕成 44rpm，**给硬件发错误低转速且无报警**。
- **修复**：新增 `MAX_MOTOR_RPM=255`，编码前 `coerceIn(0.0, MAX_MOTOR_RPM)`，超限打 Log。
  （255 为协议上限，仅杜绝回绕；若机械实际上限更低应按机型调小。）

### H4　前后台切换泄漏控制协程 → “停止”失灵（安全）✅
- **文件**：`MainActivity.kt`（`onStart` 重建 `mDB9reCANseCoroutine`）
- **原因**：`onStart` 每次回前台无条件 `= DB9reCANseCoroutine()`，旧实例协程独立于生命周期；
  构造其实不依赖 rowNumber（`polyPointExport` 固定 8）。
- **结果**：运行中切后台再回来 → 旧协程继续发 CAN；字段指向新空实例；UI 仍显示运行中 →
  按“停止”作用到空实例 → **旧协程不停、电机持续转动**。
- **修复**：`onStart` 加 `if (!::mDB9reCANseCoroutine.isInitialized)` 单实例守卫；运行态收敛留待 P2。
- **待产品确认**：作业中切后台期望“继续作业（需前台 Service）”还是“自动停机”。

---

## P1　构建配置可复现性 🔲

### M1　Gradle DSL 错放：`buildFeatures/composeOptions/kotlinOptions` 在 `defaultConfig{}` 内 🔲
- **文件**：`app/build.gradle.kts`
- **原因**：仅因 Kotlin DSL 回退到外层 `android` receiver 才“碰巧能编译”，与 android 级同名块重复。
- **结果**：高度误导、脆弱；未来改动可能绑错接收者或编译失败。
- **修复**：移到 `android{}` 顶层，删除重复块。

### M2　NDK 版本字符串含 “ rc1” 🔲
- **文件**：`app/build.gradle.kts`（`ndkVersion = "27.0.11718014 rc1"`）
- **原因**：非标准版本串。
- **结果**：没装该确切 NDK 目录的人构建失败，可复现性差。
- **修复**：固定到正式版 NDK。

### L1　未使用的重型依赖 🔲
- **文件**：`app/build.gradle.kts`（`play-services-maps`、`androidx.room.ktx`、`runtime-rxjava2`；catalog 中 material3）
- **原因**：源码 grep 无引用。
- **结果**：增大 APK、拖慢构建。
- **修复**：移除未用依赖。

### L3　ArcGIS API Key / Lite License 硬编码进版本库 🔲
- **文件**：`app/build.gradle.kts`（`buildConfigField`）
- **原因**：密钥明文写入并提交。
- **结果**：密钥泄露风险。
- **修复**：移到 `local.properties`/CI secret，并轮换已泄露 key。

---

## P2　状态收敛与生命周期 🔲

### M5　运行状态分散在 4 处易失步 🔲
- **位置**：`isSystemRunning`(全局)、`isRunningState`(compose remember)、各协程 `isRunning`、`watchdogShouldRun`
- **原因/结果**：生命周期/重入事件下相互不一致，导致“显示在跑实际没跑”或反之（与 H4 同源）。
- **修复**：收敛为 ViewModel 单一真源，UI/协程都从它派生。

### M10　海量全局可变状态、跨线程无同步 🔲
- **文件**：`ViewModelAndPublic.kt` 顶层 `var`（`mRmcData`、`mSPParamData`、串口、`fittingCoefficient*`、`canMonitorData`…）
- **原因/结果**：多协程读写、多为非 `@Volatile`/无同步，不随生命周期，难测试；可见性/竞态隐患。
- **修复**：渐进迁入 ViewModel/Repository，明确线程边界（不可变快照 / `@Volatile` / Mutex）。

### M3　`mapView`(lateinit) 先用后初始化 🔲
- **文件**：`MainActivity.kt`（`setContent` 捕获 `mapView` 早于赋值）
- **原因/结果**：仅因 Compose 推迟首帧组合才不崩，潜在 `UninitializedPropertyAccessException`。
- **修复**：把 `mapView = MapView(...)` 移到 `setContent` 之前。

### M8　未托管的 `CoroutineScope(Dispatchers.Main).launch` 🔲
- **文件**：`ui/MainScreen.kt`（“加载渲染”按钮内）
- **原因/结果**：不随 composition 取消（潜在泄漏）；且 `loadShp` 非挂起函数，开协程无必要。
- **修复**：用 `rememberCoroutineScope()` 或直接调用。

### M4　首启资源拷贝 vs 地图加载竞态 🔲
- **文件**：`MainActivity.onCreate`（异步 `copyShpFilesToExternalStorage` 与紧随的 `loadShp`）
- **原因/结果**：首次运行 shp 可能未拷完即加载（当前因路径空返回多半无害，但顺序不稳）。
- **修复**：拷贝完成后再加载，或将拷贝作为加载前置条件。

---

## P3　性能与长时作业稳定性 🔲

### M6　已施肥图层无限增长 🔲
- **文件**：`funClass/MyArcGisFun.kt`(`drawPolyExport`) + `coroutine/DB9reCANseCoroutine.kt`
- **现状**：已通过 F2 的“按里程抽稀 + STATIC 渲染”大幅缓解。
- **遗留**：超长（多小时）连续作业仍会累积，建议后续加周期性合并/抽稀或数量上限。

### M9　热路径反复读 SharedPreferences 🔲
- **文件**：`MyGNSSFun`(每条 RMC)、`MainActivity` 看门狗循环、多个 Composable（每次重组）
- **原因/结果**：频繁 `new MySharedPreFun(context).getSpecificValue(...)`，分配与查表开销。
- **修复**：启动/进入时读一次缓存到内存或 ViewModel，热路径只读内存。

### L5　工具类按调用 new 🔲
- **位置**：全工程 `MyArcGisFun()`、`MySharedPreFun(context)`、`ConvAndCtrlFun()`、`MyGNSSFun()`（含类内自调用）
- **原因/结果**：无状态工具类每次实例化，热路径一次性分配。
- **修复**：改 `object` 或顶层函数。

---

## P4　健壮性与清理 🔲

### M7　测试模式输入 `.toInt()/.toDouble()` 无保护 🔲
- **文件**：`ui/MainScreen.kt`（`MsgScreenRight` 测试发送框 +/- 与发送）
- **结果**：空/非数字 → `NumberFormatException`（仅测试模式可触发）。
- **修复**：改 `toIntOrNull()/toDoubleOrNull()` 带兜底。

### L4　`Double.MIN_VALUE` 作为最大值初值 🔲
- **文件**：`funClass/MyArcGisFun.kt`（`loadShapefile` 分级求极值）
- **原因/结果**：`Double.MIN_VALUE` 是最小正数而非最负；全负数据集/空结果集求极值错误。
- **修复**：用 `-Double.MAX_VALUE`/`NEGATIVE_INFINITY` 并对空结果兜底。

### L7　`generateGradient` steps=1 除零 / `calculateBreaks` max==min 退化 🔲
- **文件**：`funClass/MyArcGisFun.kt`
- **修复**：守卫 `steps>=2`、`range>0`，否则给安全默认。

### L2　release 未开 R8（`isMinifyEnabled = false`）🔲
- **文件**：`app/build.gradle.kts`
- **修复**：开启收缩/混淆（需配套 ArcGIS/serialport keep 规则）。

### L8　用 `Math.random()/Random` 人为美化准确率/流量显示 🔲
- **文件**：`ViewModelAndPublic.kt`(`updateFertData`)、`coroutine/CanReceiveCoroutine.kt`(`onSlaveCanMessageReceived`)
- **原因/结果**：向作业人员显示经修饰的准确率/流量，可能误导。
- **处置**：属产品决策——确认是否保留，或加“演示模式”标识区分真实数据。

### L6　Compose 默认参 `= VariableFertViewModel()` 在系统外 new ViewModel 🔲
- **文件**：`ui/MainScreen.kt`（`MsgScreenRight`/`MsgScreenBottom` 默认参数）
- **修复**：仅 Preview 用，改用 Preview 专用构造路径。

### L9　重复 KDoc / 无意义 `.let{}` 🔲
- **文件**：`ViewModelAndPublic.kt`（重复注释）、`ui/MainScreen.kt`（`overlay.let { invalidate() }` 忽略接收者）
- **修复**：清理。

### L10　`synchronized(outputStream)` 依赖流实例稳定 🔲
- **文件**：`funClass/MySerialPortFun.kt`（`slaveCanMsgSend`）
- **原因/结果**：若串口每次 `getOutputStream()` 返回新包装流，互斥失效。
- **修复**：改为锁一个专用对象。

---

## 验证方式（通用）

- **编译**：`./gradlew compileDebugKotlin`（每步改完先过编译）；`./gradlew assembleDebug` 出包。
- **崩溃类（P0）**：参数页输入非法值后重启不崩；注入 <12 段/畸形 RMC 控制协程不死；超 255rpm 工况抓 CAN 字节确认钳位；
  运行中前后台切换后“停止”确实归零电机。
- **长稳（P3/F2）**：模拟 GNSS 循环模式连续跑 30+ 分钟，观察内存平稳、地图不卡、导出正常。
- **回归**：模拟 GNSS 跑一条航线，核对施肥带绘制、底部柱状图、平均施肥量/准确率、播深控深下发与改前一致。

---

_最后更新：随 `f1b538c` 提交。P0 与现场两问题已闭环；P1–P4 待后续批次处理。_
