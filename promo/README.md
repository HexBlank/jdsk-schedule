# 几点上课 · 宣传片

`jdsk-promo.mp4`：58 秒，1920×1080，60 fps，中文，配乐与音效全部由代码合成（无第三方素材）。

## 分镜

| 时间 | 段落 | 画面 |
| --- | --- | --- |
| 0–4s | 片头 | 图标弹入、标题逐字弹出、星光闪烁 |
| 4–8s | 钩子 | 「早八在哪栋楼？」等气泡弹出 → 一扫而空 →「一眼看清」 |
| 8–14s | 01 一键导入 | 内嵌教务网页登录 → 解析进度 → 课程逐块落入课表 |
| 14–20s | 02 整周一屏 | 今天高亮、左右滑动翻周（单双周课随周变化）、课程详情抽屉 |
| 20–24s | 03 课程配色 | 长按课程 → 20 色面板点选，课程颜色实时过渡 |
| 24–30s | 04 调课 · 停课 · 补课 | 学期日历选日期与节次 → 课程沿弧线飞到新位置；周五停课半透明 |
| 30–34s | 05 自定义日程 | 点空格子 → 填写日程 → 虚线条纹日程块出现 |
| 34–40s | 06 分享与同步 | 8 位分享码翻牌 → 第二台手机输入加入 → 同步后的课表（不含本机日程） |
| 40–46s | 07 情侣课表 | 两人日视图左右排开、当前时间线、「都有空」时段、爱心 |
| 46–50s | 08 上课提醒 | 锁屏通知弹落 + 提前 5/10/15/20/30 分钟设置 |
| 50–58s | 片尾 | 功能胶囊汇总 → 图标与标语 → 原生安卓 · 约 1.5 MB · 离线优先 · 开源 |

画面里的课程、节次时间、配色（`ScheduleView.COURSE_PALETTES`）、情侣配色与界面结构都取自 App 本身。

## 重新生成

需要 Node 18+、Playwright（带 Chromium）和带 libx264 的 ffmpeg。

```bash
cd promo
# 1. 中文字体（不进仓库）
mkdir -p fonts && curl -L -o fonts/NotoSansSC.ttf \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
# 2. 导出音效时间表并合成配乐 → out/audio.wav
node render.mjs cues && node audio.mjs
# 3. 逐帧渲染无声视频 → out/video.mp4（4 进程约数分钟）
node render.mjs video --fps 60 --workers 4
# 4. 合成音视频（响度归一到 -14 LUFS）
ffmpeg -i out/video.mp4 -i out/audio.wav -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p \
  -af loudnorm=I=-14:TP=-1.5:LRA=11 -c:a aac -b:a 192k -movflags +faststart -shortest jdsk-promo.mp4
```

- 直接用浏览器打开 `index.html`（需通过本地静态服务，如 `npx serve promo`）可实时预览，点击画面同时播放 `out/audio.wav`，空格暂停；`index.html?t=21.4` 定格某一时刻。
- `node render.mjs stills 9 18.6 43.8` 导出指定时刻的静帧到 `out/stills/`，便于检查画面。
- 动画由 `renderAt(t)` 纯函数驱动（同一时刻永远得到同一帧），音效时间点统一登记在 `index.html` 的 `CUES` 里，改动画节奏时音效会跟着走。
