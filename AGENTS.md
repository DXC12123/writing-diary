# AGENTS.md

给任何接手这个项目的 AI / 开发者看的第一份文件。

## 先读这个

- 产品与需求的唯一事实来源：`PRD.html`（目前 v0.9.2，as-built）。**改动任何功能前，先读对应章节。**
- 使用方式与数据位置：`README.md`
- 项目名：**小鲸鱼**（应用名、浏览器标题、关于页、安卓 `app_name` 都叫这个）

## 项目是什么

单文件网页版「小鲸鱼 · 写作日记」：九宫格问题提示 → 每天写一篇 → 提交后永久只读 → 按日 / 月 / 年回顾。
本地单机，无账号、无服务端、无第三方依赖；另有一层安卓 WebView 外壳（`android-app/`）用于装到手机。

## 硬约束（不要违反）

1. **单文件 `index.html`**：结构、样式、逻辑全在这一个文件里。不引入构建工具、npm 包、CDN、外部字体。
2. **数据只存 localStorage**：日记与偏好 `writing-diary:v1`，壁纸单独 `writing-diary:wallpaper`。不新增任何网络请求。
3. **提交锁定不可逆**：`status: "locked"` 在界面与数据层都没有回滚路径，不要加「解锁」入口。
4. **只写「今天」**：不做补写历史日期、不做日期选择器。
5. **不做**：账号、云同步、富文本、图片/语音、打卡连续天数、埋点上报。
6. **可读性**：正文与背景对比度 ≥ 4.5:1。皮肤有六套（`paper` / `cozy` / `focus` / `press` / `cute` / `tech`，其中 `cozy`、`tech` 是深色），改任何颜色都要六套一起测。浅色的 `cute` 主色太浅，当文字色时必须用 `--accent-ink`。

## 代码地图（都在 index.html）

- 常量与工具：`KEY`、`pad`、`dkey`、`wc()`（字数 = 去掉所有空白字符）、`SAMPLE_Q`（默认 9 条问题）、`RANDOM_Q_RAW`（10200 条提示，分 15 段写死：v0.8 的 10 段各 520 条 + v0.9 的 5 段各 1000 条）+ `RANDOM_Q`（加载时去重后的成品数组，10194 条；去重键会忽略空格与标点）
- 随机问题：`randomQuestions(silent)`（洗牌抽 9 条不重复，只改 `S.questions`，不动 `S.entries`）、`questionsROBody()` / `openQuestionsRO()` / `refreshQuestionsRO()`（浮层原地刷新，保住滚动位置、不重播动画）；入口 act 是 `random-q`（浮层内）与 `random-q-board`（问题板）
- 数据层：`load()` / `persist()` / `persistSoon()`（400ms 防抖）/ `sweepDrafts()`（跨天自动锁定，`lockedAt` 记为当日 23:59）
- 视图：`viewQuestions()` / `viewWrite()` / `viewReview()`，日历分别由 `calDay()` / `calMonth()` / `calYear()` 生成
- 回看助手：`pastBlock(k)`（「往年的今天」，`sameDayPast()` 取同 `MM-DD` 且更早的条目、最多 3 条）、`entryNav(k)`（详情页「上一篇 / 下一篇」，`neighbors()` 取相邻有内容的日期）、`snippet()`（行内 22 字摘要）
- 浮层：`openSheet()` / `openPage()` / `closeLayer()`（用 `layerToken` 防关闭动画与新浮层竞态）
- 外观：`settingsBody()`、`SKINS`（六套皮肤的 id / 名称 / 一句说明 / theme-color）、`DARK_SKIN`（哪些皮肤算深色）、`applySkin()`（把 `settings.skin` 写到 `<html data-skin>` 并更新 theme-color，再调 `applyWall()`）、`setSkin(k)`（持久化 + 换肤 + 重绘设置 + Toast）、`skinTiles()`（生成设置页 2×3 小样）、`fsRow()` / `applyFs()` / `setFs(v)`（正文字号三档：`settings.fs` 写进 `<html data-fs>` 的 `--fs` 比例 0.93 / 1 / 1.12，只作用于写作框与详情正文）、`applyWall()` / `onWallpaperFile()`（canvas 压到长边 1600px、JPEG 0.82）
- 皮肤的实现方式：每套皮肤 = `<style>` 里一整块 `[data-skin="…"]{ 27 个 CSS 变量 }` + 一小段同名选择器的结构覆盖（圆角 / 描边 / 字体 / 贴纸）；同一套选择器也复用到 `.skin-pv[data-skin]` 上做小样，所以小样和真皮肤永远一致
- 备份：`exportText()` / `exportJSON()` / `download()`（有 `window.AndroidHost.saveFile` 时改走原生保存）、`parseBackup(txt)`（兼容数组与按日期索引两种 `entries`、过滤空正文与非法日期）、`onImportFile(ev)`、`openImportConfirm(name)`（导入预览浮层）、`doImport(mode)`（`'merge'` 只补空缺 / `'replace'` 清空重写），入口是隐藏的 `#impfile`
- 交互：document 级 `click` 委托，按 `data-act` 分发；`#ta` 的 `input` 事件负责自动存草稿
- 写作小贴士：`TIPS`（9 篇静态文本，`{sec,t,s,b:[3 段],try}`）、`SEC_NAME`、`openTips()`（目录）、`openTip(i)`（正文 + 上一篇/下一篇）；入口在 `viewQuestions()` 末尾的 `.tipcard`

