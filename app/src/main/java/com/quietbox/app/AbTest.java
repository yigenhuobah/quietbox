package com.quietbox.app;

/**
 * 一条可强开的 AB 实验。
 *
 * forceValue 是强开时喂给取值入口的字符串：布尔实验是 "true"，组号实验是 "1"。
 * 这些 key 全部来自 9.50.0 的客户端读取点，且默认值是 false/0，
 * 所以强开才会真正改变行为（默认已开的项不收录）。
 */
public final class AbTest {

    public static final int GROUP_DETAIL = 0;
    public static final int GROUP_PLAYBACK = 1;
    public static final int GROUP_MISC = 2;

    public final String key;
    public final int group;
    public final int titleRes;
    public final int summaryRes;
    public final String forceValue;

    AbTest(String key, int group, int titleRes, int summaryRes, String forceValue) {
        this.key = key;
        this.group = group;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.forceValue = forceValue;
    }
}
