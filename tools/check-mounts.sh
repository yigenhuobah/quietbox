#!/usr/bin/env bash
# 混淆挂载点体检：小红书升级后跑这个，30 秒内知道哪些挂载点还活着。
#
# 用法：
#   tools/check-mounts.sh [APK路径] [--apk APK] [--dexsql PATH] [--aapt2 PATH]
#
# 参数优先级：CLI > 环境变量 > 常见安装路径。
# 环境变量：
#   TARGET_APK / APK  目标 APK（小红书 9.51.0）
#   DEXSQL            dexsql 可执行文件
#   AAPT2             aapt2 可执行文件（可选，仅用于显示 APK versionName）
#
# 缓存固定在 <repo>/tools/.dexsql-cache/（已在 .gitignore 中忽略）。
# 缓存记录了 APK 路径；换 APK 会自动重建，不需要手工删缓存。
#
# 判定规则：
#   OK   = 类和方法都在，hook 大概还能挂上
#   GONE = 类不存在了（R8 改了混淆名），需要按同目录的复核报告重新定位
#   SIG  = 类还在但方法名变了，需要看具体差异再决定
#
# 退出码：0 全部 OK；1 存在 GONE/SIG 或建缓存失败；2 参数/工具/APK 缺失。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$REPO_ROOT/tools/.dexsql-cache"

TARGET_APK="${TARGET_APK:-${APK:-}}"
DEXSQL="${DEXSQL:-}"
AAPT2="${AAPT2:-}"
POSITIONAL=""

usage() {
    cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--dexsql PATH] [--aapt2 PATH]

Arguments:
  APK_PATH / --apk   目标 APK（小红书 9.51.0）；也可用 TARGET_APK / APK
  --dexsql PATH      dexsql 可执行文件；也可用 DEXSQL
  --aapt2 PATH       aapt2 可执行文件；也可用 AAPT2（可选，仅显示 versionName）
  -h, --help         显示本帮助

Resolution order: CLI > environment > common install paths.
Cache: <repo>/tools/.dexsql-cache/
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            [ $# -ge 2 ] || die "--apk needs a value"
            TARGET_APK="$2"; shift 2 ;;
        --apk=*)
            TARGET_APK="${1#*=}"; shift ;;
        --dexsql)
            [ $# -ge 2 ] || die "--dexsql needs a value"
            DEXSQL="$2"; shift 2 ;;
        --dexsql=*)
            DEXSQL="${1#*=}"; shift ;;
        --aapt2)
            [ $# -ge 2 ] || die "--aapt2 needs a value"
            AAPT2="$2"; shift 2 ;;
        --aapt2=*)
            AAPT2="${1#*=}"; shift ;;
        -h|--help)
            usage; exit 0 ;;
        -*)
            echo "ERROR: unknown option $1" >&2
            usage >&2
            exit 2 ;;
        *)
            if [ -n "$POSITIONAL" ]; then
                die "unexpected argument $1"
            fi
            POSITIONAL="$1"; shift ;;
    esac
done

# ---- 工具定位辅助 ---------------------------------------------------------
is_executable_file() {
    local path="$1"
    [ -n "$path" ] && [ -f "$path" ] || return 1
    case "$path" in
        *.exe|*.EXE) return 0 ;;
    esac
    [ -x "$path" ]
}

