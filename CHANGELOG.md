# 更新日志

## v0.8.1 — 2026-10-28

修复旧会话诊断数据残留导致「N 项功能未生效」误报。

### 修复

- 诊断回传：`Report.snapshot` 对本次会话触碰到的每个 Feature 总是发送 `reg` 与 `skip`，缺失时发送空串。`StatusProvider` 对 `reg`/`skip` 以最新值覆盖，空串会清掉上一次目标进程会话遗留的旧 skip；`hit` 仍只在 >0 时发送并取历史最大值，不会被清空。
- 根因：0.8.0 只在值非空时发送 `reg`/`skip`。目标应用重启后，本次没有 skip 的功能不会覆盖旧值，旧会话的 9 条 skip 永久留在 `status_report`，被 `failedFeatureCount` 持续计入，主页面一直显示「9 项功能未生效」。
- 失败判定抽到 `ReportProtocol.featureFailed`，行为不变：已启用、有回传且（`reg` 为空或 `skip` 非空）才计入失败；无回传不计入。

### 验证（自动化、静态与真机，2026-10-28）

- `testDebugUnitTest` 54 项全部通过（0 失败 / 0 跳过）；新增空串覆盖旧 skip、横幅计数归零、旧 hit 保留、`Report.snapshot` 键完整性等 5 项用例。
- `lintDebug` 通过：0 errors / 23 warnings（均为既有 warning）。
- `assembleDebug` / `assembleRelease` 通过。
- `tools/verify-apk.sh` 17 项全部通过：versionCode=13、versionName=0.8.1、签名证书与 APK SHA-256。
- `tools/check-mounts.sh --dexsql E:/tools/dexsql/dexsql.exe` 对小红书 9.51.0 体检 58/58 OK，GONE/SIG=0。
- 真机 25102RKBEC / Android 17 / 小红书 9.51.0：0.8.0 覆盖升级到 0.8.1（versionCode 13）后，冷启动两次均无 FATAL/ANR；旧 9 条 skip 被本次空串覆盖，主页面「9 项功能未生效」横幅消失，诊断页 `skip —`；`mount report sent=true ok=21 miss=0 bad=0`。核心 P0 沿用 0.8.0 已验证结果。

### 已知未覆盖

- AD-03/04/06/07、UI-01~05、PV-01~03、EX-01/02 未触发或未覆盖；AD-09/AD-10 具体带货/投票卡未出现，仅验证 seen 链路；EX-01 真实被删笔记场景未实测；旋转与 1.3 字体未验证。
- 诊断页仍会显示本次会话真实存在的 skip（锚点缺失属于正常信息），横幅只统计本次会话回传。

APK: quietbox-0.8.1.apk
versionCode: 13
大小: 2,900,042 字节
SHA-256: 6b763eeddc75074872392b3f9b9c14df44f0dee1efb347812f6f9f71ea550dfa

## v0.8.0 — 2026-10-24

适配小红书 9.51.0；界面改为底栏 4 Tab；修复保存图片水印入口参数不生效的问题。

### 新增

- 底栏 4 Tab：设置 / 测试 / 诊断 / 关于；原二级页改为平级 Tab，AB 面板并入测试 Tab。
- `tools/release.sh`、`tools/verify-apk.sh`、`tools/preflight.sh` 发布与校验脚本；CI 流水线。
- `tools/check-mounts.sh` 挂载点体检：锚点表新增可选第 5 列参数个数校验（方法还在但签名形状变了判 SIG）。
- 新增测试：prefs/Feature 一致性（`PrefsFeatureConsistencyTest`）、hook 注册表（`HookRegistryTest`）、水印入口参数（`WatermarkHooksTest`）等，单测 28 → 49 项；接入 JaCoCo 覆盖率报告（不设阈值）。

### 变更

