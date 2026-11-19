package com.zuakeo.inknote;

/** 一条便签。 */
public class Note {
    public long id;
    public String text;      // 转写出来的文字，可能为空（只是录音）
    public String audio;     // 录音绝对路径，可能为空
    public int sec;          // 录音时长（秒）
    public long created;     // 录制时间（毫秒）
    public int state;        // 1=已转写  2=只有录音（转写为空）  3=手写文字

    public boolean hasAudio() {
        return audio != null && audio.length() > 0;
    }
}
