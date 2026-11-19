import importlib.util, json, os, sys

SKILL = r"C:\Users\AI\.workbuddy\plugins\cache\workbuddy-builtin\tencent-docs-plugin\5.11.3-wb.43806934.gecace0f7.ha395516167d4\skills\tencent-docs"
FID = "PHQUTFRlbjcx"

sys.path.insert(0, SKILL)
spec = importlib.util.spec_from_file_location("tdoc", os.path.join(SKILL, "tencentdocs.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
mod.main(["tdoc_call", "doc-mcp", "get_outline", json.dumps({"file_id": FID})])
