# 开发与维护说明

面向接手维护的人。用户安装说明看 [README](../README.md)。

## 项目结构

```
app/src/main/java/com/quietbox/app/
├── QuietBoxModule.java        模块入口（libxposed API 102，单入口类）
├── Hooks.java                 按开关注册 hook
├── Config.java                目标进程侧读 remote prefs
├── ConfigStore.java           模块 App 侧写 remote prefs
├── Feature.java               功能清单（键名 / 标题 / 是否不可实现）
├── HookKit.java               定位与 hook 的公共封装
├── hooks/                     各功能的 hook 实现
├── MainActivity.java          设置入口
├── SettingsFragment.java      Material 3 设置页
└── MaterialSwitchPreference.java
app/src/main/resources/META-INF/xposed/   模块声明三件套
tools/check-mounts.sh                     挂载点体检
```

## 开发约束

1. 每个 hook 的 intercept 方法体必须整段包在 `try/catch(Throwable)` 里。
   框架只捕获 `RuntimeException`，`HookFailedError` 继承 `Error`，漏出去会带崩目标进程。
2. `chain.proceed()` 只调一次。不要写成 `try { return proceed(x); } catch { return proceed(); }`，
   原方法抛异常时会导致执行两遍。
3. 在 `onPackageReady` 里注册，并按包名过滤。
4. 不在 `onModuleLoaded` 之前初始化。
5. 定位失败只记日志并跳过，绝不让模块加载失败。
6. 多参重载不能用无参 `getDeclaredMethod` 定位。用 `HookKit.findByShape` 或
   `findByNameAndArity`；`findMethodQuiet(cls, "g")` 只匹配无参方法。
7. 开关必须写 remote prefs（`ServiceBridge.remotePreferences`），写本地目标进程读不到。
8. hook 代码不要依赖 androidx 类型。目标进程的类加载器对 androidx 是分层的，
   `Class.forName("androidx.fragment.app.Fragment", false, cl)` 会失败。

## 挂载点维护

模块依赖目标应用的具体版本。应用升级后，混淆类名可能变化，需要重新定位。

```bash
bash tools/check-mounts.sh <新版apk>
```

首次会为新 APK 建 DexSQL 缓存（约 40 秒），之后输出每个挂载点的状态：

- `OK` — 类和方法都在
- `GONE` — 类不存在了（混淆名变了）
- `SIG` — 类在但方法名变了

重新定位时优先找非混淆锚点（Activity、SPI 接口、字符串常量），再顺着调用链往下找。
不要凭类名相似下结论。

## 发布流程

模块同时发布到两个仓库，缺一个用户就收不到更新：

| 仓库 | 作用 | tag 格式 |
|---|---|---|
| `yigenhuobah/quietbox` | 源码主仓库，Release 面向直接下载 | `v0.2.3` |
| `Xposed-Modules-Repo/io.github.yigenhuobah.quietbox` | 官方模块仓库，LSPosed 管理器从这里读 | `5-0.2.3`（`<versionCode>-<versionName>`） |

### 1. 构建

```bash
# 改 app/build.gradle 的 versionCode / versionName
cd /d/modules/QuietBox/project
./gradlew assembleDebug assembleRelease
```

两个构建都必须通过。release 包体积应在 2–3 MB（R8 开启），签名必须来自
`keystore.properties`（无该文件会借用 debug 证书，**不要发布**）。

### 2. 真机验证

安装 release 包，冷启动目标应用，确认挂载点注册数正常、无 `skip` / `error`、
目标进程不崩溃。新功能必须逐项触发一次，不能只看「注册成功」。

0.4.0 起 AB 面板还需额外验证：

1. 模块 App 打开「测试」页 → 启用面板总开关；
2. 冷启动小红书，logcat 应出现 `P2-1 registered ABStore#f/b` 与 `ConfigCenter#l`；
3. 强开一项确实会被读取的实验，回到小红书，应出现
   `P2-1 provider pull ...` 与 `P2-1 override <key>=<value>`；
4. 回到模块 App 的「测试」页，最底部「其他」应已有探测到的 key。

### 3. 主仓库

```bash
git add -A && git commit -m "v0.2.3：<一句话>"
git tag v0.2.3 && git push origin main --tags
gh release create v0.2.3 build/outputs/apk/release/quietbox-0.2.3.apk \
  --title "0.2.3" --notes "<更新说明>"
```

### 4. 官方模块仓库

官方仓库由机器人创建，本地克隆一份长期保留：

```bash
cd /d/modules/QuietBox/research/official-repo
# 换入新 APK；SUMMARY 和 README.md 同步主仓库的用户说明
cp /d/modules/QuietBox/project/app/build/outputs/apk/release/quietbox-0.2.3.apk .
git add -A && git commit -m "0.2.3"
git tag 5-0.2.3 && git push origin main --tags
gh release create 5-0.2.3 quietbox-0.2.3.apk --title "0.2.3" --notes "<更新说明>"
```

注意事项：

- **tag 必须是 `<versionCode>-<versionName>`**，解析失败模块不会出现在索引里。
- 官方仓库里只需要 `SUMMARY`、`README.md` 和 release 资产，不要推源码。
- 仓库 **Description** 是模块在 LSPosed 里显示的名字，只能用网页设置
  （About 齿轮 → Description）。GitHub API 对 `maintain` 角色返回 404。
- 模块作者在该仓库只有 **maintain** 权限，**无法添加协作者**（需要 admin）。
  发版只用 `maintain` 就够；想让另一个号发版，只能通过 submission issue 让机器人
  追加维护者，不要指望网页的 Collaborators 入口。
- 官方索引站点会挡爬虫，`curl` 可能直接返回 403。这不是模块没上架，等索引重建即可。
- 发布后 5–60 分钟官方索引（`modules.lsposed.org/modules.json`）重建，可自查：

```bash
curl -s https://modules.lsposed.org/modules.json \
  | grep -o 'io.github.yigenhuobah.quietbox'
```

### 5. 收尾

更新 README 的兼容版本说明与 CHANGELOG。

## 签名

release 读取仓库根目录的 `keystore.properties`（不入库）：

```properties
storeFile=my-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有该文件时 release 借用 debug 证书——可以安装，但不算正式签名。
