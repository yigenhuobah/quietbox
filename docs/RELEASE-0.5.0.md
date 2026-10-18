# 静盒 0.5.0

## 新增

- 精选实验从 19 项扩到 21 项：
  - **P3 图片预加载**（`andr_note_detail_preload_p3_image_request`）：
    详情页 P3 色域图片提前请求，打开图片笔记更快。
  - **解除 2K/4K 码流过滤**（`andr_player_intelligent_rep_select_disable_filter_2k_4k`）：
    允许播放器选择 2K/4K 清晰度；高分辨率屏幕、网络好时再开。
- 「测试」页新增**导出全量实验**：生成 CSV（全量 5,760 个 key + 当前探测值），
  通过系统文件选择器保存到本地，不需要存储权限。
  CSV 字段：`key, current_value, status, primary_category, recommendation,`
  `curated, force_value, curated_title`。

## 修复

- 「字幕新样式」的 AB key 修正为 `caption_new_style_v3`
  （v0.4.0 误写成 `andr_caption_new_style_v3`，导致该开关无效）。

## 说明

- 全量导出包含 9.50.0 APK 的 5,760 个 AB key；本次没读到的 key 也会出现，
  `current_value` 为空、`status` 为「未读取」。
- 导出不包含代码读取点（reader class/method），以保持模块体积和公开数据克制；
  需要完整字段可以用 PC 端的导出脚本。
- 新增的 2 项都是默认关闭/0 的实验，强开才会改变行为；2K/4K 码流过滤在
  网络差或屏幕不支持时可能造成卡顿，建议逐项验证。

## 安装包

- `quietbox-0.5.0.apk`，2,555,756 字节
- SHA-256：`f418b3e025bae58d9910c2ff2146b7d7b311d0e43ec14175d1fdd2a8450647fd`
