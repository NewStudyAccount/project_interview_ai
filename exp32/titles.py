import contextlib
import importlib.util
import io
import json
import os
import sys

SKILL = r"C:\Users\AI\.workbuddy\plugins\cache\workbuddy-builtin\tencent-docs-plugin\5.11.3-wb.43806934.gecace0f7.ha395516167d4\skills\tencent-docs"
FID = sys.argv[1]

sys.path.insert(0, SKILL)
spec = importlib.util.spec_from_file_location("tdoc", os.path.join(SKILL, "tencentdocs.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    mod.main(["tdoc_call", "doc-mcp", "get_outline", json.dumps({"file_id": FID})])
out = buf.getvalue()
start = out.find('{"jsonrpc"')
depth = 0
end = None
for i, ch in enumerate(out[start:], start):
    if ch == '{':
        depth += 1
    elif ch == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
env = json.loads(out[start:end])

def walk(nodes):
    for n in nodes:
        lv = n.get('level', '')
        if isinstance(lv, str) and lv.startswith('HEADING_LEVEL_'):
            print(lv[-1], repr(n.get('title', ''))[:80])
        ch = n.get('children')
        if ch:
            walk(ch)

walk(env.get('result', {}).get('structuredContent', {}).get('outlines', []))
