# 写作日记 · 安卓外壳

这不是第二个版本，只是一层壳：`index.html` 还是唯一的源码，构建时被复制进 `app/src/main/assets/`，跑在系统 WebView 里。改界面只改根目录的 `index.html`，这里一个字都不用动。

```
android-app/
├─ settings.gradle / build.gradle / gradle.properties   构建配置（Gradle 8.7 + AGP 8.5.2）
├─ app/build.gradle                                     应用配置 + syncWeb 复制任务
└─ app/src/main/
   ├─ AndroidManifest.xml                               无 INTERNET 权限
   ├─ java/com/xiaojingyu/writingdiary/MainActivity.java  WebView + 原生桥
   └─ res/                                             主题、纸色、自适应图标
```

## 一、云端出包（推荐，本机什么都不用装）

1. 在 GitHub 新建一个仓库，把**整个项目目录**推上去（`index.html`、`android-app/`、`.github/` 都要在仓库根）。
2. 进仓库的 **Actions** 页签 → 左边选「构建安卓 APK」→ 右边 **Run workflow**。
3. 等 5–10 分钟（第一次要下 Gradle 和 Android SDK 依赖）。
4. 进这次运行的页面，最下面 **Artifacts** 下载 `writing-diary-apk.zip`，解压得到 `app-debug.apk`。

以后每次改完 `index.html` 推到 main，都会自动重新出包。

## 二、本机出包（装了 Android Studio 的话）

1. Android Studio → Open → 选 `android-app/` 这个目录（不是项目根目录）。
2. 等 Gradle Sync 完成（会自动下载 AGP 和依赖）。
3. 菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
4. 产物在 `android-app/app/build/outputs/apk/debug/app-debug.apk`。

命令行等价写法（在 `android-app/` 里）：

```bash
gradle assembleDebug
```

> 本机没有 Gradle 的话，用 Android Studio 自带的 Gradle 就行，不要自己装 wrapper——仓库里故意没放 `gradle-wrapper.jar`（二进制文件，用 CI 里的 `gradle` 命令或 Studio 自带的即可）。

## 装到手机

- 把 `app-debug.apk` 传到手机（微信文件传输 / 数据线 / 网盘都行），点开安装。
- 第一次会提示「不允许安装未知来源应用」，按提示给文件管理器或浏览器开权限。
- 这是 **debug 签名**：能装能用，但**不能上架 Google Play**，也不能覆盖安装正式签名的包。自己用完全够。

## 为什么这么写（几个不能踩的坑）

| 坑 | 处理 |
| --- | --- |
| 用 `file:///android_asset/` 加载，`localStorage` 的源是 `file://`，部分机型会**莫名清零** | 用 `WebViewAssetLoader` 把页面挂到 `https://appassets.androidplatform.net/assets/`，存储源稳定为 https |
| 没开 DOM Storage，写完一刷新就空 | `settings.setDomStorageEnabled(true)` |
| 选壁纸 / 选 JSON 备份的 `<input type=file>` 在 WebView 里点了没反应 | 实现 `onShowFileChooser` |
| 「导出 txt / JSON」用的是 `Blob` + `<a download>`，WebView 里不会下载 | 页面检测到 `window.AndroidHost` 时改走原生 `saveFile`，弹系统「保存到」对话框（SAF，不需要存储权限） |
| 返回键落到白屏 | `onBackPressed()` 直接 `finish()` |

## 数据注意

- 日记存在 WebView 的 localStorage 里，和网页版是**两份互不相通**的数据。
- **卸载 App = 日记全丢**。要用设置里的「导出为 JSON」定期存一份到手机存储。
- 重装后用「从 JSON 导入」恢复（合并或覆盖都会问一次）。
- 应用不申请 INTERNET 权限，也不发任何网络请求。

## 换图标 / 改包名

- 图标：`app/src/main/res/drawable/ic_launcher_foreground.xml`（矢量，改颜色和方块即可），底色在 `values/colors.xml` 的 `ic_launcher_bg`。
- 包名：`app/build.gradle` 里的 `namespace` 与 `applicationId`，以及 Java 文件所在目录，三处要一起改。
