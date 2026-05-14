# HAcg relay build

这是基于 [yueeng/hacg](https://github.com/yueeng/hacg) 的个人接力维护版本。原项目是 Android 端 ACG 阅读器，本仓库主要补了一些偏本地使用和自助打包的功能。

> 非官方版本，和琉璃神社/原作者没有直接关系。代码与功能改动以个人使用为主。

## 主要改动

- 本地收藏页：作品可加入/移出收藏，收藏列表独立查看。
- 收藏导入/导出：支持导出 `hacg-favorites.json`，也可从 JSON 导入。
- WebDAV 同步：可把收藏 JSON 上传到 WebDAV，也可从 WebDAV 导入。
- 收藏资源备份：详情页解析到的磁力/百度链接会写入收藏 JSON 的 `links` 字段，作为站点不可用时的线索备份。
- 标签体验：列表卡片限制标签预览，`+N` 可展开；详情页标题下方完整展示标签。
- 搜索优化：结果页可继续搜索，空搜索会拦截，首页提交搜索后自动收起搜索框。
- 搜索封面补救：搜索结果缺封面时，会后台预取详情页 HTML 并解析第一张正文图 URL。
- GitHub Actions 打包：支持手动构建 debug/release APK，并自动创建 GitHub Release。
- 检查更新：关于页检查本 fork 的 GitHub Release。

## 收藏 JSON

收藏文件会保存作品信息，也会尽量保存资源链接备份：

```json
{
  "version": 2,
  "items": [
    {
      "title": "...",
      "link": "...",
      "links": [
        { "type": "magnet", "value": "magnet:?xt=urn:btih:..." },
        { "type": "baidu", "url": "https://yun.baidu.com/s/...", "code": "..." }
      ]
    }
  ]
}
```

`links` 只作为备份字段存在，收藏页当前仍按作品列表展示。

## GitHub Actions 打包

本仓库有两个手动工作流：

- `Generate Android Keystore`：生成 release 签名文件和 GitHub Actions secrets 参考值。
- `Android APK`：构建 APK，并创建 Release。

Release 覆盖安装需要使用同一份签名。首次生成签名后，请把以下 secrets 配到仓库：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

之后每次运行 `Android APK` 选择 `release`，生成的 APK 才能和上一次 release 版覆盖安装。

## 版本号

CI 构建时版本名格式为：

```text
1.5.<GITHUB_RUN_NUMBER>
```

Release tag 会包含版本号、构建类型和运行尝试次数。

## 原项目

- 原仓库：[yueeng/hacg](https://github.com/yueeng/hacg)
- 原项目发布页：[https://github.com/yueeng/hacg/releases](https://github.com/yueeng/hacg/releases)

原 README 截图：

main | content | comment
------------ | ------------- | -------------
![screenshot01](https://cloud.githubusercontent.com/assets/4374375/8587179/e53cab82-262a-11e5-8edf-da067e7e4494.png)|![screenshot02](https://cloud.githubusercontent.com/assets/4374375/8587180/e540b1c8-262a-11e5-91c9-ded4d0a94d93.png)|![screenshot03](https://cloud.githubusercontent.com/assets/4374375/8587178/e4f8ade2-262a-11e5-9734-e227a09f034d.png)
