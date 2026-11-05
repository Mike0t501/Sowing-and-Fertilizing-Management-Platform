# 播深检测节点接入变更摘要

实施：2026-11-04，最终界面验收：2026-11-05。分支：`codex/depth-node`。依据：`DEPTH_NODE_ICD.md` v1.0、`播深检测节点接入_ClaudeCode任务指令.md`。实施阶段H0–H9；伺服闭环策略不在范围内。

## 实施结果

| 阶段 | 交付 |
|---|---|
| H0 | 删除独立CANopen编码器21–28的协议、状态、持久化接口、标定/配置/诊断入口、模拟和专用测试；旧帧忽略，施肥和伺服路由保留 |
| H1 | 严格8字节节点协议解析、构帧、CRC与ICD金标准测试 |
| H2 | CAS原子状态、≤10Hz LiveData、独立标定持久化与启动恢复 |
| H3 | 显式路由、STATUS离线判定、seq丢帧、INFO诊断、CFG事务与帧级模拟 |
| H4 | MeasuredDepthFacade统一出口、主界面/播深页实测显示、0.5cm回差与1mm档位 |
| H5 | 2秒采点、中心化一/二次拟合、残差/稳定性、验证、零点、止点、标定JSON |
| H6 | 手动/一键共用21列CSV、真实参数meta及变化历史 |
| H7 | 设置页固定节点管理入口、6种全参数预设、报告周期、研究流预算/互斥、Burn、配号与8行诊断 |
| H8 | SHALLOW/DEEP/LIFT_OFF/UNSTABLE状态机、事件CSV、显示提示与实际记录链仪器测试 |
| H9 | 实施文档第九节、历史章节标注和本摘要 |

新协议、标定、记录、质量、管理实现位于`funClass/DepthNode*.kt`、`DepthCalibrationFit.kt`、`MeasuredDepthFacade.kt`、`DepthQuality*.kt`、`DepthRecordSchema.kt`、`DepthEventSchema.kt`；UI位于`ui/DepthNode*.kt`。详细ID表、路由、交互、阈值和文件格式见`SOWING_DEPTH_IMPLEMENTATION.md`第九节。

## 保留与移除边界

- YZ伺服11–18的CANopen位置反馈、点动停止两阶段、DS402 setpoint bit4、限位、伺服标定和目标决策保留；没有用检测深度修正targetDepth。
- 施肥自定义协议、GNSS/ArcGIS、串口默认值、换算系数、包名/签名/SDK/依赖版本、JAR/native/地图资源未作附带清理。
- 不再读取`enc_N_*`或迁移旧独立编码器标定；必须对传感器、摆臂、变送节点成套重标。节点使用独立`dn_N_*`，无DepthSource或来源切换。
- 保留CLAUDE.md、历史编码器文档/EDS/PDF、源码zip和已有CSV。第八节明确标为历史停用，不能作为当前操作指南。

## 记录格式迁移

新schema为`depth_node_v1`，原始9列不变，尾部追加`ds_online,ds_depth_mm,ds_depth_raw_mm,ds_span_mm,ds_u_filt_mv,ds_u_mean_mv,ds_span_mv,ds_flags,ds_seq,ds_cfg_crc,ds_quality,ds_event`，合计21列。不再有独立编码器的`measured_depth_mm,enc_position,enc_online`或`ds_source`。原始`encoder_position`仍表示伺服内部位置，不能误删。

每份记录配同名`.meta.json`和关联`depthEvt_*.csv`。JSON不列入CSV数据页；历史文件不改写。外部分析程序必须按表头及schema识别。非OK的深度留空、0mm有效；模拟文件标记`simulation=true`，不得混作真实田间结果。

## 测试与平板验证

本地Windows环境可用Android SDK与Gradle Wrapper，已运行`gradlew.bat test`、`gradlew.bat assembleDebug`，并在连接的A10ST Android10平板上运行`connectedAndroidTest`。最终结果：Debug与Release各132项单元测试，3项仪器测试，全部通过，无失败、错误或跳过。APK输出为`app/build/outputs/apk/debug/app-debug.apk`。

自动化覆盖协议逐字节/CRC向量、路由隔离、CAS/持久化、离线/seq回绕/节点重启、配置超时回读/通知隔离/新地址应答、模拟、显示回差、拟合/JSON、预设方向/研究流限制、记录schema、质量事件/距离/目标过渡/零值/坏数据。仪器测试走真实模拟帧接收与六点2秒采集、拟合换算、配置/Burn/配号，以及实际手动记录器和DepthTestCoroutine梯度记录；检查SAF输出21列CSV、meta和腾空事件文件，不启动伺服控制协程。

平板界面实查：设置固定入口、8行管理、参数回读/CRC、研究流预算及多节点二次确认、标定步骤切换、六个挡块点采集/拟合/保存、独立30mm验证、零点复核、止点操作，以及通过Android文件选择器导出并重新导入标定JSON（二次确认）。模拟六点拟合最大残差约0.002mm，独立验证约+0.01mm，仅证明软件模拟链路。截图及读回的QA文件保存在本机忽略目录`build/depth-node-qa/`。检查结束将测试模式恢复为原先关闭状态。

Gradle默认设备测试结束会卸载被测App。发现后已恢复预先备份的原有三个SharedPreferences文件和地图运行资源，并重新授权原Download记录目录；后续测试使用下式保留已安装APK，避免再次清除App数据。公开地图与历史CSV保留。

