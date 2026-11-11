# 评审脚本（UI 品味评审用）

用 [mobilewright](https://mobilewright.dev)（Mobile MCP 同一引擎）驱动 Android 设备，
配合像素测量脚本做「有证据」的 UI 评审。产物默认落在 `artifacts/review/`（被 `.gitignore` 覆盖）。

## 依赖

```bash
npm install --no-save mobilewright pngjs @mobilenext/mobilecli-windows-amd64
```

> 注意：`npm install --no-save` 每次只保留本次列出的包，**要一次装齐**，
> 否则上一次装的会被删掉（本轮踩过：装 pngjs 时把 mobilewright 清掉了）。
> `@mobilenext/mobilecli-windows-amd64` 是 mobilecli 的平台二进制，缺了会报
> `Failed to find @mobilenext/mobilecli-windows-amd64`。

## 驱动设备

```bash
node scripts/review/drive.mjs dump home          # 截图 + 可访问性树 + 可读行清单
node scripts/review/drive.mjs tap "@搜索"        # 按 label 点击（@=label，#=testId，其余=text）
node scripts/review/drive.mjs tapxy 540 1600     # 坐标点击
node scripts/review/drive.mjs longpress 500 390  # 长按
node scripts/review/drive.mjs swipe up           # 滑动
node scripts/review/drive.mjs back               # 系统返回
node scripts/review/drive.mjs launch             # 启动 App
```

产物：`artifacts/review/<name>.png` / `.tree.json`（原始树）/ `.lines.json`（拍平的可读行）。

## 像素测量

```bash
# 区域对比度：自动找出该区域里「出现最多的底色」与「对比最强的文字色」并算 WCAG 比值
node scripts/review/region.mjs artifacts/review/10-home.png 148 418 860 452

# 单点取色
node scripts/review/contrast.mjs artifacts/review/10-home.png 540 300

# 两色直接算比值
node scripts/review/contrast.mjs --ratio '#9AA1AE' '#FFFFFF'

# 扫描一条线上的颜色分段（量卡片边界、圆角、间距）
node scripts/review/scan.mjs artifacts/review/13-home-more.png v 540
node scripts/review/scan.mjs artifacts/review/10-home.png x 184
```

## 口径提醒

- 设备是 1080×2400 / density 420，**dp = px / 2.625**。别按 3.0 换算。
- 截图顶部若出现 `P:0/1 dX:0.0 …` 那一行，是模拟器的「指针位置」开发者选项，
  不是 App 的 UI：`adb shell settings put system pointer_location 0` 关掉。
- `region.mjs` 报的 `fg` 是「该区域内对比最强的那个色」，若区域内混了多个文字色，
  要缩小矩形重测，别直接当结论。
- 暗色验证要两轮：运行时 `cmd uimode night yes` + **冷启动重启**（有些实现只在重建时读）。
