import sys, pathlib
from weasyprint import HTML

root = pathlib.Path(__file__).parent
src = root / "src"

def grid_svg():
    c = 36
    ox, oy = 10, 10
    rects = []
    for gx in range(8):
        for gy in range(8):
            border = gx in (0, 7) or gy in (0, 7)
            fill = "#374151" if border else "#f5f0eb"
            rects.append(f'<rect x="{ox+gx*c}" y="{oy+gy*c}" width="{c}" height="{c}" fill="{fill}" stroke="#9ca3af" stroke-width="0.8"/>')
    rooms = [
        (1, 1, 2, 2, "Large", "16x16", "#fdba74"),
        (3, 1, 1, 2, "Hall", "long", "#fde68a"),
        (4, 1, 1, 1, "Oct", "", "#bfdbfe"),
        (5, 1, 2, 1, "Hall", "large", "#bbf7d0"),
        (4, 2, 1, 1, "Sq", "", "#e9d5ff"),
        (1, 3, 1, 1, "Small", "4x4", "#fecaca"),
        (2, 3, 1, 1, "Sq", "", "#e9d5ff"),
        (3, 3, 1, 1, "Cross", "", "#bfdbfe"),
        (4, 3, 2, 2, "Large", "16x16", "#fdba74"),
    ]
    for x, y, w, h, a, b, col in rooms:
        rects.append(f'<rect x="{ox+x*c+2}" y="{oy+y*c+2}" width="{w*c-4}" height="{h*c-4}" rx="3" fill="{col}" stroke="#7c2d12" stroke-width="1.2"/>')
        cx, cy = ox + x * c + w * c / 2, oy + y * c + h * c / 2
        rects.append(f'<text x="{cx}" y="{cy-(2 if b else -3)}" text-anchor="middle" font-size="8.5" font-family="Liberation Sans" fill="#1f2937">{a}</text>')
        if b:
            rects.append(f'<text x="{cx}" y="{cy+9}" text-anchor="middle" font-size="7" font-family="Liberation Sans" fill="#4b5563">{b}</text>')
    rects.append(f'<text x="{ox+4*c}" y="{oy+8*c+16}" text-anchor="middle" font-size="9" font-family="Liberation Sans" fill="#1f2937">Instance: 8x8 chunks (dark = unusable border)</text>')
    notes = [
        "Plot = interior 6x6 chunks = 48x48 tiles",
        "One chunk = 8x8 tiles = one cell",
        "Large square = 2x2 cells",
        "Hallway (large) = 2x1 cells; hallway (long) = 1x2",
        "4-wide shapes sit inside a whole cell",
        "Doorways: tiles 3-4 of every cell edge",
    ]
    for i, n in enumerate(notes):
        rects.append(f'<text x="{ox+8*c+22}" y="{oy+30+i*22}" font-size="9.5" font-family="Liberation Sans" fill="#374151">{n}</text>')
    return f'<figure><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 318" width="100%">{"".join(rects)}</svg><figcaption>Figure 1. The house plot as chunk cells (illustrative layout, ground floor).</figcaption></figure>'

def flow_svg():
    steps = ["Pick item in\ntrunk / Move", "Open session\n(item, rot 0)", "Walk packet\nhits WalkHandler", "Validate\n+ show ghost", "Rotate / retarget\nconfirm / cancel", "Re-validate,\nspawn, persist"]
    w, h, gap, y = 92, 46, 12, 14
    out = []
    for i, s in enumerate(steps):
        x = 6 + i * (w + gap)
        col = "#fff7ed" if i not in (2, 3) else "#fee2e2"
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{col}" stroke="#7c2d12" stroke-width="1"/>')
        for j, line in enumerate(s.split("\n")):
            out.append(f'<text x="{x+w/2}" y="{y+19+j*12}" text-anchor="middle" font-size="8.6" font-family="Liberation Sans" fill="#1f2937">{line}</text>')
        if i < len(steps) - 1:
            ax = x + w
            out.append(f'<line x1="{ax}" y1="{y+h/2}" x2="{ax+gap-1}" y2="{y+h/2}" stroke="#7c2d12" stroke-width="1.2"/><polygon points="{ax+gap-1},{y+h/2-3} {ax+gap+2},{y+h/2} {ax+gap-1},{y+h/2+3}" fill="#7c2d12"/>')
    loop_x1, loop_x2 = 6 + 4 * (w + gap) + w / 2, 6 + 3 * (w + gap) + w / 2
    out.append(f'<path d="M {loop_x1} {y+h} v 12 H {loop_x2} V {y+h}" fill="none" stroke="#9a3412" stroke-width="1" stroke-dasharray="3 2"/>')
    out.append(f'<text x="{(loop_x1+loop_x2)/2}" y="{y+h+24}" text-anchor="middle" font-size="8" font-family="Liberation Sans" fill="#9a3412">click another tile</text>')
    out.append(f'<text x="6" y="{y+h+24}" font-size="8" font-family="Liberation Sans" fill="#b91c1c">red = new hook</text>')
    return f'<figure><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 650 92" width="100%">{"".join(out)}</svg><figcaption>Figure 2. Placement session flow; the red steps are the new server-side hook.</figcaption></figure>'

subs = {"{{GRID_SVG}}": grid_svg, "{{FLOW_SVG}}": flow_svg}
names = sys.argv[1:] or [p.stem for p in sorted(src.glob("*.html"))]
for n in names:
    html = (src / f"{n}.html").read_text()
    for k, f in subs.items():
        html = html.replace(k, f())
    out = root / f"{n}.pdf"
    HTML(string=html, base_url=str(src)).write_pdf(str(out))
    print("wrote", out.name)
