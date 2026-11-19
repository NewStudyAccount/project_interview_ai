import io, re, sys

path = sys.argv[1] if len(sys.argv) > 1 else '../java_interview_31_pool_isolation.html'
s = io.open(path, encoding='utf-8').read()
issues = []

# 1. 标签配平（仅常见容器标签）
for t in ['div', 'figure', 'table', 'thead', 'tbody', 'tr', 'td', 'th', 'pre', 'code', 'svg']:
    o = len(re.findall(r'<' + t + r'[\s>]', s))
    c = len(re.findall(r'</' + t + r'>', s))
    if o != c:
        issues.append(f'[配平] <{t}> 开 {o} 关 {c}')

# 2. SVG 内禁 HTML 标签
for m in re.finditer(r'<svg.*?</svg>', s, re.S):
    blk = m.group(0)
    for bad in ['<b ', '<b>', '<span', '<strong', '<i>', '<table', '<div', '<p>', '<br']:
        if bad in blk:
            issues.append(f'[SVG内混HTML] {bad} @pos {m.start()}')

# 3. url(#id) 必须有定义
ids = set(re.findall(r'<marker[^>]*id="([^"]+)"', s)) | set(re.findall(r'<(?:linearGradient|filter|clipPath)[^>]*id="([^"]+)"', s))
for m in re.finditer(r'url\(#([^)]+)\)', s):
    if m.group(1) not in ids:
        issues.append(f'[marker未定义] #{m.group(1)}')

def width(txt, fs):
    w = 0
    for ch in txt:
        w += fs * (1.0 if ord(ch) > 0x2E80 else 0.55)
    return w

# 4~8. 逐图几何校验
for mi, m in enumerate(re.finditer(r'<svg[^>]*viewBox="0 0 (\d+) (\d+)".*?</svg>', s, re.S)):
    blk = m.group(0)
    VW, VH = int(m.group(1)), int(m.group(2))
    fig = mi + 1
    texts = []
    for tm in re.finditer(r'<text([^>]*)>(.*?)</text>', blk, re.S):
        attrs, body = tm.group(1), tm.group(2)
        if 'tspan' in body:
            plain = re.sub(r'<[^>]+>', '', body)
        else:
            plain = body
        plain = re.sub(r'&[a-z]+;', 'x', plain).strip()
        fs = float(re.search(r'font-size="([\d.]+)"', attrs).group(1)) if 'font-size' in attrs else 12.0
        x = float(re.search(r'\bx="([-\d.]+)"', attrs).group(1)) if re.search(r'\bx="', attrs) else 0
        y = float(re.search(r'\by="([-\d.]+)"', attrs).group(1)) if re.search(r'\by="', attrs) else 0
        anchor = re.search(r'text-anchor="(\w+)"', attrs)
        anchor = anchor.group(1) if anchor else 'start'
        w = width(plain, fs)
        if anchor == 'middle':
            x0, x1 = x - w / 2, x + w / 2
        elif anchor == 'end':
            x0, x1 = x - w, x
        else:
            x0, x1 = x, x + w
        if x1 > VW - 2:
            issues.append(f'[图{fig} 越右] "{plain[:22]}" x1={x1:.0f}>{VW}')
        if x0 < 0:
            issues.append(f'[图{fig} 越左] "{plain[:22]}" x0={x0:.0f}<0')
        if y > VH - 1:
            issues.append(f'[图{fig} 越下] "{plain[:22]}" y={y:.0f}>{VH}')
        texts.append((x0, x1, y - fs * 0.8, y + fs * 0.2, plain[:14], fs))

    # 7. 文本包围盒重叠（同图内，横向纵向都相交）
    for i in range(len(texts)):
        for j in range(i + 1, len(texts)):
            a, b = texts[i], texts[j]
            ox = min(a[1], b[1]) - max(a[0], b[0])
            oy = min(a[3], b[3]) - max(a[2], b[2])
            if ox > 2 and oy > 1:
                issues.append(f'[图{fig} 文字重叠] "{a[4]}" × "{b[4]}" 重叠 {ox:.0f}x{oy:.1f}px')

    # 6. rect 越界
    for rm in re.finditer(r'<rect([^>]*)/>', blk):
        at = rm.group(1)
        gx = lambda k, d=0: float(re.search(k + r'="([-\d.]+)"', at).group(1)) if re.search(k + r'="', at) else d
        x, y, w, h = gx('x'), gx('y'), gx('width'), gx('height')
        if x + w > VW + 1 or x < -1 or y + h > VH + 1:
            issues.append(f'[图{fig} rect越界] x={x:.0f} w={w:.0f} y={y:.0f} h={h:.0f}')

    # 8. 退化多边形（点多于 5 且坐标只有一个逗号分隔的串）
    for pm in re.finditer(r'<polygon([^>]*)/>', blk):
        pts = re.search(r'points="([^"]+)"', pm.group(1))
        if pts and len(pts.group(1).split()) > 5:
            issues.append(f'[图{fig} 多边形点数>5] {pts.group(1)[:40]}')

print(f'检查 {path}: {len(issues)} issue')
for i in issues:
    print('  ', i)
