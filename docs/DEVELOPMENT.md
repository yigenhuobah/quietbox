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

1. 改 `app/build.gradle` 的 `versionCode` / `versionName`。
2. `gradlew assembleDebug assembleRelease`，确认两者都通过。
3. 真机安装 release 包，冷启动目标应用，确认：
   - 挂载点注册数正常
   - 没有 `skip` / `error`
   - 目标进程没有崩溃
4. 打 tag 并创建 GitHub Release，附上 `quietbox-<版本>.apk` 与 SHA-256。
5. 更新 README 的兼容版本说明。

## 签名

release 读取仓库根目录的 `keystore.properties`（不入库）：

```properties
storeFile=my-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有该文件时 release 借用 debug 证书——可以安装，但不算正式签名。
