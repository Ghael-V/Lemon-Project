"""Merge raw settings with tiers into the schema the server sends to the model.
Usage: python merge.py <raw.json> <tiers.json> <out.json>"""
import json, sys
raw = json.load(open(sys.argv[1], encoding="utf-8"))
tiers = json.load(open(sys.argv[2], encoding="utf-8"))
tier_of = {}
for t in ("allow", "risky", "preference"):
    for k in tiers[t]:
        tier_of[k] = t
out, counts = [], {}
for s in raw["settings"]:
    if not s["switchable"]:
        tier, reason = "global", "not per-game (global Setting)"
    else:
        tier = tier_of.get(s["key"], "never")
        reason = tiers["never_reasons"].get(s["key"]) if tier == "never" else None
    counts[tier] = counts.get(tier, 0) + 1
    if tier not in ("allow", "risky"):
        continue
    e = {"key": s["key"], "tier": tier, "type": s["cpp_type"], "default": s["default"]}
    for f in ("min", "max", "enum"):
        if f in s:
            e[f] = s[f]
    desc = tiers["descriptions"].get(s["key"]) or s.get("description") or s.get("title")
    if s.get("title"):
        e["ui_title"] = s["title"]
    e["description"] = desc
    out.append(e)
missing = [e["key"] for e in out if not e["description"]]
json.dump({"app": raw["app"], "schema_version": 1, "settings": out},
          open(sys.argv[3], "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(raw["app"], counts, "-> exported", len(out), "| no description:", missing)
unknown = [k for k in tier_of if k not in {s["key"] for s in raw["settings"]}]
print("  tier keys not in this app:", unknown)