- 适配小红书 9.51.0：15 个锚点重定位（旧编号 P0-2 信息流、P1-7 外跳、P1-9 复制链接/剪贴板、P1-11 水印、P1-13 搜索广告、P1-15 已删笔记、P1-21/22/23 卡片过滤、P2-1 ConfigCenter）；旧版本 fail-open 并提示。
- 水印入口修复：9.51.0 保存入口为 11 参 `ce8.i#i`（布尔位 index 5/8/9/10），此前按 `>=12` 判断且写 index 11，改参永不生效；dexsql 反汇编确认 index 5 = disableWaterMark、index 10 = disableAiWaterMark，其余布尔位与水印无关不设。改参逻辑抽为 `applyWatermarkFlags` 纯静态方法并有单测。
- release 签名配置：`signingConfigs.release` 改为条件引用，无 keystore 的 CI 也能跑配置期与 test/lint；release 任务图仍硬失败，不产出 debug 签名或未签名包。
- 构建基线：appcompat 1.8.0、material 1.14.0，JDK 21 构建（字节码仍 Java 17）。

### 验证（自动化、静态与真机，2026-10-27）

- `bash tools/preflight.sh` 通过：`testDebugUnitTest` 49 项全部通过（0 失败 / 0 跳过），`lintDebug` 通过。
- `./gradlew :app:assembleDebug :app:assembleRelease` 通过；release 产物 2,899,950 字节。
- `bash tools/verify-apk.sh app/build/outputs/apk/release/quietbox-0.8.0.apk` 17 项检查全部通过：Xposed 元数据与 scope、单入口、R8 keep 4 类、Manifest、versionCode/versionName、签名证书、APK SHA-256。
- 小红书 9.51.0 挂载点体检 58/58 OK（2026-10-24 对同版本 APK 的记录）。
- 无 keystore 环境：`:app:help`、`:app:testDebugUnitTest :app:lintDebug` 通过，`:app:assembleRelease` 以「release 构建必须使用正式签名」失败。
- 真机 25102RKBEC / Android 17 / 小红书 9.51.0：两次冷启动无 FATAL/ANR；AD-01、AD-02、AD-05、AD-08/09/10、EN-01 保存无水印图片和 4 Tab/诊断/关于均通过；模块 App 存活时 `mount report sent=true ok=21 miss=0 bad=0`。AD-04 外跳与 AD-07 评论带货本轮无自然场景，未覆盖；不据此宣称通过。
- 已知观察：真机诊断页仍显示部分 skip，模块主页出现“9 项功能未生效”横幅；核心 P0 链路不受影响，具体原因待后续排查。

APK: quietbox-0.8.0.apk
versionCode: 12
大小: 2,899,950 字节
SHA-256: cb3735f88b54d8d216e80a495d0c8366e06e0cb178c81945d4dd375e3d8ed788

## v0.7.0 — 2026-10-23

统一界面、诊断与功能编号。历史条目中的旧编号（P0-x / P1-x / P2-x）保留原样，仅作记录。

### 新增

- 单页设置 + 二级页导航：去掉 2 Tab 底栏，主页面为设置页，实验、诊断、关于改为二级页；二级页显示返回箭头，返回后保留主页面滚动位置。
- 诊断页：逐功能显示 `编号 标题 · registered · hit N · skip 原因`，无数据用 `—`；页首显示刷新时间，页尾提供刷新与复制。
- 关于页：模块版本、目标应用、源码、四模块互链（当前模块标注）、免责声明与 MIT 许可证；互链为蓝色可点击链接。
- 诊断回传通道：`HookState` + `hooks/Report` + `StatusProvider`，目标进程只在主进程回传，Provider 校验调用方 UID，键走白名单，value 上限 256 字符，hit 多进程取历史最大值。
- 异常横幅：目标应用版本未适配，或已启用且 ≥2 个功能明确失效时显示；删除 `MISS_THRESHOLD=5`，统一按“功能去重、≥2 功能失效”判断。
- JUnit 4.13.2 单测：`FeatureTest`、`ConfigTest`、`ReportProtocolTest`、`HookStateTest`。

### 变更

