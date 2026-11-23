"""Optional PDF inspection: install review-requirements.txt; outputs go to /tmp by default."""
import sys
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
root=Path(__file__).resolve().parents[1]
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path('/tmp/irr-report-review')
out.mkdir(parents=True,exist_ok=True)
doc=pymupdf.open(root/'report/report.pdf')
assert 10<=len(doc)<=15,f'Expected 10-15 pages, found {len(doc)}'
width=320;height=475
sheet=Image.new('RGB',(width*4,height*((len(doc)+3)//4)),'#ddd')
draw=ImageDraw.Draw(sheet)
for i,page in enumerate(doc):
    pix=page.get_pixmap(matrix=pymupdf.Matrix(1.4,1.4))
    pix.save(out/f'page-{i+1:02}.png')
    im=Image.open(out/f'page-{i+1:02}.png');im.thumbnail((width-12,height-25))
    x=(i%4)*width+6;y=(i//4)*height+20
    sheet.paste(im,(x,y));draw.text((x,y-16),f'Page {i+1}',fill='black')
sheet.save(out/'contact-sheet.png')
(out/'text.txt').write_text('\n\n'.join(p.get_text() for p in doc))
print(f'{len(doc)} A4 pages rendered to {out}')
