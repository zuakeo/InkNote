# 便签 InkNote（海信 Touch Lite）

> 自写的极简语音便签。**离线按墨智键说话 → 录音存下 → 联网后自动转文字 → 一条便签里既有文字也有录音。**

状态：2026-11-18 15:22 **真机全链路验证通过**。

## 为什么不用系统便签

系统便签（`com.hmct.hsmemo`）可以写入，但：
- 没有「离线录音 → 联网自动转写」的链路；
- 界面在墨水屏上偏重，操作层级多。

现成的开源 App 也都不对口：inkOS 是桌面、Defter 要英文+alpha、InkVox 是输入法不存录音。
所以自写一个，只做「看便签 + 播录音」，其余交给系统。

**系统便签保留不动**，两边并存。新 App 的图标在桌面第二页，长按拖到第一页即可。

## 链路

```
按住墨智键说话
   │
   ├─ 有外网 → 推电脑 8765 → 转写 → 电脑粘贴（原有行为，不变）
   │
   └─ 推不上电脑 → /data/data/com.zuakeo.inknote/files/inbox/vk_yyyyMMdd_HHmmss.m4a
                   （语音键用 root 写 + chown，便签普通读写即可见）
                 │
                 └─ 联网后 SyncService 转写
                       ├─ 先找电脑（mode=text，热词+润色+幻觉过滤）
                       └─ 电脑不在 → 设备直连 Groq
                             ↓
                       便签一条：文字 + 原始录音，永久保留
```

## 组成

| 部分 | 路径 |
|---|---|
| 源码 / 构建 | 本仓库根目录（`src/`、`build.sh`、`dist/`） |
| 安装脚本 | `install.sh`（装 App + 权限 + root + 电脑地址/Key/热词） |
| 自测脚本 | `test.sh` |
| 录音入口 | VoiceKey 仓库（VoiceKey 0.14 起支持离线转存） |
| 电脑端 | VoiceKey 仓库的 `pc/inkvoice.py`（`mode=text` 同步返回文字） |
| 守护 | Magisk 模块 `/data/adb/modules/voicekey_mokey/service.sh`（同时守 VoiceKey 和便签） |

## 数据

| 内容 | 位置 |
|---|---|
| 便签库 | `/data/data/com.zuakeo.inknote/databases/notes.db` |
| 录音归档 | `/data/data/com.zuakeo.inknote/files/audio/` |
| 收件箱 / 待转写队列 | `/data/data/com.zuakeo.inknote/files/inbox/` |
| 兜底收件箱（仅当便签没装） | `/sdcard/InkNote/pending/` |

## 界面

- 列表：时间 + 正文（最多 4 行）+ 🎤 录音时长；还没转写的显示「⏳ 待转写」
- 点开：正文（可选中复制）+ ▶ 播放录音
- 长按：删除（录音一起删）
- 右上「设置」：电脑地址、Groq Key、热词

## 构建 / 安装

```bash
cd <project-root> && bash build.sh   # → dist/InkNote-0.1.apk
bash install.sh                                # 装 + 授权 + 给 root + 灌配置
```

## 踩过的坑（重要）

1. **`ACCESS_NETWORK_STATE` 不申请，`getNetworkCapabilities` 直接抛异常**，整个同步逻辑会静默不执行。
2. **Android 11 的 FUSE 会把 root 建在 /sdcard 的文件对普通应用隐藏**，`listFiles()` 返回空，
   且 `chown` 在 /sdcard 上是空操作。→ 解法：干脆不过 /sdcard，语音键用 root 直写便签私有目录并 chown。
3. **同名录音重复落库**：源文件删不掉就会每 15 秒转一次。→ 先搬进私有队列再转写，并按时间戳去重。
4. **海信「锁定阅读」会拦下一切 Activity 启动**（`am start` 返回 error 102，连系统设置都打不开）。
   两个开关：`settings put system locked_read_enable 0`、`reading_mode_enabled 0`。
5. **`am start-service` 在这台机器上解析不了组件**（报 Not found）。→ 用导出的广播 `com.zuakeo.inknote.WAKE` 唤醒服务。
6. **后台广播要拉起前台服务，必须有 `SYSTEM_ALERT_WINDOW`**（appops allow），否则抛
   `ForegroundServiceStartNotAllowedException`。
7. **重装会重置存储权限**，`install.sh` 里每次都要重新 `pm grant`。

## 已知边界

- 设备连不上电脑、又没有外网时，录音挂在「待转写」，不会丢。
- 电脑端没开时走设备直连 Groq，准确率略低（没有润色和专有名词纠错）。
- 便签只能看/听/删，暂不支持在设备上编辑文字。
- 桌面图标需要手动拖到第一页。

---

## 最近改动（2026-11-19）

### 1. 静音 / 过短不再落成笔记

误按一下侧键，以前会把空白音频送去转写，Whisper 在静音上会「幻觉」出固定句子
（实测出现过「ご視聴ありがとうございました」「はい」「作词 曲：李宗盛」）。

现在落笔记前先过两道闸门（`Loudness.java`）：

| 条件 | 处理 |
|---|---|
| 时长 < 3 秒 | 直接丢弃 |
| 100ms 窗口 RMS ≥ 500 的窗口数 < 5 | 判定无声，丢弃 |
| 解码失败 | **不拦**（宁可留一条空录音，也不误删用户的话） |

阈值与电脑端 `is_silent` 同口径。实测：真实语音衰减到 1/8 音量仍能正常转写，
1/16 才会被丢掉；纯静音 RMS 只有 55~100，余量足够。

### 2. 批量勾选与删除

长按任一条进入选择模式，顶部出现 `已选 N 条 / 全选 / 删除 / 取消`，
行首显示 ☐/☑，删除有二次确认，记事与录音文件一起删。

### 3. 收件箱支持媒体库

语音键把录音插进系统媒体库 `Music/VoiceKey`（自己插自己不需要权限），
本应用在同步时一并导入；同时保留对 `/sdcard/InkNote/pending` 的兜底扫描。

> 为什么不是直接写对方的私有目录：Android 11 的包可见性让 `getPackageInfo` 拿不到对方 uid，
> 而 `/sdcard` 的权限由 FUSE 合成（`chmod` 无效），两条路都不通。
