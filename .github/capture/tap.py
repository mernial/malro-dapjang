# uiautomator 덤프에서 글자(text 또는 content-desc)가 [want] 와 같은 노드(없으면 포함하는 노드)의 가운데 좌표
import re, sys, xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
try:
    nodes = list(ET.parse(path).getroot().iter("node"))
except Exception:
    sys.exit(1)
def center(n):
    m = re.findall(r"\d+", n.get("bounds", ""))
    if len(m) != 4: return None
    x1, y1, x2, y2 = map(int, m)
    return (x1 + x2) // 2, (y1 + y2) // 2
def labels(n):
    return [(n.get("text") or "").strip(), (n.get("content-desc") or "").strip()]
# 1) 글자가 똑같은 것 (버튼 우선)  2) 포함하는 것
for exact in (True, False):
    hits = [n for n in nodes if any((l == want) if exact else (want in l) for l in labels(n))]
    hits.sort(key=lambda n: 0 if n.get("clickable") == "true" or "Button" in (n.get("class") or "") else 1)
    for n in hits:
        c = center(n)
        if c: print(*c); sys.exit(0)
sys.exit(1)