## 安卓外壳（`android-app/`，不影响网页版）

- `index.html` 仍是唯一源码；`app/build.gradle` 里的 `syncWeb` 任务在构建前把它拷进 `app/src/main/assets/`，不复制逻辑、不改页面。
- `MainActivity.java` 用 `WebViewAssetLoader` 把页面挂在 `https://appassets.androidplatform.net/assets/`（`file://` 下部分机型会清 localStorage），开 `domStorageEnabled`，实现 `onShowFileChooser` 与 `AndroidHost.saveFile`（SAF 保存）。
- 清单里**不声明 INTERNET 权限**；返回键直接 `finish()`。
- 状态栏 / 导航栏是边到边（`WindowCompat.setDecorFitsSystemWindows(false)` + 透明色）：原生把 insets 换算成 dp 后调页面的 `window.setSafeArea(top,bottom,kb)`，写进 `--sat` / `--sab` / `--kb`（`kb` = `Type.ime()` 底部内边距；键盘弹起时 `.app` 整屏上缩，否则写作页光标会被键盘盖住）；皮肤切换时页面回调 `AndroidHost.setStatusBarIcons(dark)` 切图标明暗。网页版没有这些桥时，`--sat` / `--sab` 退化为 `env(safe-area-inset-*)`，`--kb` 由 `visualViewport` 估算（原生给过值就跳过估算，标志是 `window.__kbNative`）。
- 图标：`res/drawable/ic_launcher_foreground.xml` 是鲸鱼矢量，和网页 favicon、`assets/icon-preview.html` 同一份路径；底色在 `values/colors.xml` 的 `ic_launcher_bg`。
- 出包：推 main 后由 `.github/workflows/android.yml` 跑 `gradle assembleDebug`，产物在 Actions 的 artifact `writing-diary-apk`。本机没有 Gradle/JDK 也能出包。签名优先取 secret `DEBUG_KEYSTORE_B64`，其次 cache，最后 `keytool` 现造并把 base64 写进那次 run 的 Summary；细节见 `android-app/README.md`「覆盖安装 / 签名」。

## 怎么验证改动

1. 直接用浏览器打开 `index.html`，不需要起服务器。
2. 关键路径自测：写日记 → 刷新 → 内容还在；提交锁定 → 刷新 → 仍只读；切 Tab → 正文不丢。
3. 跨天逻辑：临时改系统日期，或在控制台改 `writing-diary:v1` 里的日期键后刷新。
4. 布局按 360 / 390（手机）与 1440（桌面）宽各看一次，确认无横向溢出。
5. 六套皮肤都要看：设置 → 外观 → 界面风格，逐套点一遍，六类页面（问题板 / 写作 / 回顾 / 详情 / 设置 / 小贴士）各扫一眼，看有没有残留异色块。
6. 导入往返：导出 JSON → 清 `writing-diary:v1` → 从 JSON 导入（先合并再覆盖各试一次）→ 天数、正文、锁定状态对得上；坏文件只弹 Toast。
7. 随机问题：写作页「问题」→ 浮层底部「随机问题」，点几次看 9 条是否每次都换且互不重复、正文是否一点没动；刷新后问题板应停在上次抽到的那 9 条。
8. 状态栏：网页上 `--sat` / `--sab` 取 `env(safe-area-inset-*)`，桌面浏览器恒为 0，看不出效果；要看真实效果只能装安卓包（状态栏应融进当前皮肤的背景，而不是一条白带），或在开发者工具里手动给 `:root` 写 `--sat: 44px` 临时看布局。
9. 本机 PowerShell 有时起不来（`0xC0000142`），不影响：直接用浏览器打开文件即可。

## 待办

见 `PRD.html` 第 2.7 节「后续候选」：PWA 添加到桌面、写作日偏移（凌晨 4 点算新一天）、锁定后自动备份、内置壁纸预设。

## 改完之后

凡是影响行为的改动（新功能、新约束、数值变化），**同步更新 `PRD.html` 的对应章节与版本记录**——否则这份文档就不再是事实来源，下一个人会被误导。
