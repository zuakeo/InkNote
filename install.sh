#!/usr/bin/env bash
# 安装便签 App，一次把该给的权限、root、配置、目录都弄好。
# 用法：bash install.sh
set -e
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK="$ROOT/dist/InkNote-0.1.apk"
PKG="com.zuakeo.inknote"
VK="com.zuakeo.voicekey"

echo "[1/7] 安装 APK"
adb install -r "$APK"

echo "[2/7] 运行时权限"
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
# SYSTEM_ALERT_WINDOW：有这个才有资格从后台广播里拉起前台服务
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
adb shell appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
adb shell am set-standby-bucket $PKG active 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true

echo "[3/7] 打开一次（海信要求到过前台才允许后台服务）"
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 3

echo "[4/7] 给 root（拉收件箱要用：root 写进 /sdcard 的文件应用看不见）"
UID_=$(adb shell "su -c 'stat -c %u /data/data/$PKG'" | tr -d '\r')
adb shell "su -c 'magisk --sqlite \"insert or replace into policies (uid,policy,until,logging,notification) values ($UID_,2,0,0,0)\"'" >/dev/null 2>&1 || true

echo "[5/7] 读取已配好的电脑地址与 Key"
PC=$(adb shell "su -c 'cat /data/data/$VK/shared_prefs/vk.xml 2>/dev/null'" 2>/dev/null \
      | grep -o 'http[^<]*' | head -1 | tr -d '\r')
GROQ=""
if [ -f "$ROOT/../voicekey/pc/groq_key.txt" ]; then
  GROQ=$(tr -d ' \r\n' < "$ROOT/../voicekey/pc/groq_key.txt")
fi
echo "    电脑 = ${PC:-（空，App 里会自动扫局域网）}"

echo "[6/7] 写配置"
# 已经有配置就不覆盖 —— 手机端的热词是唯一真源，重装不能把它冲掉
if adb shell "su -c 'test -f /data/data/$PKG/shared_prefs/ink.xml && echo yes'" | grep -q yes; then
  echo "    已存在配置，跳过（保留手机端热词）"
  SKIP_PREFS=1
fi
mkdir -p "$ROOT/build"
if [ "$SKIP_PREFS" != "1" ]; then
  python "$ROOT/tools/mk_prefs.py" "$ROOT/build/ink.xml" "$PC" "$GROQ" "$ROOT/../voicekey/pc/hotwords.txt"
  adb push "$ROOT/build/ink.xml" /data/local/tmp/ink.xml >/dev/null
  adb shell "su -c 'am force-stop $PKG; mkdir -p /data/data/$PKG/shared_prefs; cp /data/local/tmp/ink.xml /data/data/$PKG/shared_prefs/ink.xml; chown $UID_:$UID_ /data/data/$PKG/shared_prefs/ink.xml; chmod 660 /data/data/$PKG/shared_prefs/ink.xml; rm -f /data/local/tmp/ink.xml'"
fi

echo "[7/7] 建收件箱并启动"
# 便签自己的私有收件箱（语音键用 root 直接写这里）
adb shell "su -c 'mkdir -p /data/data/$PKG/files/inbox; chown $UID_:$UID_ /data/data/$PKG/files/inbox; chmod 700 /data/data/$PKG/files/inbox'"
# 老的共享目录留着做兜底
adb shell "su -c 'mkdir -p /sdcard/InkNote/pending; chmod 777 /sdcard/InkNote /sdcard/InkNote/pending'"
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
adb shell "am broadcast -a $PKG.WAKE -n $PKG/.BootReceiver" >/dev/null 2>&1 || true

echo "完成。包名 $PKG，uid=$UID_"