```powershell
.\gradlew.bat test assembleDebug connectedAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

设备验证使用序列号`YY2026011601006`，包名仍为`com.nx.vfremake.v3`。模拟测试未启用真实作业或伺服主开关。设备墙上时钟当前为2024年，测试文件名和绝对时间反映设备时间；单调计时与持续时间不受影响。未擅自修改系统时钟。

## 实现决策与现有材料差异

1. 任务书工作区提示是历史状态。执行开始检查时工作区已干净，按用户授权从当前提交`bcb5fee`切出`codex/depth-node`并分阶段中文提交，没有丢弃或提交未知改动。
2. 系统方案中的旧多来源切换按2026-11-04任务修订取消；仅接检测节点，保留伺服内部反馈。
3. OUT_OF_RANGE通常不参与统计或事件。按用户确认，标定和方向可信、无FAULT/SAT/SETTLING的浅端STOP只作为腾空事件例外；仍不给出深度数值，不计合格率。
4. 未标定时没有mm/mV斜率，无法在首次采点直接判断0.3mm稳定性。初次拟合后统一检查所有点；有标定斜率时采集即检查。
5. CV默认20%为可修改工程参数，不宣称标准原文规定20%；合格容差和合格率按任务引用值实现，适用标准版本和作物范围须现场核对。
6. 窗口采用有界1cm/10ms分箱保留实际样本数与统计矩，避免长时间静止或高帧率造成无界内存；窗口边界最多多保留一个分箱。
7. 固件目录`D:\dev\dingoFW`仅审查未修改。其当前精简版本固定直出参数及CRC 0xD1，可能拒绝完整滤波预设。App逐项回读并显示失败，不假报成功。模拟器支持完整CFG用于链路测试，不实现或证明节点实际DSP效果。

## ICD问题

未发现需要单方面改帧格式的问题，ICD未修改。母本与固件/App两份副本SHA256均为：

`8DCEACF0DD3BE0552C45B3E0BB272BCC3622C89C5FB18A13B67EC31282F90A77`

注意核心Version命令的build字段按ICD该命令示例使用高字节在前，其余参数数值按小端；已用单元测试固定。固件功能裁剪属于实现能力差异，不以改变ICD掩盖。

## 任务验收对照

| 任务要求 | 实际证据 |
|---|---|
| §3.1–3.2 路由与共享发送 | 生产`classifyRoute`/显式节点分支；CanFrameRoutingTest遍历整个0x680–0x6C7、旧21–28和保留伺服/施肥；CfgClientTest验证四元组、通知隔离、超时回读、新地址应答 |
| §3.3–3.5 数据、质量、唯一出口 | DepthNodeStateTest并发与持久化白名单；RuntimeTest离线、无效值、质量优先级、回绕与重启；统一Facade供显示/记录/服务读取 |
| §3.6 显示 | DepthDisplayFormatTest验证0.5cm回差/档位/零值；主界面与播深页实机截图，实测与伺服换算明确区分 |
| §3.7 标定 | DepthCalibrationFitTest与JSON校验测试；仪器测试六点实际2秒采集；平板表单采集、保存、独立验证、零点、止点成功提示 |
| §3.8 节点管理 | ManagementTest完整9参数、正负斜率、预算、8行关闭、跨节点互斥；CfgClient/Simulator与仪器测试Burn/配号；实机管理及确认弹窗 |
| §3.9 记录 | RecordSchemaTest；实际SAF手动11条与一键26条最新QA记录均21列、schema正确；12列事件CSV含LIFT_OFF，JSON未列入数据页 |
| §3.10 质量监测 | QualityMonitorTest合成序列覆盖持续时间/距离/回差/目标过渡/窗口/CV/坏数据；QualityServiceTest验证实际样本流、STOP方向和记录会话 |
| §3.11 模拟 | SimulatorTest与平板仪器/界面共用STATUS/INFO解析及CFG客户端；模拟与真实标定隔离；研究帧仅计数 |
| §4 H9文档 | 本摘要与SOWING_DEPTH_IMPLEMENTATION第九节；第八节历史停用标注；ICD与CLAUDE原文件保留 |
| §5.1 构建和测试 | 最后完整命令成功，Debug/Release各132项、设备3项全通过 |
| §5.2–3、5.5 页面/移除/CSV | 上述界面与文件证据；源码搜索无独立编码器模块或DepthSource调用；原伺服控制协程/换算/GNSS/ArcGIS与基线逐字未变 |
| §5.4 用户真实节点验收 | 未以平板模拟替代；本任务交付实现和操作步骤，真实节点丢帧率、滤波效果及硬件联动留给下述现场验收 |

最终核对ICD三份哈希一致，固件工作区无改动，App包名/Gradle/SDK未改。平板原有伺服与单体偏好逐键一致，主偏好仅记录目录URI因重新授权改变，仍指向同一Download目录。

## 真实节点现场验收（由用户执行）

1. 备份真实标定，关闭模拟；检查500kbit/s、CSM100T、供电、终端电阻与节点逐行地址。
2. 单个出厂节点接入0x6C0，选空行配号，确认新地址应答、Burn、新INFO和断电重启后地址保持。
3. 完成挡块多点标定与独立验证，检查安装方向和两端STOP；拔线/故障/未标定应显示缺测，不以0代替。
4. STATUS显示与手动/梯度记录一致，实测近10秒丢帧率<0.1%，检查tx_drop和OVERRUN；静止位置不应被误判离线。
5. 使用支持完整滤波的固件逐一应用预设，核对9项实际参数及CRC再Burn；精简直出固件应明确拒绝不支持预设。
6. 先低速研究流确认预算、超时自动关闭和旁路抓包；8行在线必须关闭。真实滤波性能、硬件带宽和伺服动作需现场验证，本次平板模拟测试不替代这些结果。
