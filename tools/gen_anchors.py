#!/usr/bin/env python3
"""
生成 QB 的 anchors.json 与 AnchorKeys.java：每个锚点先用 DexSQL 对目标 APK
校验存在，全部命中才写文件。

用法:
    python tools/gen_anchors.py --apk /path/to/xhs.apk \
        --out app/src/main/resources/anchors.json \
        --keys-out app/src/main/java/com/quietbox/app/anchor/AnchorKeys.java

    # dex/sql 工具路径显式指定
    python tools/gen_anchors.py --apk ... --dexsql /path/to/dexsql --aapt2 /path/to/aapt2

输出 schema v1（与 TB 一致）：顶层
schemaVersion/app/appVersion/versionCode/generatedBy/anchors/classes；
每个锚点 c/m/s + feature/critical/note。s 以 "(" 开头是方法，否则是字段类型描述符。
classes 段保持 key -> FQCN。

锚点来源：tools/check-mounts.sh 的 64 项挂载点清单 + hooks/*.java 里的
类/方法常量。check-mounts.sh 只读生成的 anchors.json，不再维护第二份表。

DexSQL 缓存固定在 <repo>/tools/.dexsql-cache/；APK 路径/大小/sha256 任一变化都会
自动重建。所有锚点命中后才写 --out，缺失时打印 MISSING 并以非 0 退出，原文件不变。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

# 锚点单一事实来源。key -> c/m/s + feature/critical/note。
# s 以 "(" 开头表示方法（methods 表校验）；否则表示字段类型描述符
# （fields 表校验，如 "Ljava/lang/String;"）。
ANCHORS = {
    "splash_open_lcb_v2": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "openLCBSplashAdsV2", "s": "(Landroid/content/Context;Lcom/xingin/android/redutils/base/XhsActivity;Lcom/xingin/advert/intersitial/bean/SplashAd;Lkotlin/jvm/functions/Function2;)V",
        "feature": "AD-01", "critical": True,
        "note": "内嵌开屏主入口（openLCBSplashAdsV2）",
    },
    "splash_open_lcb_red_v2": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "openLCBRedSplashAdsV2", "s": "(Landroid/content/Context;Lcom/xingin/android/redutils/base/XhsActivity;Lcom/xingin/advert/intersitial/bean/SplashAd;Landroidx/core/util/Supplier;Lcom/uber/autodispose/ScopeProvider;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function2;)V",
        "feature": "AD-01", "critical": True,
        "note": "红/CNY 内嵌开屏入口",
    },
    "splash_open_lcb_brand_max": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "openLCBBrandMaxSplashAds", "s": "(Landroid/content/Context;Lcom/xingin/android/redutils/base/XhsActivity;Lcom/xingin/advert/intersitial/bean/SplashAd;Lcom/uber/autodispose/ScopeProvider;Lkotlin/jvm/functions/Function2;)V",
        "feature": "AD-01", "critical": True,
        "note": "品牌 Max 内嵌开屏入口",
    },
    "splash_open_brand_max": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "openBrandMax", "s": "(Landroid/app/Activity;ILcom/xingin/advert/intersitial/bean/SplashAd;Z)V",
        "feature": "AD-01", "critical": True,
        "note": "品牌开屏入口（Activity+int+SplashAd+Z）",
    },
    "splash_open_lazy_hf": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "openNeedLazyHFSplashAds", "s": "(Landroid/content/Context;Lcom/xingin/android/redutils/base/XhsActivity;Lcom/xingin/advert/intersitial/bean/SplashAd;Lede/d;Lcom/uber/autodispose/ScopeProvider;Lkotlin/jvm/functions/Function2;)V",
        "feature": "AD-01", "critical": True,
        "note": "懒加载 HF 开屏入口",
    },
    "splash_start_anim": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "startAnim", "s": "(Lcom/xingin/advert/splash/AdsFragment;Landroid/view/View;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;)V",
        "feature": "AD-01", "critical": True,
        "note": "开屏动画入口",
    },
    "splash_can_lazy_open": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "canLazyOpenSplash", "s": "(Lcom/xingin/advert/intersitial/bean/SplashAd;)Z",
        "feature": "AD-01", "critical": True,
        "note": "懒加载开屏决策（返回 false 关闭）",
    },
    "splash_can_need_new_closer": {
        "c": "com.xingin.advert.spi.AdsIndexSpiImp", "m": "canNeedNewCloser", "s": "(Lcom/xingin/advert/intersitial/bean/SplashAd;)Z",
        "feature": "AD-01", "critical": True,
        "note": "开屏关闭器决策（返回 false 关闭）",
    },
    "splash_fullscreen_entry": {
        "c": "com.xingin.advert.intersitial.ui.a", "m": "d", "s": "(Ljava/lang/ref/WeakReference;Lcom/xingin/advert/intersitial/bean/SplashAd;)V",
        "feature": "AD-01", "critical": True,
        "note": "独立全屏开屏唯一出口 ui.a#d",
    },
    "splash_activity_launcher": {
        "c": "com.xingin.advert.intersitial.ui2.view.SplashAdActivity$a", "m": "a", "s": "(Landroid/content/Context;Lcom/xingin/advert/intersitial/bean/SplashAd;)V",
        "feature": "AD-01", "critical": False,
        "note": "SplashAdActivity 全屏开屏兜底",
    },
    "splash_dynamic_launcher": {
        "c": "com.xingin.advert.intersitial.ui2.view.dynamic.SplashDynamicActivity$a", "m": "a", "s": "(Landroid/content/Context;Lcom/xingin/advert/intersitial/bean/SplashAd;)V",
        "feature": "AD-01", "critical": False,
        "note": "SplashDynamicActivity 动态开屏兜底",
    },
    "splash_cny_launcher": {
        "c": "com.xingin.advert.intersitial.ui2.cny.SplashCNYActivity$a", "m": "a", "s": "(Landroid/content/Context;Lcom/xingin/advert/intersitial/bean/SplashAd;)V",
        "feature": "AD-01", "critical": False,
        "note": "SplashCNYActivity CNY 开屏兜底",
    },
    "splash_interstitial_launcher": {
        "c": "com.xingin.advert.intersitial.ui.InterstitialAdsActivity$a", "m": "a", "s": "(Landroid/content/Context;Lcom/xingin/advert/intersitial/bean/SplashAd;)V",
        "feature": "AD-01", "critical": False,
        "note": "InterstitialAdsActivity 插屏开屏兜底",
    },
    "splash_placeholder_view": {
        "c": "com.xingin.advert.intersitial.ui.SplashAdPlaceHolderView", "m": "onCreateView", "s": "(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;",
        "feature": "AD-01", "critical": False,
        "note": "冷启动占位 Fragment（非广告内容）",
    },
    "feed_ad_handle_if_need": {
        "c": "com.xingin.advert.spi.AdsHomeFeedManagerServiceImpl", "m": "handleFeedDataIfNeed", "s": "(Ljava/util/ArrayList;Ljava/lang/String;)V",
        "feature": "AD-02", "critical": True,
        "note": "首页第 2 位广告比对入口",
    },
    "feed_ad_record_note": {
        "c": "com.xingin.advert.spi.AdsHomeFeedManagerServiceImpl", "m": "recordAdsNoteData", "s": "(Lcom/xingin/advert/intersitial/bean/SplashAd;Lee7/r;)V",
        "feature": "AD-02", "critical": True,
        "note": "首页第 2 位广告记录入口",
    },
    "feed_ad_insert_guard": {
        "c": "nfd.m0", "m": "g0", "s": "(ILcom/xingin/entities/NoteItemBean;)V",
        "feature": "AD-02", "critical": True,
        "note": "2-pos 广告插入守卫",
    },
    "feed_ad_append_filter": {
        "c": "nfd.m0", "m": "S", "s": "(Ljava/util/ArrayList;)V",
        "feature": "AD-02", "critical": True,
        "note": "信息流追加列表广告过滤（AD-02）",
    },
    "feed_ad_refresh_filter": {
        "c": "nfd.m0", "m": "C0", "s": "(Ljava/util/ArrayList;I)V",
        "feature": "AD-02", "critical": True,
        "note": "信息流刷新列表广告过滤（AD-02）",
    },
    "feed_ad_pager_filter": {
        "c": "ifd.s", "m": "Pd", "s": "(ZLjava/util/ArrayList;Lifd/b;)V",
        "feature": "AD-02", "critical": True,
        "note": "分页上游列表广告过滤（AD-02）",
    },
    "comment_ad_dsl_enable": {
        "c": "com.xingin.advert.spi.AdsCommentComponentBinderCreatorImpl", "m": "commentAdvertComponentDSLEnable", "s": "()Z",
        "feature": "AD-03", "critical": True,
        "note": "评论广告组件总开关（返回 false）",
    },
    "update_dialog_on_create": {
        "c": "com.xingin.update.UpdateDialogActivity", "m": "onCreate", "s": "(Landroid/os/Bundle;)V",
        "feature": "UI-04", "critical": True,
        "note": "更新弹窗 Activity onCreate（finish）",
    },
    "update_dialog_he": {
        "c": "com.xingin.update.UpdateDialogActivity", "m": "he", "s": "()V",
        "feature": "UI-04", "critical": True,
        "note": "更新弹窗补充入口（void skip）",
    },
    "ext_app_goto": {
        "c": "com.xingin.advert.spi.AdvertSpiImpl", "m": "gotoExtApp", "s": "(Landroid/net/Uri;Landroid/content/Context;Lcom/xingin/entities/ad/AdRedirectInfo;)V",
        "feature": "AD-04", "critical": True,
        "note": "广告外跳 SPI 主挂 gotoExtApp",
    },
    "ext_app_webview_converge": {
        "c": "m8e.i0", "m": "b", "s": "(Landroid/net/Uri;Landroid/content/Context;Lcom/xingin/entities/ad/AdRedirectInfo;ZZ)Z",
        "feature": "AD-04", "critical": False,
        "note": "落地页外跳汇聚点（Path B 兜底）",
    },
    "copy_link_clipboard_write": {
        "c": "q7b.e", "m": "b", "s": "(Landroid/content/ClipboardManager;Landroid/content/ClipData;)V",
        "feature": "PV-03", "critical": True,
        "note": "剪贴板写入点（setPrimaryClip 收口）",
    },
    "copy_link_funnel": {
        "c": "k92.r", "m": "a", "s": "(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z",
        "feature": "PV-03", "critical": False,
        "note": "复制链接 String 收口（兜底）",
    },
    "watermark_compose": {
        "c": "bm8.j", "m": "a", "s": "(Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;ZZ)Landroid/graphics/Bitmap;",
        "feature": "EN-01", "critical": True,
        "note": "Canvas 水印合成点（禁用主/AI 水印）",
    },
    "watermark_save_entry": {
        "c": "bm8.i", "m": "i", "s": "(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;ZZZ)V",
        "feature": "EN-01", "critical": True,
        "note": "图片保存入口（11 参 disableWaterMark）",
    },
    "watermark_model_getter": {
        "c": "com.xingin.entities.MediaSaveConfig", "m": "c", "s": "()Z",
        "feature": "EN-01", "critical": False,
        "note": "MediaSaveConfig 语义 getter（可选）",
    },
    "live_photo_save_entry": {
        "c": "ss9.j", "m": "c", "s": "(Lny9/m;Lcl9/i;Z)V",
        "feature": "EN-03", "critical": True,
        "note": "实况保存入口 ss9.j#c（改 feedback 字段）",
    },
    "live_photo_feedback_init": {
        "c": "cl9.i", "m": "<init>", "s": "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Z)V",
        "feature": "EN-03", "critical": False,
        "note": "实况下载参数形状（8 参 e/f/g/h）",
    },
    "live_photo_save_switch": {
        "c": "wk2.h", "m": "a", "s": "(I)Z",
        "feature": "EN-03", "critical": True,
        "note": "实况保存链路开关 code=1（返回 false 走旧链路）",
    },
    "live_photo_legacy_download": {
        "c": "ol2.e", "m": "d", "s": "(Ljava/lang/String;Z)Lio/reactivex/Observable;",
        "feature": "EN-03", "critical": False,
        "note": "旧链路视频下载（链路参考点）",
    },
    "force_save_function_enable": {
        "c": "com.xingin.entities.notedetail.FunctionSwitch", "m": "a", "s": "()Z",
        "feature": "EN-04", "critical": True,
        "note": "FunctionSwitch enable（下载类型强制 true）",
    },
    "force_save_function_type": {
        "c": "com.xingin.entities.notedetail.FunctionSwitch", "m": "c", "s": "()Ljava/lang/String;",
        "feature": "EN-04", "critical": True,
        "note": "FunctionSwitch type（判定下载类型）",
    },
    "autoplay_detail_open": {
        "c": "com.xingin.detailfeed.services.autoscroll.AutoScrollStateHelper", "m": "isAutoPlayOpen", "s": "()Z",
        "feature": "UI-05", "critical": True,
        "note": "详情页自动播放 isAutoPlayOpen",
    },
    "autoplay_detail_server": {
        "c": "com.xingin.detailfeed.services.autoscroll.AutoScrollStateHelper", "m": "isAutoPlayOpenServer", "s": "()Z",
        "feature": "UI-05", "critical": True,
        "note": "详情页自动播放 isAutoPlayOpenServer",
    },
    "autoplay_tab_open": {
        "c": "com.xingin.detailfeed.services.autoscroll.AutoScrollStateTabHelper", "m": "isAutoPlayOpen", "s": "()Z",
        "feature": "UI-05", "critical": True,
        "note": "Tab 自动播放 isAutoPlayOpen",
    },
    "autoplay_tab_server": {
        "c": "com.xingin.detailfeed.services.autoscroll.AutoScrollStateTabHelper", "m": "isAutoPlayOpenServer", "s": "()Z",
        "feature": "UI-05", "critical": True,
        "note": "Tab 自动播放 isAutoPlayOpenServer",
    },
    "search_ad_result_filter": {
        "c": "jl0.f0", "m": "o", "s": "(Lcom/xingin/alioth/entities/k;Lkn0/n;ZLk3c/g;)Ljava/util/List;",
        "feature": "AD-05", "critical": True,
        "note": "搜索结果广告列表过滤（数据层）",
    },
    "note_detail_ad_image": {
        "c": "com.xingin.matrix.notedetail.asyncwidgets.advert.AdvertV2View", "m": "a", "s": "(Lz10/b;)V",
        "feature": "AD-06", "critical": True,
        "note": "详情页图片广告绑定入口",
    },
    "note_detail_ad_video": {
        "c": "com.xingin.matrix.notedetail.asyncwidgets.advert.AdvertV2View", "m": "b", "s": "(Lo50/a0;)V",
        "feature": "AD-06", "critical": True,
        "note": "详情页视频广告绑定入口",
    },
    "note_unavailable_map_invoke": {
        "c": "rz9.o", "m": "invoke", "s": "(Ljava/lang/Object;)Ljava/lang/Object;",
        "feature": "EX-01", "critical": True,
        "note": "NoteDetailRepository map 阶段 lambda",
    },
    "score_dialog_on_create": {
        "c": "com.xingin.lurker.appscore.ui.AppScoreDialogActivity", "m": "onCreate", "s": "(Landroid/os/Bundle;)V",
        "feature": "UI-01", "critical": True,
        "note": "应用内评分弹窗 onCreate（finish+抹 extra）",
    },
    "score_gp_review": {
        "c": "com.xingin.xhs.app.ShareApplication", "m": "tryLaunchGpReview", "s": "(La2/b;)V",
        "feature": "UI-01", "critical": True,
        "note": "Google Play 系统评分入口（void skip）",
    },
    "push_guide_dialog": {
        "c": "com.xingin.spi.IGrowthCupidProxyImpl", "m": "pushGuideDialogDialogShow", "s": "(Landroid/content/Context;Lco/c;Ljava/lang/String;Lp1/a;)V",
        "feature": "UI-02", "critical": True,
        "note": "通知引导弹窗展示入口",
    },
    "push_guide_popup": {
        "c": "com.xingin.spi.IGrowthCupidProxyImpl", "m": "showGuidePopupWindow", "s": "(Landroid/app/Activity;Ljava/lang/String;)V",
        "feature": "UI-02", "critical": True,
        "note": "通知引导 PopupWindow 入口",
    },
    "push_guide_inside": {
        "c": "com.xingin.spi.IGrowthCupidProxyImpl", "m": "showPushGuidShowForInside", "s": "(Landroid/content/Context;Ljava/lang/String;)V",
        "feature": "UI-02", "critical": True,
        "note": "站内通知引导入口",
    },
    "push_guide_activity": {
        "c": "com.xingin.spi.IGrowthCupidProxyImpl", "m": "openPushGuideActivity", "s": "(Landroid/content/Context;Ljava/lang/String;)V",
        "feature": "UI-02", "critical": True,
        "note": "通知引导 Activity 入口",
    },
    "content_rating_should_show": {
        "c": "com.xingin.growth.snackbar.ContentRatingProxyImpl", "m": "shouldShowInternal", "s": "(Ljava/lang/String;)Z",
        "feature": "UI-03", "critical": True,
        "note": "满意度调研总判定（返回 false）",
    },
    "content_rating_show_video": {
        "c": "com.xingin.growth.snackbar.ContentRatingProxyImpl", "m": "showForVideo", "s": "(Landroid/app/Activity;Landroid/xingin/com/spi/growth/IContentRatingProxy$b;Ljava/lang/String;II)V",
        "feature": "UI-03", "critical": False,
        "note": "视频场景满意度兜底",
    },
    "content_rating_show_image_text": {
        "c": "com.xingin.growth.snackbar.ContentRatingProxyImpl", "m": "showForImageText", "s": "(Landroid/app/Activity;Landroid/xingin/com/spi/growth/IContentRatingProxy$b;Ljava/lang/String;I)V",
        "feature": "UI-03", "critical": False,
        "note": "图文场景满意度兜底",
    },
    "content_rating_show_if_needed": {
        "c": "com.xingin.growth.snackbar.ContentRatingProxyImpl", "m": "showContentRatingSnackBarIfNeeded", "s": "(Landroid/app/Activity;Landroid/xingin/com/spi/growth/IContentRatingProxy$b;II)V",
        "feature": "UI-03", "critical": False,
        "note": "满意度公开入口兜底",
    },
    "contact_sync_start": {
        "c": "com.xingin.login.manager.UploadContactServiceExtension", "m": "start", "s": "(Landroid/content/Context;)V",
        "feature": "PV-02", "critical": True,
        "note": "通讯录同步 start",
    },
    "contact_sync_request": {
        "c": "com.xingin.login.manager.UploadContactServiceExtension", "m": "requestContactsSync", "s": "(Landroid/content/Context;Ljava/lang/String;)V",
        "feature": "PV-02", "critical": True,
        "note": "通讯录同步 requestContactsSync（2 参主入口）",
    },
    "contact_sync_lifecycle": {
        "c": "com.xingin.login.manager.UploadContactServiceExtension", "m": "installLifecycleObserver", "s": "(Landroid/app/Application;)V",
        "feature": "PV-02", "critical": True,
        "note": "通讯录同步生命周期注册",
    },
    "goods_comment_start_add_in": {
        "c": "com.xingin.commercial.vision.post.spi.GoodsCommentAddInProcessor", "m": "startAddIn", "s": "(Ljava/lang/String;Lg1/c;Lg1/d;Lg1/b;Ljava/util/HashMap;)V",
        "feature": "AD-07", "critical": True,
        "note": "评论区带货插入",
    },
    "feed_card_append_filter": {
        "c": "nfd.m0", "m": "S", "s": "(Ljava/util/ArrayList;)V",
        "feature": "AD-08", "critical": True,
        "note": "信息流追加列表直播/带货/投票卡过滤（AD-08/09/10 共用）",
    },
    "feed_card_refresh_filter": {
        "c": "nfd.m0", "m": "C0", "s": "(Ljava/util/ArrayList;I)V",
        "feature": "AD-08", "critical": True,
        "note": "信息流刷新列表直播/带货/投票卡过滤（AD-08/09/10 共用）",
    },
    "feed_card_pager_filter": {
        "c": "ifd.s", "m": "Pd", "s": "(ZLjava/util/ArrayList;Lifd/b;)V",
        "feature": "AD-08", "critical": True,
        "note": "分页上游直播/带货/投票卡过滤（AD-08/09/10 共用）",
    },
    "ab_get_value_once": {
        "c": "oq.h", "m": "e", "s": "(Ljava/lang/String;Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;",
        "feature": "EX-02", "critical": True,
        "note": "AB getValueJustOnce 读取点",
    },
    "ab_get_value": {
        "c": "oq.h", "m": "b", "s": "(Ljava/lang/String;Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;",
        "feature": "EX-02", "critical": True,
        "note": "AB getValue 读取点",
    },
    "config_center_get_value": {
        "c": "vk6.p", "m": "l", "s": "(Ljava/lang/String;)Ljava/lang/String;",
        "feature": "EX-02", "critical": True,
        "note": "ConfigCenter 字符串取值总入口",
    },
}

# 类锚点：hook 里按类型判定/取 Class 的语义类名。key -> FQCN。
CLASSES = {
    "media_save_config": "com.xingin.entities.MediaSaveConfig",
    "search_ad_entity": "com.xingin.alioth.entities.i",
    "external_webview": "com.xingin.xywebview.activity.ExternalWebViewActivityV2",
    "note_detail_repository": "com.xingin.matrix.notedetail.v3.repo.NoteDetailRepository",
    "note_detail_holder": "com.xingin.notebase.entities.notedetail.DetailNoteFeedHolder",
    "note_feed": "com.xingin.entities.notedetail.NoteFeed",
}

TARGET_PACKAGE = "com.xingin.xhs"
SCHEMA_VERSION = 1
CACHE_DIR = REPO_ROOT / "tools" / ".dexsql-cache"


# ---- 工具定位 -------------------------------------------------------------
def _is_executable_file(path):
    if not path:
        return False
    path = Path(path)
    if not path.is_file():
        return False
    if path.suffix.lower() == ".exe":
        return True
    return os.access(str(path), os.X_OK)


def _first_executable(candidates):
    for candidate in candidates:
        if _is_executable_file(candidate):
            return Path(candidate)
    return None


def _sdk_roots():
    for value in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if value:
            yield Path(value)
    local = REPO_ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("sdk.dir"):
                value = line.split("=", 1)[1].strip()
                value = value.replace("\\\\", "\\").replace("\\:", ":")
                if value:
                    yield Path(value)
    for value in (
        Path.home() / "Android" / "Sdk",
        Path.home() / "Library" / "Android" / "sdk",
        Path.home() / "AppData" / "Local" / "Android" / "Sdk",
        Path("/usr/lib/android-sdk"),
        Path("/opt/android-sdk"),
    ):
        yield value


def resolve_dexsql(explicit=None):
    candidates = []
    if explicit:
        candidates.append(explicit)
    env = os.environ.get("DEXSQL")
    if env:
        candidates.append(env)
    path = shutil.which("dexsql") or shutil.which("dexsql.exe")
    if path:
        candidates.append(path)
    candidates.extend([
        str(REPO_ROOT / "tools" / "dexsql" / "dexsql"),
        str(REPO_ROOT / "tools" / "dexsql" / "dexsql.exe"),
        str(REPO_ROOT / "tools" / "dexsql.exe"),
        str(Path.home() / "tools" / "dexsql" / "dexsql"),
        str(Path.home() / "tools" / "dexsql" / "dexsql.exe"),
        str(Path.home() / ".local" / "bin" / "dexsql"),
        str(Path.home() / "bin" / "dexsql"),
        "/usr/local/bin/dexsql",
        "/opt/dexsql/dexsql",
        "E:/tools/dexsql/dexsql.exe",
    ])
    found = _first_executable(candidates)
    if found is None:
        raise SystemExit(
            "ERROR: dexsql not found; pass --dexsql /path/to/dexsql or set DEXSQL")
    return found


def resolve_aapt(explicit=None, required=False):
    candidates = []
    if explicit:
        candidates.append(explicit)
    env = os.environ.get("AAPT2")
    if env:
        candidates.append(env)
    path = shutil.which("aapt2") or shutil.which("aapt2.exe") \
        or shutil.which("aapt") or shutil.which("aapt.exe")
    if path:
        candidates.append(path)
    for root in _sdk_roots():
        build_tools = Path(root) / "build-tools"
        if not build_tools.is_dir():
            continue
        for version in sorted(build_tools.iterdir(), key=lambda p: p.name, reverse=True):
            for name in ("aapt2.exe", "aapt2", "aapt.exe", "aapt"):
                candidate = version / name
                if _is_executable_file(candidate):
                    candidates.append(str(candidate))
    found = _first_executable(candidates)
    if found is None and required:
        raise SystemExit(
            "ERROR: aapt2/aapt not found; pass --aapt2 /path/to/aapt2 or set ANDROID_HOME")
    return found


def read_apk_version(apk, aapt):
    if aapt is None:
        return None, None
    try:
        result = subprocess.run([str(aapt), "dump", "badging", str(apk)],
                                capture_output=True, text=True,
                                encoding="utf-8", errors="replace")
    except OSError:
        return None, None
    if result.returncode != 0:
        return None, None
    name = re.search(r"versionName='([^']*)'", result.stdout)
    code = re.search(r"versionCode='([0-9]+)'", result.stdout)
    return (name.group(1) if name else None,
            code.group(1) if code else None)


# ---- DexSQL 缓存 ----------------------------------------------------------
def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _meta_matches(meta_path, apk, size, sha):
    if not meta_path.is_file():
        return False
    values = {}
    for line in meta_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return (values.get("path") == str(apk.resolve())
            and values.get("size") == str(size)
            and values.get("sha256") == sha)


def ensure_cache(dexsql, apk, cache_dir=CACHE_DIR):
    cache_dir = Path(cache_dir)
    cache_file = cache_dir / "cache.dxc2"
    meta_path = cache_dir / "apk.meta"
    apk = Path(apk)
    size = apk.stat().st_size
    sha = _sha256(apk)
    if cache_file.is_file() and _meta_matches(meta_path, apk, size, sha):
        return cache_file
    shutil.rmtree(cache_dir, ignore_errors=True)
    cache_dir.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [str(dexsql), "prepare-cache", "--input", str(apk),
         "--output", str(cache_dir), "--trusted-input"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0 or not cache_file.is_file():
        raise SystemExit("ERROR: dexsql prepare-cache failed: %s"
                         % (result.stderr.strip() or result.stdout.strip()))
    meta_path.write_text(
        "version=2\npath=%s\nsize=%s\nmtime=%s\nsha256=%s\n"
        % (apk.resolve(), size, int(apk.stat().st_mtime), sha),
        encoding="utf-8")
    return cache_file


def query_count(dexsql, cache_file, sql):
    result = subprocess.run(
        [str(dexsql), "query-cache", "--cache-file", str(cache_file),
         "--trusted-input", "--sql", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    rows = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|-+ "):
            continue
        rows.append([cell.strip() for cell in line.strip("|").split("|")])
    if not rows:
        return 0
    try:
        return int(rows[-1][0])
    except (ValueError, IndexError):
        return 0


# ---- AnchorKeys.java ------------------------------------------------------
def java_constant_name(key):
    """锚点 key -> Java 常量名（大写、非字母数字转下划线）。"""
    return re.sub(r"[^0-9A-Za-z]+", "_", key).upper()


def anchor_keys_source(anchor_keys, class_keys, package="com.quietbox.app.anchor"):
    lines = [
        "// Generated by tools/gen_anchors.py from anchors.json. DO NOT EDIT.",
        "package %s;" % package,
        "",
        "/**",
        " * anchors.json 的逻辑键：编译期常量，避免 hook 代码里散落裸字符串。",
        " *",
        " * <p>由 tools/gen_anchors.py 生成；新增/删除锚点后重新生成，",
        " * AnchorContractTest 会校验常量与 anchors.json 双向一致。",
        " */",
        "public final class AnchorKeys {",
        "",
        "    private AnchorKeys() {",
        "    }",
        "",
    ]
    seen = {}
    for key in list(anchor_keys) + list(class_keys):
        constant = java_constant_name(key)
        if not constant or constant[0].isdigit():
            raise SystemExit("ERROR: 无法从锚点 key 生成合法 Java 常量名: %r" % key)
        if constant in seen and seen[constant] != key:
            raise SystemExit(
                "ERROR: 锚点 key %r 与 %r 生成同名常量 %s"
                % (seen[constant], key, constant))
        if constant in seen:
            continue
        seen[constant] = key
        lines.append('    public static final String %s = "%s";' % (constant, key))
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def default_keys_path(out_path):
    out = Path(out_path).resolve()
    try:
        out.relative_to(REPO_ROOT)
    except ValueError:
        return None
    if out.name != "anchors.json" or out.parent.name != "resources" \
            or out.parent.parent.name != "main":
        return None
    return (REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "quietbox"
            / "app" / "anchor" / "AnchorKeys.java")


def write_text_atomic(path, text):
    path = Path(path)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise SystemExit("ERROR: cannot create output dir %s: %s" % (path.parent, exc))
    tmp_path = None
    try:
        fd, tmp_name = tempfile.mkstemp(
            prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
        tmp_path = Path(tmp_name)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            mode = path.stat().st_mode & 0o777 if path.exists() else 0o644
            os.chmod(str(tmp_path), mode)
        except OSError:
            pass
        os.replace(str(tmp_path), str(path))
        tmp_path = None
    except OSError as exc:
        raise SystemExit("ERROR: cannot write %s: %s" % (path, exc))
    finally:
        if tmp_path is not None:
            try:
                tmp_path.unlink()
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(
        description="用 DexSQL 校验 QB 锚点并生成 anchors.json / AnchorKeys.java。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--apk", required=True, help="目标 APK")
    parser.add_argument("--out", required=True, help="输出 anchors.json")
    parser.add_argument("--keys-out", help="输出 AnchorKeys.java（默认按 --out 布局推导）")
    parser.add_argument("--dexsql", help="dexsql 可执行文件（默认 DEXSQL / PATH / 常见路径）")
    parser.add_argument("--aapt2", help="aapt2/aapt 可执行文件（读取 APK 版本）")
    parser.add_argument("--app-version", default=os.environ.get("APP_VERSION"),
                        help="APK 读取失败时显式指定 versionName")
    parser.add_argument("--version-code", default=os.environ.get("VERSION_CODE"),
                        help="APK 读取失败时显式指定 versionCode")
    parser.add_argument("--cache-dir", default=str(CACHE_DIR),
                        help="DexSQL 缓存目录（默认 tools/.dexsql-cache）")
    args = parser.parse_args()

    apk = Path(args.apk).expanduser()
    if not apk.is_file():
        raise SystemExit("ERROR: APK not found: %s" % apk)

    dexsql_path = resolve_dexsql(args.dexsql)
    aapt = resolve_aapt(args.aapt2)

    app_version, version_code = read_apk_version(apk, aapt)
    if not app_version:
        if args.app_version:
            app_version = args.app_version
            print("WARN: 无法从 APK 读取 versionName，使用 --app-version=%s" % app_version,
                  file=sys.stderr)
        else:
            raise SystemExit("ERROR: 无法从 APK 读取 versionName；请安装 aapt2 或传 --app-version")
    if not version_code:
        if args.version_code:
            version_code = args.version_code
            print("WARN: 无法从 APK 读取 versionCode，使用 --version-code=%s" % version_code,
                  file=sys.stderr)
        else:
            raise SystemExit("ERROR: 无法从 APK 读取 versionCode；请安装 aapt2 或传 --version-code")
    try:
        version_code_value = int(str(version_code).strip())
    except ValueError:
        raise SystemExit("ERROR: versionCode 不是整数: %r" % version_code)

    print("APK: %s" % apk)
    print("appVersion: %s (versionCode %d)" % (app_version, version_code_value))
    print("dexsql: %s" % dexsql_path)

    cache_file = ensure_cache(dexsql_path, apk, args.cache_dir)
    print("cache: %s" % cache_file)

    anchors = {}
    missing = []
    for key, meta in ANCHORS.items():
        for field in ("c", "m", "s", "feature", "note"):
            value = meta.get(field)
            if not isinstance(value, str) or not value:
                raise SystemExit("ERROR: anchor %s 缺少字符串字段 %s" % (key, field))
        if not isinstance(meta.get("critical"), bool):
            raise SystemExit("ERROR: anchor %s 的 critical 必须是布尔值" % key)
        cls, name, sig = meta["c"], meta["m"], meta["s"]
        if sig.startswith("("):
            table = "methods"
            full = "L%s;->%s%s" % (cls.replace(".", "/"), name, sig)
        else:
            table = "fields"
            full = "L%s;->%s:%s" % (cls.replace(".", "/"), name, sig)
        try:
            count = query_count(
                dexsql_path, cache_file,
                "SELECT count(*) AS c FROM %s WHERE descriptor='%s'" % (table, full))
        except RuntimeError as exc:
            raise SystemExit("ERROR: dexsql failed for anchor %s: %s" % (key, exc))
        if count > 0:
            anchors[key] = {
                "c": cls,
                "m": name,
                "s": sig,
                "feature": meta["feature"],
                "critical": meta["critical"],
                "note": meta["note"],
            }
        else:
            missing.append((key, full))
            print("MISSING %s: %s" % (key, full))

    classes = {}
    for key, cls in CLASSES.items():
        try:
            count = query_count(
                dexsql_path, cache_file,
                "SELECT count(*) AS c FROM types WHERE java_name='%s'" % cls)
        except RuntimeError as exc:
            raise SystemExit("ERROR: dexsql failed for class %s: %s" % (key, exc))
        if count > 0:
            classes[key] = cls
        else:
            missing.append((key, cls))
            print("MISSING class %s: %s" % (key, cls))

    out_path = Path(args.out).expanduser()
    if missing:
        print("ERROR: %d 个锚点/类缺失，未写入 %s（保留原文件）"
              % (len(missing), out_path), file=sys.stderr)
        return 1

    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "app": TARGET_PACKAGE,
        "appVersion": app_version,
        "versionCode": version_code_value,
        "generatedBy": "tools/gen_anchors.py",
        "anchors": anchors,
        "classes": classes,
    }

    keys_path = Path(args.keys_out).expanduser() if args.keys_out else default_keys_path(out_path)
    write_text_atomic(out_path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    if keys_path is not None:
        write_text_atomic(keys_path, anchor_keys_source(list(anchors), list(classes)))
        print("wrote %s (%d keys)" % (keys_path, len(anchors) + len(classes)))

    print("wrote %s (%d anchors, %d classes)" % (out_path, len(anchors), len(classes)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
