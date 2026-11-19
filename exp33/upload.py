import base64
import importlib.util
import io
import json
import os
import sys

SKILL = r"C:\Users\AI\.workbuddy\plugins\cache\workbuddy-builtin\tencent-docs-plugin\5.11.3-wb.43806934.gecace0f7.ha395516167d4\skills\tencent-docs"
MD = r"D:\myProject\project_interview_ai\java_interview_33_auth_session.md"
TITLE = "Java面试深挖33·认证授权与会话"


def load_main():
    sys.path.insert(0, SKILL)
    spec = importlib.util.spec_from_file_location("tdoc", os.path.join(SKILL, "tencentdocs.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.main


def main():
    raw = io.open(MD, encoding="utf-8").read()
    b64 = base64.b64encode(raw.encode("utf-8")).decode("ascii")
    args = json.dumps({"base64_markdown": b64, "title": TITLE}, ensure_ascii=False)
    print("md chars=%d base64 len=%d" % (len(raw), len(b64)))
    m = load_main()
    m(["tdoc_call", "doc-mcp", "create_with_markdown", args])


if __name__ == "__main__":
    main()