first_executable() {
    local candidate
    for candidate in "$@"; do
        if is_executable_file "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# SDK 根目录：环境变量、local.properties、常见安装位置（不写死机器路径）。
sdk_roots() {
    local value line
    for value in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$value" ] && printf '%s\n' "$value"
    done
    if [ -f "$REPO_ROOT/local.properties" ]; then
        line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' \
            "$REPO_ROOT/local.properties" | tail -n 1 || true)"
        value="${line#*=}"
        value="$(printf '%s' "$value" \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
                  -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
        [ -n "$value" ] && printf '%s\n' "$value"
    fi
    printf '%s\n' \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "$HOME/AppData/Local/Android/Sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"
}

# ---- APK 定位 -------------------------------------------------------------
if [ -n "$POSITIONAL" ]; then
    TARGET_APK="$POSITIONAL"
fi
if [ -z "$TARGET_APK" ]; then
    for candidate in \
        "$PWD"/base*.apk \
        "$HOME/Downloads"/base*.apk \
        "$HOME/Downloads"/*xhs*.apk \
        "$HOME/Downloads"/*xiaohongshu*.apk \
        "$HOME/Desktop"/base*.apk; do
        if [ -f "$candidate" ]; then
            TARGET_APK="$candidate"
            echo "APK (auto-detected): $TARGET_APK" >&2
            break
        fi
    done
fi

# ---- dexsql 定位 ----------------------------------------------------------
if [ -z "$DEXSQL" ]; then
    if command -v dexsql >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql)"
    elif command -v dexsql.exe >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql.exe)"
    else
        DEXSQL="$(first_executable \
            "$REPO_ROOT/tools/dexsql/dexsql" \
            "$REPO_ROOT/tools/dexsql/dexsql.exe" \
            "$REPO_ROOT/tools/dexsql.exe" \
            "$HOME/tools/dexsql/dexsql" \
            "$HOME/tools/dexsql/dexsql.exe" \
            "$HOME/.local/bin/dexsql" \
            "$HOME/bin/dexsql" \
            "/usr/local/bin/dexsql" \
            "/opt/dexsql/dexsql" || true)"
    fi
fi

# ---- aapt2 定位（可选） ---------------------------------------------------
if [ -z "$AAPT2" ]; then
    if command -v aapt2 >/dev/null 2>&1; then
        AAPT2="$(command -v aapt2)"
    else
        while IFS= read -r sdk_root; do
            [ -n "$sdk_root" ] || continue
            AAPT2="$(first_executable \
                "$sdk_root"/build-tools/*/aapt2.exe \
                "$sdk_root"/build-tools/*/aapt2 || true)"
            [ -n "$AAPT2" ] && break
        done < <(sdk_roots)
    fi
fi

# ---- 输入校验 -------------------------------------------------------------
if [ -z "$TARGET_APK" ] || [ ! -f "$TARGET_APK" ]; then
    echo "ERROR: APK not found: ${TARGET_APK:-<unset>}" >&2
    echo "  Fix: pass --apk /path/to/xhs.apk, or export TARGET_APK=/path/to/xhs.apk," >&2
    echo "       or place base*.apk in the current directory / ~/Downloads." >&2
    exit 2
fi
if [ -z "$DEXSQL" ] \
    || { ! is_executable_file "$DEXSQL" && ! command -v "$DEXSQL" >/dev/null 2>&1; }; then
    echo "ERROR: dexsql not found: ${DEXSQL:-<unset>}" >&2
    echo "  Fix: install dexsql and put it on PATH, or pass --dexsql /path/to/dexsql," >&2
    echo "       or export DEXSQL=/path/to/dexsql." >&2
    exit 2
fi
if [ -n "$AAPT2" ] && ! is_executable_file "$AAPT2"; then
    echo "WARN: aapt2 is not executable: $AAPT2; skipping versionName display" >&2
    echo "      Fix: pass --aapt2 /path/to/aapt2 or set ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    AAPT2=""
fi

echo
echo "APK: $TARGET_APK"
if is_executable_file "$AAPT2"; then
    VERSION_NAME="$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null \
        | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    if [ -n "$VERSION_NAME" ]; then
        echo "versionName: $VERSION_NAME"
        [ "$VERSION_NAME" = "9.51.0" ] \
            || echo "WARN: expected 9.51.0, got $VERSION_NAME" >&2
    else
        echo "WARN: cannot read versionName from $TARGET_APK" >&2
    fi
else
    echo "WARN: aapt2 not found; skipping versionName display" >&2
    echo "      Fix: pass --aapt2 /path/to/aapt2, export AAPT2=..., or set ANDROID_HOME/ANDROID_SDK_ROOT."
fi

# ---- 缓存 -----------------------------------------------------------------
# 固定缓存在仓库内，避免把 dexsql 缓存写进 APK 所在目录或系统临时目录。
if ! mkdir -p "$CACHE_DIR"; then
    echo "ERROR: cannot create cache dir: $CACHE_DIR" >&2
    exit 2
fi
CACHE="$CACHE_DIR/cache.dxc2"
META="$CACHE_DIR/apk.path"
APK_ABS="$(cd "$(dirname "$TARGET_APK")" 2>/dev/null && pwd)/$(basename "$TARGET_APK")"

CACHE_REUSABLE=0
if [ -f "$CACHE" ] && [ -f "$META" ] && [ "$(cat "$META")" = "$APK_ABS" ]; then
    CACHE_REUSABLE=1
fi
if [ "$CACHE_REUSABLE" -eq 0 ]; then
    rm -rf "$CACHE_DIR"
    mkdir -p "$CACHE_DIR" || {
        echo "ERROR: cannot create cache dir: $CACHE_DIR" >&2
        exit 2
    }
    printf '%s\n' "$APK_ABS" > "$META"
    echo "== 首次为该 apk 建 DexSQL 缓存（约 40 秒）..."
    if ! "$DEXSQL" prepare-cache --input "$TARGET_APK" --output "$CACHE_DIR" \
            --trusted-input > "$CACHE_DIR/prepare.log" 2>&1; then
        echo "ERROR: dexsql prepare-cache failed" >&2
        sed -n '1,20p' "$CACHE_DIR/prepare.log" >&2
        exit 1
    fi
    if [ ! -f "$CACHE" ]; then
        echo "ERROR: dexsql did not create cache: $CACHE" >&2
        sed -n '1,20p' "$CACHE_DIR/prepare.log" >&2
        exit 1
    fi
fi
echo "缓存: $CACHE"
echo

q() { "$DEXSQL" query-cache --cache-file "$CACHE" --trusted-input --sql "$1" 2>/dev/null; }

# 挂载点清单：类描述符|方法名|功能|复核报告|参数个数（可选，空=不校验）
# 第 5 列填了数字时，用 methods 表的 param_count 做"签名形状"校验：
# 方法还在但参数个数变了 → SIG。没有数据的条目留空，不做该层校验。
MOUNTS='Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBSplashAdsV2|P0-1 内嵌开屏|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBRedSplashAdsV2|P0-1 红开屏|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBBrandMaxSplashAds|P0-1 品牌开屏|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openBrandMax|P0-1 品牌开屏|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openNeedLazyHFSplashAds|P0-1 懒加载开屏|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|startAnim|P0-1 开屏动画|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|canLazyOpenSplash|P0-1 开屏决策|splash_ad_path|
Lcom/xingin/advert/spi/AdsIndexSpiImp;|canNeedNewCloser|P0-1 开屏决策|splash_ad_path|
Lcom/xingin/advert/intersitial/ui/a;|d|P0-1 全屏开屏出口|splash_ad_path|
Lcom/xingin/advert/intersitial/ui2/view/SplashAdActivity$a;|a|P0-1 全屏开屏兜底|splash_ad_path|
Lcom/xingin/advert/intersitial/ui2/view/dynamic/SplashDynamicActivity$a;|a|P0-1 动态开屏兜底|splash_ad_path|
Lcom/xingin/advert/intersitial/ui2/cny/SplashCNYActivity$a;|a|P0-1 CNY 开屏兜底|splash_ad_path|
Lcom/xingin/advert/intersitial/ui/InterstitialAdsActivity$a;|a|P0-1 插屏开屏兜底|splash_ad_path|
Lcom/xingin/advert/intersitial/ui/SplashAdPlaceHolderView;|onCreateView|P0-1 冷启动占位|splash_ad_path|
Lcom/xingin/advert/spi/AdsHomeFeedManagerServiceImpl;|handleFeedDataIfNeed|P0-2 2-pos 比对|verify_p0_2|
Lcom/xingin/advert/spi/AdsHomeFeedManagerServiceImpl;|recordAdsNoteData|P0-2 2-pos 记录|verify_p0_2|
Ll8d/m0;|e0|P0-2 广告插入守卫|verify_p0_2|
Ll8d/m0;|Q|P0-2 列表追加过滤|verify_p0_2|
Ll8d/m0;|A0|P0-2 列表刷新过滤|verify_p0_2|
Lg8d/s;|Yd|P0-2 分页上游过滤|verify_p0_2|
Lcom/xingin/advert/spi/AdsCommentComponentBinderCreatorImpl;|commentAdvertComponentDSLEnable|P0-3 评论广告|specs|
Lcom/xingin/update/UpdateDialogActivity;|onCreate|P0-4 更新弹窗|specs|
Lcom/xingin/update/UpdateDialogActivity;|he|P0-4 更新弹窗补充|specs|
Lcom/xingin/advert/spi/AdvertSpiImpl;|gotoExtApp|P1-7 外跳主挂|verify_p1_7|
Lk1e/i0;|b|P1-7 外跳汇聚|verify_p1_7|
Lu0b/e;|b|P1-9 剪贴板写入|verify_p1_9|
Lo92/q;|a|P1-9 复制链接收口|verify_p1_9|
Lce8/j;|a|P1-11 水印主挂|verify_watermark|6
Lce8/i;|i|P1-11 水印入口|verify_watermark|11
Lcom/xingin/entities/MediaSaveConfig;|c|P1-11 语义 getter|verify_watermark|
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateHelper;|isAutoPlayOpen|P1-12 自动播放|specs|
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateHelper;|isAutoPlayOpenServer|P1-12 自动播放|specs|
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateTabHelper;|isAutoPlayOpen|P1-12 自动播放|specs|
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateTabHelper;|isAutoPlayOpenServer|P1-12 自动播放|specs|
Lhl0/f0;|o|P1-13 搜索广告|verify_p1_13|
Lcom/xingin/matrix/notedetail/asyncwidgets/advert/AdvertV2View;|a|P1-14 详情广告条|verify_p1_14|
Lcom/xingin/matrix/notedetail/asyncwidgets/advert/AdvertV2View;|b|P1-14 详情广告条|verify_p1_14|
Lsr9/o;|invoke|P1-15 已删除笔记查看|verify_p1_15|
Lcom/xingin/lurker/appscore/ui/AppScoreDialogActivity;|onCreate|P1-16 评分弹窗|verify_p1_16|
Lcom/xingin/xhs/app/ShareApplication;|tryLaunchGpReview|P1-16 系统评分|verify_p1_16|
Lcom/xingin/spi/IGrowthCupidProxyImpl;|pushGuideDialogDialogShow|P1-17 通知引导|verify_p1_17|
Lcom/xingin/spi/IGrowthCupidProxyImpl;|showGuidePopupWindow|P1-17 通知引导|verify_p1_17|
Lcom/xingin/spi/IGrowthCupidProxyImpl;|showPushGuidShowForInside|P1-17 通知引导|verify_p1_17|
Lcom/xingin/spi/IGrowthCupidProxyImpl;|openPushGuideActivity|P1-17 通知引导|verify_p1_17|
Lcom/xingin/growth/snackbar/ContentRatingProxyImpl;|shouldShowInternal|P1-18 满意度调研|verify_p1_18|
Lcom/xingin/growth/snackbar/ContentRatingProxyImpl;|showForVideo|P1-18 满意度兜底|verify_p1_18|
Lcom/xingin/growth/snackbar/ContentRatingProxyImpl;|showForImageText|P1-18 满意度兜底|verify_p1_18|
Lcom/xingin/growth/snackbar/ContentRatingProxyImpl;|showContentRatingSnackBarIfNeeded|P1-18 满意度公开入口|verify_p1_18|
Lcom/xingin/login/manager/UploadContactServiceExtension;|start|P1-19 通讯录同步|verify_p1_19|
Lcom/xingin/login/manager/UploadContactServiceExtension;|requestContactsSync|P1-19 通讯录同步|verify_p1_19|
Lcom/xingin/login/manager/UploadContactServiceExtension;|installLifecycleObserver|P1-19 通讯录同步|verify_p1_19|
Lcom/xingin/commercial/vision/post/spi/GoodsCommentAddInProcessor;|startAddIn|P1-20 评论带货|verify_p1_20|
Ll8d/m0;|Q|P1-21/22/23 直播/带货/投票卡过滤|verify_p1_21|
Ll8d/m0;|A0|P1-21/22/23 直播/带货/投票卡过滤|verify_p1_21|
Lg8d/s;|Yd|P1-21/22/23 直播/带货/投票卡过滤|verify_p1_21|
Ltq/h;|f|P2-1 AB 读取 getValueJustOnce|verify_p2_1|
Ltq/h;|b|P2-1 AB 读取 getValue|verify_p2_1|
Lmb6/o;|l|P2-1 ConfigCenter 取值|verify_p2_1|'

printf '%-8s %-64s %s\n' "状态" "挂载点" "功能"
printf '%.0s-' {1..110}; echo

bad=0
while IFS='|' read -r cls mth feat rep pc; do
    [ -z "$cls" ] && continue
    cls_hit=$(q "SELECT count(*) FROM classes WHERE descriptor='$cls'" \
        | grep -oE '^\| [0-9]+' | tr -d '| ')
    if [ "${cls_hit:-0}" = "0" ]; then
        printf '%-8s %-64s %s\n' "GONE" "${cls#L}" "$feat  → 报告: $rep"
        bad=$((bad+1))
        continue
    fi
    mth_hit=$(q "SELECT count(*) FROM methods WHERE class_descriptor='$cls' AND name='$mth'" \
        | grep -oE '^\| [0-9]+' | tr -d '| ')
    if [ "${mth_hit:-0}" = "0" ]; then
        printf '%-8s %-64s %s\n' "SIG" "${cls#L}#$mth  (类在、方法没了)" "$feat  → 报告: $rep"
        bad=$((bad+1))
        continue
    fi
    # 可选第 5 列：填了数字就做参数个数校验（同名重载任一匹配即可）。
    if [ -n "$pc" ]; then
        pc_hit=$(q "SELECT count(*) FROM methods WHERE class_descriptor='$cls' AND name='$mth' AND param_count=$pc" \
            | grep -oE '^\| [0-9]+' | tr -d '| ')
        if [ "${pc_hit:-0}" = "0" ]; then
            actual_pc=$(q "SELECT DISTINCT param_count FROM methods WHERE class_descriptor='$cls' AND name='$mth'" \
                | grep -oE '[0-9]+' | paste -sd, -)
            printf '%-8s %-64s %s\n' "SIG" \
                "${cls#L}#$mth  (参数个数变了，期望 $pc，实际 ${actual_pc:-?})" \
                "$feat  → 报告: $rep"
            bad=$((bad+1))
            continue
        fi
    fi
    printf '%-8s %-64s %s\n' "OK" "${cls#L}#$mth" "$feat"
done <<< "$MOUNTS"

echo
echo "OK=还在；GONE=混淆名变了，要重新定位；SIG=方法名变了，看报告里的链路。"
echo "复核报告路径见上表第四列（research/agy-scratch/，仅本地维护）。"

echo
echo "汇总：GONE/SIG = $bad"
[ "$bad" -eq 0 ] || exit 1