- 功能编号统一为 AD/UI/PV/EN/EX：24 项对应 AD-01~11、UI-01~05、PV-01~04、EN-01~02、EX-01~02；remote prefs key、AB key、applicationId、namespace 均未改动。
- 编号映射：P0-1~P0-3 → AD-01~AD-03；P0-4 → UI-04；P0-5 → PV-01；P0-6 → EN-02；P1-7 → AD-04；P1-8 → AD-11；P1-9 → PV-03；P1-10 → PV-04；P1-11 → EN-01；P1-12 → UI-05；P1-13~P1-14 → AD-05~AD-06；P1-15 → EX-01；P1-16~P1-18 → UI-01~UI-03；P1-19 → PV-02；P1-20~P1-23 → AD-07~AD-10；P2-1 → EX-02。
- 默认值从全关改为“净化开、敏感关”：15 项 AD/UI 功能默认开启，PV/EN/EX 与 3 项 blocked 功能默认关闭。仅影响新安装，已保存配置不迁移、不覆盖。
- 设置页按 AD → UI → PV → EN → EX 重新分组；blocked 功能保留编号与条目，但不在设置页渲染。
- 状态卡改为四态（正在连接框架… / 未激活 / 框架已连接 / 已激活）+ detail 行（框架 API、目标版本、remote prefs）。
- 用户可见文案统一进 `strings.xml`，统一无人称，功能标题改为 4–8 字动宾短语。
- 构建基线：AGP 8.12.2 → 8.13.2；compileSdk 37、minSdk 29、targetSdk 36、Gradle 8.13、Java 17 不变；未使用 AAR metadata 豁免。
- release 签名：缺少 `keystore.properties` 或 `storeFile` 未配置时直接失败，不再回退 debug 签名。

### 文档

- README 功能表改用新编号与默认值，补充单页 + 诊断/关于、四模块互链。
- CHANGELOG 新增 0.7.0 段。

### 验证

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 通过。
- 单测 28 项通过。
- 真机 25102RKBEC（Android 17 / LSPosed 2.2.1 / 小红书 9.50.0）：21 个功能、0 miss / 0 bad；诊断页收到 `AD-01 hit 2`、`AD-02 hit 1` 等数据；单页 + 二级页、状态卡四态、关于页四模块互链、AB 面板均正常；无 FATAL/ANR。
- 默认值只影响新安装；本次设备保留已保存配置，未覆盖。

APK: quietbox-0.7.0.apk
versionCode: 11
大小: 2,546,156 字节
SHA-256: f2b0e9c9be167f2675d1633b0fa2aa81c68587ef3528ccd272fae64e0b588e14

## v0.6.1 — 2026-10-20

修复 0.6.0 静态审查发现的问题：

- 复制链接净化：改用原始编码 query 处理，修复含 `%XX`（如 `xsec_token`）的链接静默不净化；全删参数时一并去掉 `?`；日志不再输出剪贴板正文。
- 通讯录同步：补挂 3 参 `requestContactsSync` 重载。
- 内容评价：补挂公开入口 `showContentRatingSnackBarIfNeeded`，防私有方法被内联后失效。
- 开屏：不再跳过 `startAnim`，避免开屏 fragment 卡住。
- 广告外跳：放行 `xhsdiscover://` 等站内 scheme，避免误伤站内深链。
- 信息流卡片：`modelType` 大小写归一；已知类型不匹配时不再用字段兜底，避免误删正常笔记。
- 挂载统计：静默定位失败也会计 miss；统计改为按「功能」去重，横幅仅在 ≥2 个功能失效时显示；推送改到后台线程且失败可重试。
- AB 面板：修复 `refresh()` 只遍历顶层导致状态/摘要不刷新；总开关改值即时生效；provider 拉取加 2 秒超时；观测值变化会重发。
- 文案：修正「修改后立即生效」「默认最高画质」等与实现不符的表述。

### 验证

真机 25102RKBEC（Android 17 / LSPosed 2.2.1 / 小红书 9.50.0）：

- 模块注入成功：67 个 hook、21 个功能，0 miss / 0 bad；`mount report sent=true`。
- AB 面板可打开，总开关开启，精选实验显示「已强制为 true」。
- 复制链接：短链 `https://xhslink.cn/o/...` 原样保留（无追踪参数，符合预期）。
- 冷启动：无卡层，进入主界面，无 FATAL/ANR。
- 直播/带货/投票卡：hook 已注册；当前信息流未出现匹配卡片，未产生 hit。
- P1-15~P1-23：hook 已注册；本次使用未自然触发。

构建信息：`quietbox-0.6.1.apk`，versionCode 10，2,575,008 字节，SHA-256
`f9fa835fbe8cd8c5eb749284e0e5d42d0bf6534daf2a44f877cb7ab971fc1f2c`。

