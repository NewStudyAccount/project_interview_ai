import io, re, sys

path = sys.argv[1]
s = io.open(path, encoding='utf-8').read()

def width(txt, fs):
    w = 0
    for ch in txt:
        w += fs * (1.0 if ord(ch) > 0x2E80 else 0.55)
    return w

issues = []
for mi, m in enumerate(re.finditer(r'<svg[^>]*viewBox="0 0 (\d+) (\d+)".*?</svg>', s, re.S)):
    blk = m.group(0)
    fig = mi + 1
    rects = []
    for rm in re.finditer(r'<rect([^>]*)/>', blk):
        at = rm.group(1)
        gx = lambda k, d=0: float(re.search(k + r'="([-\d.]+)"', at).group(1)) if re.search(k + r'="', at) else d
        rects.append((gx('x'), gx('y'), gx('width'), gx('height')))
    texts = []
    for tm in re.finditer(r'<text([^>]*)>(.*?)</text>', blk, re.S):
        attrs, body = tm.group(1), tm.group(2)
        plain = re.sub(r'<[^>]+>', '', body)
        plain = re.sub(r'&[a-z]+;', 'x', plain).strip()
        if not plain:
            continue
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
        texts.append((x0, x1, y - fs * 0.8, y + fs * 0.2, plain[:20]))

    # 9. 文字压 rect 边框：文字 bbox 跨越 rect 的任一条边（部分在内、部分在外）
    for (x0, x1, y0, y1, label) in texts:
        for (rx, ry, rw, rh) in rects:
            if rw <= 0 or rh <= 0:
                continue
            inx = x0 > rx + 1 and x1 < rx + rw - 1
            iny = y0 > ry + 1 and y1 < ry + rh - 1
            outx = x1 < rx + 1 or x0 > rx + rw - 1
            outy = y1 < ry + 1 or y0 > ry + rh - 1
            straddle_x = not inx and not outx
            straddle_y = not iny and not outy
            if (straddle_x and not outy) or (straddle_y and not outx):
                issues.append(f'[图{fig} 文字压边框] "{label}" x={x0:.0f}..{x1:.0f} y={y0:.0f}..{y1:.0f} 跨 rect({rx:.0f},{ry:.0f},{rw:.0f}x{rh:.0f})')

print(f'edge-straddle check {path}: {len(issues)} issue')
for i in issues:
    print('  ', i)
