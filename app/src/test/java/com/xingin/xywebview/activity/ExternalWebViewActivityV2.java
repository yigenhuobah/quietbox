package com.xingin.xywebview.activity;

/**
 * 测试替身（仅存在于单测源集）：宿主真实类不在 JVM 单测 classpath 上，
 * ExtAppHooks.isExternalWebView / shouldBlock 按「类名沿父类链精确匹配」判定，
 * 因此替身必须与宿主同名同包。生产代码不引用本类。
 */
public class ExternalWebViewActivityV2 {
}
