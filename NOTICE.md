# 来源与署名

本仓库是 **Aerial** 的一条个人定制分支，上游项目：

- <https://github.com/pengchujin/aerial-tv>
- MIT License，Copyright (c) 2026 pengchujin（原文见 [LICENSE](LICENSE)）

## 哪些来自上游

`app/`、`lite/`、`site/`、`gradle/`、`README`（本分支加了几段说明）、`docs/` 下的架构与机型笔记，界面文案与品牌素材，绝大部分是上游的代码和文档。Inter 字体按 [SIL Open Font License](FONT-LICENSE-OFL.txt) 使用。

## 哪些是本分支新增的

针对小米 MiTV-MFTR0 的定制：包名 `com.ts.launcher`、桌面守护、守住开发者模式与 ADB、家长密码宽限、`tools/` 下的安装 / 还原 / 黑屏应急脚本，以及 `docs/devices/xiaomi-mitv-mftr0.md` 里的实测记录。逐项说明见 [README](README.md#本分支改了什么)。这些改动同样按 MIT 发布。

## 名称

「Aerial」这个名字、图标和官网属于上游作者。本仓库只是同一份代码的一条分支，不代表上游，也不要把这里的构建当成上游的官方版本发包。

## 第三方

- 航拍屏保影片的版权归 Apple，由 Apple 的服务器直接播放，仓库里不含影片文件。
- 每日精选图片来自必应。
- 天气用的国内城市表来自 [GeoNames](https://www.geonames.org)（CC BY 4.0），天气数据来自 [Open-Meteo](https://open-meteo.com)。
- Aerial 与 Apple、小米、TCL 等公司没有关系。
