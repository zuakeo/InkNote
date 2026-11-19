#!/bin/bash
# 待机活动量采样：10 分钟内两个 App 用了多少 CPU、电脑端收到多少请求。
# 设备一直在充电，测电量没有意义，这里测的是"活不活跃"。
cd . || exit 1

get_cpu() {
  local pkg=$1
  local pid
  pid=$(adb shell "pidof $pkg" 2>/dev/null | tr -d '\r')
  if [ -z "$pid" ]; then echo "0"; return; fi
  adb shell "cat /proc/$pid/stat" 2>/dev/null | awk '{print $14+$15}' | tr -d '\r'
}

LOG=../VoiceKey/pc/out/receiver.log
A0=$(get_cpu com.zuakeo.inknote)
V0=$(get_cpu com.zuakeo.voicekey)
L0=$(wc -l < "$LOG" 2>/dev/null || echo 0)
LINES0=$(wc -l < "$LOG" 2>/dev/null || echo 0)
echo "start: inknote_cpu=$A0 voicekey_cpu=$V0 log_lines=$L0"

sleep 600

A1=$(get_cpu com.zuakeo.inknote)
V1=$(get_cpu com.zuakeo.voicekey)
LINES1=$(wc -l < "$LOG" 2>/dev/null || echo 0)
echo "end:   inknote_cpu=$A1 voicekey_cpu=$V1 log_lines=$LINES1"
echo "delta: inknote=$((A1-A0)) jiffies  voicekey=$((V1-V0)) jiffies  (100 jiffies = 1 CPU 秒)"
echo "pc 日志新增行: $((LINES1-LINES0))"
