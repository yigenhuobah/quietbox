#!/usr/bin/env bash
# 混淆挂载点体检：小红书升级后跑这个，30 秒内知道哪些挂载点还活着。
#
# 用法：
#   bash check-mounts.sh <新版apk路径> [缓存输出目录]
#
# 例：
#   bash check-mounts.sh "E:\tmp-xhs-mod\xhs951\xhs-9.51.0.apk"
#
# 依赖：E:\tools\dexsql\dexsql.exe（首次会为这个 apk 建缓存，约 40 秒）
#
# 判定规则：
#   OK   = 类和方法都在，且方法签名形状没变，hook 大概还能挂上
#   GONE = 类不存在了（R8 改了混淆名），需要按同目录的复核报告重新定位
#   SIG  = 类还在但方法签名变了，需要看具体差异再决定
#
# 每个条目后面是复核报告路径，里面有"如何重新定位"的线索。

set -u

APK="${1:-}"
OUT="${2:-}"

if [ -z "$APK" ]; then
  echo "用法: bash check-mounts.sh <apk路径> [缓存目录]" >&2
  exit 2
fi

DEXSQL="E:/tools/dexsql/dexsql.exe"
if [ ! -x "$DEXSQL" ]; then
  DEXSQL="E:\\tools\\dexsql\\dexsql.exe"
fi
[ -f "$DEXSQL" ] || { echo "找不到 dexsql: $DEXSQL" >&2; exit 2; }

[ -z "$OUT" ] && OUT="$(dirname "$APK")/dexsql-cache"
CACHE="$OUT/cache.dxc2"

if [ ! -f "$CACHE" ]; then
  echo "== 首次为该 apk 建 DexSQL 缓存（约 40 秒）..."
  "$DEXSQL" prepare-cache --input "$APK" --output "$OUT" --trusted-input >/dev/null 2>&1
  [ -f "$CACHE" ] || { echo "建缓存失败" >&2; exit 1; }
fi

q() { "$DEXSQL" query-cache --cache-file "$CACHE" --trusted-input --sql "$1" 2>/dev/null; }

# 挂载点清单：类描述符|方法名|功能|复核报告
MOUNTS='Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBSplashAdsV2|P0-1 内嵌开屏|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBRedSplashAdsV2|P0-1 红开屏|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openLCBBrandMaxSplashAds|P0-1 品牌开屏|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openBrandMax|P0-1 品牌开屏|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|openNeedLazyHFSplashAds|P0-1 懒加载开屏|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|startAnim|P0-1 开屏动画|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|canLazyOpenSplash|P0-1 开屏决策|splash_ad_path
Lcom/xingin/advert/spi/AdsIndexSpiImp;|canNeedNewCloser|P0-1 开屏决策|splash_ad_path
Lcom/xingin/advert/intersitial/ui/a;|d|P0-1 全屏开屏出口|splash_ad_path
Lcom/xingin/advert/intersitial/ui2/view/SplashAdActivity$a;|a|P0-1 全屏开屏兜底|splash_ad_path
Lcom/xingin/advert/intersitial/ui2/view/dynamic/SplashDynamicActivity$a;|a|P0-1 动态开屏兜底|splash_ad_path
Lcom/xingin/advert/intersitial/ui2/cny/SplashCNYActivity$a;|a|P0-1 CNY 开屏兜底|splash_ad_path
Lcom/xingin/advert/intersitial/ui/InterstitialAdsActivity$a;|a|P0-1 插屏开屏兜底|splash_ad_path
Lcom/xingin/advert/intersitial/ui/SplashAdPlaceHolderView;|onCreateView|P0-1 冷启动占位|splash_ad_path
Lcom/xingin/advert/spi/AdsHomeFeedManagerServiceImpl;|handleFeedDataIfNeed|P0-2 2-pos 比对|verify_p0_2
Lcom/xingin/advert/spi/AdsHomeFeedManagerServiceImpl;|recordAdsNoteData|P0-2 2-pos 记录|verify_p0_2
Lr7d/m0;|e0|P0-2 广告插入守卫|verify_p0_2
Lr7d/m0;|Q|P0-2 列表追加过滤|verify_p0_2
Lr7d/m0;|A0|P0-2 列表刷新过滤|verify_p0_2
Lm7d/s;|Yd|P0-2 分页上游过滤|verify_p0_2
Lcom/xingin/advert/spi/AdsCommentComponentBinderCreatorImpl;|commentAdvertComponentDSLEnable|P0-3 评论广告|specs
Lcom/xingin/update/UpdateDialogActivity;|onCreate|P0-4 更新弹窗|specs
Lcom/xingin/update/UpdateDialogActivity;|he|P0-4 更新弹窗补充|specs
Lcom/xingin/advert/spi/AdvertSpiImpl;|gotoExtApp|P1-7 外跳主挂|verify_p1_7
Lp0e/i0;|b|P1-7 外跳汇聚|verify_p1_7
Le0b/e;|b|P1-9 剪贴板写入|verify_p1_9
Lj92/q;|a|P1-9 复制链接收口|verify_p1_9
Lnd8/j;|a|P1-11 水印主挂|verify_watermark
Lnd8/i;|g|P1-11 水印入口|verify_watermark
Lcom/xingin/entities/MediaSaveConfig;|c|P1-11 语义 getter|verify_watermark
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateHelper;|isAutoPlayOpen|P1-12 自动播放|specs
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateHelper;|isAutoPlayOpenServer|P1-12 自动播放|specs
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateTabHelper;|isAutoPlayOpen|P1-12 自动播放|specs
Lcom/xingin/detailfeed/services/autoscroll/AutoScrollStateTabHelper;|isAutoPlayOpenServer|P1-12 自动播放|specs
Lfl0/f0;|o|P1-13 搜索广告|verify_p1_13
Lcom/xingin/matrix/notedetail/asyncwidgets/advert/AdvertV2View;|a|P1-14 详情广告条|verify_p1_14
Lcom/xingin/matrix/notedetail/asyncwidgets/advert/AdvertV2View;|b|P1-14 详情广告条|verify_p1_14'

echo
echo "APK: $APK"
echo "缓存: $CACHE"
echo
printf '%-8s %-64s %s\n' "状态" "挂载点" "功能"
printf '%.0s-' {1..110}; echo

bad=0
echo "$MOUNTS" | while IFS='|' read -r cls mth feat rep; do
  [ -z "$cls" ] && continue
  cls_hit=$(q "SELECT count(*) FROM classes WHERE descriptor='$cls'" | grep -oE '^\| [0-9]+' | tr -d '| ')
  if [ "${cls_hit:-0}" = "0" ]; then
    printf '%-8s %-64s %s\n' "GONE" "${cls#L}" "$feat  → 报告: $rep"
    continue
  fi
  mth_hit=$(q "SELECT count(*) FROM methods WHERE class_descriptor='$cls' AND name='$mth'" | grep -oE '^\| [0-9]+' | tr -d '| ')
  if [ "${mth_hit:-0}" = "0" ]; then
    printf '%-8s %-64s %s\n' "SIG" "${cls#L}#$mth  (类在、方法没了)" "$feat  → 报告: $rep"
    continue
  fi
  printf '%-8s %-64s %s\n' "OK" "${cls#L}#$mth" "$feat"
done

echo
echo "OK=还在；GONE=混淆名变了，要重新定位；SIG=方法名变了，看报告里的链路。"
echo "复核报告都在 E:\\tmp-xhs-mod\\agy-scratch\\ 下。"
