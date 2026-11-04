# 定时任务执行记录 · Java 面试复习（每 2 小时）

## 2026-11-04 15:35（第 1 次执行）
- 主题：第 01 期 HashMap → ConcurrentHashMap（L0 契约 / L1 寻址 / L2 树化 / L3 扩容 / L4 CHM 并发 / L5 速查 / L6 模拟题）
- 产出：`java_interview_01_hashmap.html`（6 层正文 + 7 张手绘 SVG 图 + 代码高亮）、`java_interview_01_hashmap.md`（同构纯文本版）
- 云端：已通过 tencent-docs skill 的 `create_smartcanvas_by_mdx`（content_format=markdown）上传，标题「Java面试深挖01·HashMap并发原理」，file_id `PbLJCJsRxgoV`
- 经验：
  1. 长文本不能走 `tdoc_call` 命令行 argv（Windows 32k 限制），改为写 driver 脚本 `importlib` 加载 `tencentdocs.py` 后调用 `m.main([...])`，把 JSON 在进程内传递 —— 已跑通，后续复用此法。
  2. 智能文档标题上限 36 字符，需精简。
  3. HTML 代码块内的 `<` `>` `&` 用实体转义，再用 JS 读 textContent 重新高亮，可避免二次转义。
- 下期建议主题：JVM 内存模型与 GC（已在文档末尾做预告，需保持一致）。

## 2026-11-04 17:33（第 2 次执行）
- 主题：第 02 期 JVM 内存模型与垃圾回收（L0 运行时数据区 / L1 对象创建与内存布局 / L2 可达性与三色标记 / L3 分代与回收算法 / L4 收集器 CMS→G1→ZGC / L5 12 条问答 / L6 3 道模拟题）
- 产出：`java_interview_02_jvm_gc.html`（8 张手绘 SVG 原理图 + 7 段代码 + 7 张表格）、`java_interview_02_jvm_gc.md`（ASCII 图替代 SVG 的纯文本版，20101 字符）
- 云端：`create_smartcanvas_by_mdx`（content_format=markdown）上传成功，标题「Java面试深挖02·JVM内存模型与GC」，file_id `PXjInSrjHNbu`，url https://docs.qq.com/aio/DUFhqSW5TcmpITmJ1
- 经验：
  1. 进程内 driver 脚本法再次验证可用（本次 20k 字符一次性传入无问题），已是稳定路径，后续直接复用；上传完记得删临时脚本。
  2. SVG `<text>` 内不能嵌 HTML 的 `<b>`/`<span>`，必须用 `<tspan>`，否则浏览器不渲染。写完应跑一次正则扫描：`<(b|span|i|strong)\b` 搜 svg 块。
  3. Chrome headless 在本机沙箱下截图无效（exit 0 但不落盘，换 user-data-dir / 临时目录均失败）→ **不要再用截图做视觉校验**，改为写脚本按坐标复核：分隔线是否穿过盒子、箭头终点是否落在说明框上、长文本是否溢出 viewBox 右边界（中文按 font-size 像素估宽）。
  4. 中文长句在 900 宽 viewBox 上的安全上限约 60 字 @ font-size 10.5，超出要拆行或缩短。
- 下期建议主题：Java 并发编程（synchronized 锁升级 → AQS/CLH → ReentrantLock → volatile 与 happens-before → 线程池七参数与拒绝策略），已与本期 L1 Mark Word 锁状态表做衔接埋点。
