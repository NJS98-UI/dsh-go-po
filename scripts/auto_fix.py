#!/usr/bin/env python3
"""让 LLM 根据 CI 失败日志生成补丁（只改 app/ 下的文件）。

用法: SILICONFLOW_API_KEY=... python3 scripts/auto_fix.py /tmp/logs.zip
成功退出码 0 且工作区有改动；无法修复时退出码 2（不视为 workflow 失败）。
"""
import json, os, sys, urllib.request, zipfile, pathlib

KEY = os.environ.get("SILICONFLOW_API_KEY")
if not KEY:
    print("no key, skip"); sys.exit(2)
API = "https://api.siliconflow.cn/v1/chat/completions"
MODEL = os.environ.get("AUTOFIX_MODEL", "deepseek-ai/DeepSeek-V3")


def failed_log(zpath):
    z = zipfile.ZipFile(zpath)
    best = ""
    for n in z.namelist():
        if not n.endswith(".txt"):
            continue
        t = z.read(n).decode(errors="replace")
        if ("Build APK" in n) or ("error" in t.lower() and "flutter" in t.lower()):
            if len(t) > len(best):
                best = t
    lines = best.splitlines()
    # 只留错误附近：从最后出现 error/failed 的位置往回取
    idx = 0
    for i, l in enumerate(lines):
        if "error" in l.lower() or "failed" in l.lower():
            idx = max(0, i - 40)
    tail = lines[idx:][-260:]
    return "\n".join(tail)


def collect_context():
    ctx = []
    for p in ("app/pubspec.yaml",):
        f = pathlib.Path(p)
        if f.exists():
            ctx.append(f"### {p}\n```\n{f.read_text(encoding='utf-8', errors='replace')[:4000]}\n```")
    return "\n\n".join(ctx)


def main():
    log = failed_log(sys.argv[1])
    if not log.strip():
        print("no log"); sys.exit(2)
    prompt = f"""你在修复一个 Flutter Android 项目的 CI 构建失败。
下面是失败日志（尾部），以及项目文件片段。请判断根因，给出最小修复。

规则：
- 只能修改 app/ 目录下的文件（Dart、Kotlin、yaml、gradle 都可以）。
- 输出严格 JSON，不要 markdown 代码块：
{{"reason":"一句话根因","confidence":"high|medium|low","changes":[{{"path":"app/...","content":"完整的新文件内容"}}]}}
- content 必须是整个文件的完整内容（不是 diff）。
- 最多改 3 个文件；宁可 confidence 低也不要瞎改。

失败日志：
{log[-12000:]}

{collect_context()}
"""
    body = json.dumps({"model": MODEL, "temperature": 0.1, "max_tokens": 8000,
                       "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(API, data=body, headers={
        "Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    try:
        resp = json.load(urllib.request.urlopen(req, timeout=180))
    except Exception as e:
        print("api error:", e); sys.exit(2)
    text = resp["choices"][0]["message"]["content"].strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0]
    try:
        data = json.loads(text)
    except Exception:
        print("bad json", text[:400]); sys.exit(2)
    print("reason:", data.get("reason"), "| confidence:", data.get("confidence"))
    changes = data.get("changes") or []
    if not changes or data.get("confidence") == "low":
        print("no confident fix"); sys.exit(2)
    n = 0
    for ch in changes[:3]:
        p = pathlib.Path(ch["path"])
        rp = p.as_posix()
        if not rp.startswith("app/") or ".." in rp:
            print("reject path", rp); continue
        content = ch.get("content") or ""
        if len(content) > 300000 or not content.strip():
            continue
        old = p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""
        if old == content:
            continue
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        print("patched", rp)
        n += 1
    sys.exit(0 if n else 2)


if __name__ == "__main__":
    main()
