#!/usr/bin/env bash
# 真机自测：装 App → 塞一条录音到待转写目录 → 等它转写 → 读数据库验收
set -e
export MSYS_NO_PATHCONV=1
ROOT="<project-root>"
PKG="com.zuakeo.inknote"

echo "=== 0. 设备 ==="
adb devices

echo "=== 1. 安装 ==="
adb install -r "$ROOT/dist/InkNote-0.1.apk"
adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true

echo "=== 2. 打开一次 ==="
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 3

echo "=== 3. 建目录 + 塞测试录音 ==="
adb shell "su -c 'mkdir -p /sdcard/InkNote/pending; chmod 777 /sdcard/InkNote /sdcard/InkNote/pending'"
MSYS_NO_PATHCONV=1 adb push "$ROOT/../voicekey/test.m4a" /sdcard/InkNote/pending/vk_20261118_131500.m4a

echo "=== 4. 触发同步 ==="
adb shell "su -c 'am force-stop $PKG'"
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1 || true
sleep 12

echo "=== 5. 看结果 ==="
adb shell "su -c 'ls -la /sdcard/InkNote/pending/; echo ---; ls -la /data/data/$PKG/files/audio 2>/dev/null'"
adb shell "su -c 'cat /data/data/$PKG/files/../databases/notes.db > /sdcard/notes_probe.db; chmod 666 /sdcard/notes_probe.db'"
cd "$ROOT/probe" && MSYS_NO_PATHCONV=1 adb pull /sdcard/notes_probe.db . >/dev/null
"$ROOT/../voicekey/pc/.venv312/Scripts/python.exe" -c "
import sqlite3
c=sqlite3.connect(r'<project-root>/probe/notes_probe.db')
print('--- 便签 ---')
for r in c.execute('select id,created,sec,state,substr(text,1,60),audio from note order by created desc'):
    print(r)
"

echo "=== 6. 截图 ==="
MSYS_NO_PATHCONV=1 adb shell screencap -p /sdcard/ink1.png
cd "$ROOT/probe" && MSYS_NO_PATHCONV=1 adb pull /sdcard/ink1.png . >/dev/null
echo "截图：$ROOT/probe/ink1.png"
