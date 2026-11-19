package com.zuakeo.inknote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 便签库。全部存本机，不联网。 */
public class NoteDb extends SQLiteOpenHelper {

    private static final String NAME = "notes.db";
    private static final int VER = 2;   // v2：加 raw_text（ASR 原始文本）

    private final Context ctx;

    public NoteDb(Context c) {
        super(c, NAME, null, VER);
        ctx = c.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table note(" +
                "id integer primary key autoincrement," +
                "text text," +
                "audio text," +
                "sec integer default 0," +
                "created integer," +
                "state integer default 1)");
        db.execSQL("create index idx_created on note(created desc)");
    }

    /** 只做增量迁移，绝不删表重建（历史笔记不能丢）。 */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("alter table note add column raw_text text");
            } catch (Throwable ignored) {
                // 已经有了就跳过
            }
        }
    }

    public long insert(String text, String audio, int sec, long created, int state) {
        return insert(text, null, audio, sec, created, state);
    }

    /** 带原始识别文本的插入：方便核对润色有没有改坏数字/人名。 */
    public long insert(String text, String raw, String audio, int sec, long created, int state) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        if (raw != null) v.put("raw_text", raw);
        v.put("audio", audio);
        v.put("sec", sec);
        v.put("created", created);
        v.put("state", state);
        return getWritableDatabase().insert("note", null, v);
    }

    public void updateText(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("text", text);
        v.put("state", text == null || text.trim().isEmpty() ? 2 : 1);
        getWritableDatabase().update("note", v, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Note> list() {
        List<Note> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("note", null, null, null,
                null, null, "created desc");
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.text = c.getString(c.getColumnIndexOrThrow("text"));
                n.audio = c.getString(c.getColumnIndexOrThrow("audio"));
                n.sec = c.getInt(c.getColumnIndexOrThrow("sec"));
                n.created = c.getLong(c.getColumnIndexOrThrow("created"));
                n.state = c.getInt(c.getColumnIndexOrThrow("state"));
                out.add(n);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Note get(long id) {
        Cursor c = getReadableDatabase().query("note", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (!c.moveToNext()) return null;
            Note n = new Note();
            n.id = c.getLong(c.getColumnIndexOrThrow("id"));
            n.text = c.getString(c.getColumnIndexOrThrow("text"));
            n.audio = c.getString(c.getColumnIndexOrThrow("audio"));
            n.sec = c.getInt(c.getColumnIndexOrThrow("sec"));
            n.created = c.getLong(c.getColumnIndexOrThrow("created"));
            n.state = c.getInt(c.getColumnIndexOrThrow("state"));
            return n;
        } finally {
            c.close();
        }
    }

    public void delete(long id) {
        Note n = get(id);
        if (n != null && n.hasAudio()) {
            try { new File(n.audio).delete(); } catch (Throwable ignored) { }
        }
        getWritableDatabase().delete("note", "id=?", new String[]{String.valueOf(id)});
    }

    /** 录音文件自己的存放目录。 */
    public File audioDir() {
        File d = new File(ctx.getFilesDir(), "audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
