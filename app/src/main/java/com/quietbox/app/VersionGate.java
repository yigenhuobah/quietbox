package com.quietbox.app;

/**
 * 目标应用版本门禁。
 *
 * 只声明“适配版本”这一件事：未知版本不阻断 hook（fail-open），
 * 由异常横幅提示；hook 内部仍按 Feature 开关执行。
 */
public final class VersionGate {

    /** 小红书 9.50.0（9500803）：当前全部挂载点的实测版本。 */
    public static final String SUPPORTED_VERSION = "9.50.0";

    private VersionGate() {
    }

    public static boolean isSupported(String versionName) {
        return SUPPORTED_VERSION.equals(versionName);
    }
}