## v0.6.0 — 2026-10-19

- 新增 8 项功能，全部独立开关、默认关闭：
  - 评分引导弹窗屏蔽（P1-16）：不弹「满意吗 / 去评分」和 Google Play 系统评分
  - 通知引导弹窗屏蔽（P1-17）：不弹「开启通知」引导；手动开通知不受影响
  - 满意度调研条屏蔽（P1-18）：详情页不再弹出「对内容满意吗」
  - 通讯录同步关闭（P1-19）：不上传通讯录，不再有通讯录推荐好友
  - 评论区带货插入屏蔽（P1-20）：评论区顶部不再插入商品卡
  - 直播卡 / 带货卡 / 投票卡过滤（P1-21/22/23）：信息流里不显示这三类卡片，
    复用信息流广告过滤已验证的列表链路，只移除卡片、不动分页游标
- 新增版本不兼容警示：挂载点大面积失效（≥5 个）时，静盒顶部显示红色提示。
  平时计数健康则不显示，界面仍是零诊断内容。
- AB 面板修复与加固：
  - 修复「首页视频预加载」（`andr_homefeed_preload_video`）强开不生效的问题
    （v0.5.0 已知问题）：该读点是整数型，之前误喂 `"true"` 导致解析失败被吞
  - 修复「个人页布局预加载」（`andr_profile_layout_request_preload`）只点亮
    一半读点的问题：两个读点阈值分别是 >0 和 >1，强开值从 `"1"` 改为 `"2"`
  - 每条精选实验现在带声明式的类型标注（BOOL / INT），由 9.50.0 反汇编三重证据
    （TypeToken 泛型 + 默认值字面量 + 返回值 check-cast）逐条判定
  - 强制值按声明类型规整：类型不匹配时自动改写，解析失败一律回落到原值并打
    告警日志，不再静默失效；读取值为 null（key 尚未下发）时也能正确强开
  - 「其他」列表里手填的未收录 key 按值的字面形状推断类型
- 状态卡修正：未激活时提示「请在 LSPosed 中启用本模块并勾选小红书」
  （此前文案说的是作用域未勾选，与实际状态不符）；顺带清掉两条未引用字符串。
- 文档：「已删除笔记查看」标注为实验性——该恢复路径仅理论验证，未在真实被删
  笔记场景实测。catalog 里 21 个精选 key 的推荐列改为「精选」。
- 构建：`quietbox-0.6.0.apk`，versionCode 9，2,571,548 字节，SHA-256
  `c26f7ea1b82f7fd662668a67e054cf9b5f1b6189cab3eb2a7e799b20386e6097`。

## v0.5.0 — 2026-10-19

- 精选实验从 19 项扩到 21 项：
  - 新增「P3 图片预加载」（`andr_note_detail_preload_p3_image_request`）
  - 新增「解除 2K/4K 码流过滤」（`andr_player_intelligent_rep_select_disable_filter_2k_4k`）
- 「测试」页新增「导出全量实验」：生成 CSV（全量 5760 个 key + 当前探测值），
  通过系统文件选择器保存到本地，不需要存储权限。
- 修正「字幕新样式」的 AB key（`caption_new_style_v3`）。
- 构建：`quietbox-0.5.0.apk`，2,555,756 字节，SHA-256
  `f418b3e025bae58d9910c2ff2146b7d7b311d0e43ec14175d1fdd2a8450647fd`。
- 已知问题：「首页视频预加载」（`andr_homefeed_preload_video`）的强开值写错类型，
  该读点是整数型却喂了 `"true"`，开关实际不生效；v0.6.0 已修复。

## v0.4.1 — 2026-10-18

- 修正「字幕新样式」的 AB key：实际读取的是 `caption_new_style_v3`，
  v0.4.0 误写成 `andr_caption_new_style_v3`，导致该开关无效。
- 其余功能与 v0.4.0 相同。
- 构建：`quietbox-0.4.1.apk`，2,466,377 字节，SHA-256
  `1bd2ea9ce12bf7d02c5b115c6c8937ad456b744efe12f998267482c51a5b7f4b`。

## v0.4.0 — 2026-10-18

