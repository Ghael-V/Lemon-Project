"""Build the AI per-game settings schema from an emulator source tree.

Usage: python gen_schema.py <repo_root> <app_name> <android_pkg_dir> <out.json>
Reads src/common/settings.h, settings_enums.h and the Android settings UI.
"""
import json, re, sys, html
from pathlib import Path

root, app, pkg_dir, out = Path(sys.argv[1]), sys.argv[2], sys.argv[3], Path(sys.argv[4])
common = root / "src/common"
android = root / "src/android/app/src/main"

# ---- enums -------------------------------------------------------------
enums_src = (common / "settings_enums.h").read_text(encoding="utf-8")
enums = {}
for m in re.finditer(r"ENUM\(\s*(\w+)\s*,([^;]*?)\);", enums_src, re.S):
    vals = [v.strip() for v in m.group(2).replace("\n", " ").split(",") if v.strip()]
    enums[m.group(1)] = vals
# explicit enum classes (enum class X : u32 { A = 0, B, ... })
for m in re.finditer(r"enum class (\w+)\s*(?::\s*\w+)?\s*\{([^}]*)\}", enums_src, re.S):
    if m.group(1) in enums:
        continue
    vals, nxt = [], 0
    for part in m.group(2).split(","):
        part = re.sub(r"//.*", "", part).strip()
        if not part:
            continue
        if "=" in part:
            n, v = [x.strip() for x in part.split("=", 1)]
            try:
                nxt = int(v, 0)
            except ValueError:
                pass
        else:
            n = part
        vals.append((n, nxt))
        nxt += 1
    enums[m.group(1)] = vals

def enum_values(t):
    v = enums.get(t)
    if v is None:
        return None
    if v and isinstance(v[0], tuple):
        return [{"name": n, "value": i} for n, i in v]
    return [{"name": n, "value": i} for i, n in enumerate(v)]

# ---- settings.h --------------------------------------------------------
DEFINES = {"__ANDROID__", "ANDROID", "__linux__", "HAS_NCE", "ARCHITECTURE_arm64", "__aarch64__"}

def cond_true(expr):
    expr = re.sub(r"defined\s*\(\s*(\w+)\s*\)", lambda m: "True" if m.group(1) in DEFINES else "False", expr)
    expr = expr.replace("&&", " and ").replace("||", " or ").replace("!", " not ")
    expr = re.sub(r"\b([A-Za-z_]\w*)\b", lambda m: m.group(1) if m.group(1) in ("True", "False", "and", "or", "not") else ("True" if m.group(1) in DEFINES else "False"), expr)
    return bool(eval(expr, {"__builtins__": {}}))

def preprocess(text):
    """Resolve #if/#ifdef/#ifndef/#elif/#else/#endif for an arm64 build."""
    out, stack = [], []  # stack of [active, taken]
    for line in text.splitlines():
        s = line.strip()
        m = re.match(r"#\s*(ifdef|ifndef|if|elif|else|endif)\b(.*)", s)
        parent = all(a for a, _ in stack)
        if not m:
            if parent:
                out.append(line)
            continue
        d, rest = m.group(1), m.group(2).strip()
        if d in ("ifdef", "ifndef", "if"):
            v = (rest in DEFINES) if d == "ifdef" else (rest not in DEFINES) if d == "ifndef" else cond_true(rest)
            stack.append([v, v])
        elif d == "elif":
            v = not stack[-1][1] and cond_true(rest)
            stack[-1] = [v, stack[-1][1] or v]
        elif d == "else":
            stack[-1] = [not stack[-1][1], True]
        else:
            stack.pop()
    return "\n".join(out)

src = (common / "settings.h").read_text(encoding="utf-8")
src = preprocess(re.sub(r"//[^\n]*", "", src))
settings = {}
for m in re.finditer(r"\b(Switchable)?Setting<([^;{]*?)>\s+(\w+)\s*\{(.*?)\};", src, re.S):
    switchable = bool(m.group(1))
    targs = [a.strip() for a in m.group(2).split(",")]
    ctype, ranged = targs[0], len(targs) > 1 and targs[1] == "true"
    body = " ".join(m.group(4).split())
    km = re.search(r'"([a-z0-9_\\]+)"', body)
    if not km:
        continue
    key = km.group(1)
    before = [a.strip() for a in body[: km.start()].split(",") if a.strip()]
    if before and before[0] == "linkage":
        before = before[1:]
    after = body[km.end():]
    cat = re.search(r"Category::(\w+)", after)
    ent = {"key": key, "cpp_type": ctype, "switchable": switchable,
           "category": cat.group(1) if cat else None,
           "default": before[0] if before else None}
    if ranged and len(before) >= 3:
        ent["min"], ent["max"] = before[1], before[2]
    ev = enum_values(ctype.split("::")[-1])
    if ev:
        ent["enum"] = ev
    settings[key] = ent

# ---- Android UI: key -> title/description ------------------------------
strings = {}
sx = (android / "res/values/strings.xml").read_text(encoding="utf-8")
for m in re.finditer(r'<string name="(\w+)"[^>]*>(.*?)</string>', sx, re.S):
    strings[m.group(1)] = html.unescape(re.sub(r"\\(['\"])", r"\1", m.group(2))).strip()

model = android / "java" / pkg_dir / "features/settings/model"
const_to_key = {}
for f in model.glob("*Setting.kt"):
    cls = f.stem
    for m in re.finditer(r'\b([A-Z0-9_]+)\("([a-z0-9_\\]+)"', f.read_text(encoding="utf-8")):
        const_to_key[f"{cls}.{m.group(1)}"] = m.group(2)
ui = {}
item = (model / "view/SettingsItem.kt").read_text(encoding="utf-8")
for m in re.finditer(r"\b(\w+Setting\.[A-Z0-9_]+)\s*,(.{0,400}?)\)", item, re.S):
    key = const_to_key.get(m.group(1))
    if not key or key in ui:
        continue
    t = re.search(r"titleId\s*=\s*R\.string\.(\w+)", m.group(2))
    d = re.search(r"descriptionId\s*=\s*R\.string\.(\w+)", m.group(2))
    ui[key] = {"title": strings.get(t.group(1)) if t else None,
               "description": strings.get(d.group(1)) if d else None}

for key, ent in settings.items():
    ent["ui_visible"] = key in ui
    if key in ui:
        ent.update({k: v for k, v in ui[key].items() if v})

out.write_text(json.dumps({"app": app, "settings": list(settings.values())},
                          indent=1, ensure_ascii=False), encoding="utf-8")
print(app, "settings:", len(settings), "switchable:", sum(s["switchable"] for s in settings.values()),
      "ui:", sum(s["ui_visible"] for s in settings.values()))
