# -*- coding: utf-8 -*-
"""生成便签 App 的 SharedPreferences 文件。

用法：python mk_prefs.py 输出路径 [电脑地址] [GroqKey]
"""
import io
import os
import sys
from xml.sax.saxutils import escape

HOT = "劳务公司、签字、护照复印件、签证材料、施工日志、劳务人员、工资表、交到办公室、递交、项目部"


def main():
    out = sys.argv[1]
    pc = sys.argv[2] if len(sys.argv) > 2 else ""
    groq = sys.argv[3] if len(sys.argv) > 3 else ""
    # 第 4 个参数可选：电脑端热词文件，用它把手机端热词一次性铺好
    hot = HOT
    if len(sys.argv) > 4 and os.path.exists(sys.argv[4]):
        words = []
        for line in io.open(sys.argv[4], encoding="utf-8").read().splitlines():
            if line.strip().startswith("#"):
                continue
            for w in line.replace("、", "\n").splitlines():
                w = w.strip()
                if w:
                    words.append(w)
        if words:
            hot = "、".join(words)
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           "<map>\n"
           "    <string name=\"pc\">%s</string>\n"
           "    <string name=\"groq\">%s</string>\n"
           "    <string name=\"hotwords\">%s</string>\n"
           "</map>\n") % (escape(pc), escape(groq), escape(hot))
    open(out, "w", encoding="utf-8", newline="\n").write(xml)
    print("写入 %s（pc=%s，key=%s，热词 %d 个）"
          % (out, "有" if pc else "空", "有" if groq else "空", len(hot.split("、"))))


if __name__ == "__main__":
    main()