- 新增底栏：「功能 / 测试」两栏。
- 新增「AB 测试面板」：
  - 内置 19 个高价值、纯客户端的实验开关（详情页渲染、播放体验、评论键盘、首页预加载等），
    每项独立开关，默认关闭。
  - 读取点：`tq.h#f`、`tq.h#b`（ABTest SDK）与 `eb6.o#l`（ConfigCenter）；
    覆盖表每 3 秒通过 `AbReportProvider` 拉取（remote prefs 作为启动兜底），
    改单个开关后回到小红书约 3 秒生效。
  - 最底部「其他（探测到，未收录）」展示模块在宿主进程读到的其他 AB key 与当前值；
    只读，广告/埋点/直播/服务端调参类标记为「不推荐修改」。
  - 「其他」按 50 条一批增量加载：滚到底自动加载下一批，底部保留「加载更多」兜底。
  - 探测数据由小红书进程单向写入模块 App 的 `AbReportProvider`（本地存储，不联网）。
- 构建：`quietbox-0.4.0.apk`，2,466,377 字节，SHA-256
  `fe5987978c64c35f922f8f6af45508eb909cd2ffb6c00f0a678754e687d4390e`。
- 已知限制：面板首次启用后需重启小红书才会安装 hook；未收录项不提供强制开关。

## v0.3.0 — 2026-10-18

- 新增「已删除笔记查看」：服务端把笔记标记为不可展示（`NoteFeed.modelType == "error"`）
  时，改用本地已缓存的内容继续显示；没有可用缓存时走与断网相同的加载失败分支，
  不伪造内容。适用于笔记已删除但封面/图片仍在本地缓存的场景。
- 设置页新增「内容与浏览」分组。
- 发布：源码仓库 tag `v0.3.0`；官方仓库 tag `5-0.3.0`（`versionCode 5`）。
  APK `quietbox-0.3.0.apk`，2,363,829 字节，SHA-256
  `7664a5bb83ce7c9864c0abc4bc3dff8c5650deac7221faefad8b6db80ee5ae47`。

## v0.2.2 — 2026-10-17

- 使用自备 keystore 正式签名（此前为 debug 证书）。
- 关闭 `autoHotReload`：模块未实现 `onHotReloading`（默认拒绝热重载），
  开着只会在每次更新模块时提示「热重载失败」。更新后重启目标应用即可。

## v0.2.1 — 2026-10-17

- 修正状态卡：改用 `getRunningTargets()` 判断目标进程是否真的被注入。
  此前模块已安装但未在 LSPosed 中启用时也会显示「已激活」，容易误导。
  现在分三态：未激活 / 框架已连接（等待小红书启动）/ 已激活。
- 包名调整为 `io.github.yigenhuobah.quietbox`，符合 Xposed-Modules-Repo 新规。

## v0.2.0 — 2026-10-17

首个公开版本。

**功能**

- 开屏广告跳过：覆盖内嵌 Fragment、独立全屏 Activity、冷启动占位三条路径
- 信息流广告过滤：开屏关联广告 + 服务端下发的普通广告
- 评论广告过滤
- 广告外跳拦截
- 搜索广告过滤（新增）
- 详情页广告条屏蔽（新增）
- 更新弹窗屏蔽
- 截图检测关闭
- 复制链接净化（可选删除 xsec_token）
- 保存图片去水印
- 自动播放与画质偏好

**界面**

- 重做为 Material 3 设置界面，支持系统动态取色（Android 12+）
- 顶部状态卡显示激活状态、作用域与框架 API 版本
- 按「广告 / 界面与隐私 / 分享与播放」分组
- 新增自适应启动图标

**工程**

- 包名调整为 `io.github.yigenhuobah.quietbox`
- release 启用 R8 与资源收缩，APK 约 2.3 MB
- 移除目标进程到模块 App 的状态回传（MIUI 上广播与 ContentProvider 均被拦截，
  且正式界面不再展示诊断信息）

**已知限制**

- 默认静音：小红书自带自动静音，不提供
- 摇一摇跳转禁用：检测在原生引擎侧，Java 层无可靠挂载点
- 埋点抑制：本版本无安全挂载点
- 仅适配小红书 9.50.0（9500803）
