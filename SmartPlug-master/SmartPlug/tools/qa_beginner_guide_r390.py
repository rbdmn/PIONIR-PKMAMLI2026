from pathlib import Path
from PIL import Image, ImageOps, ImageDraw
import pdfplumber
import json
import sys
root=Path(__file__).resolve().parents[1]
revision=sys.argv[1] if len(sys.argv)>1 else '3.9'
folder=root/f'tmp/pdfs/guide-r{revision.replace(".","")}'
files=sorted(folder.glob('page-*.png'))
for batch in range(0,len(files),4):
    sheet=Image.new('RGB',(900,1280),'white')
    for i,filename in enumerate(files[batch:batch+4]):
        im=Image.open(filename).convert('RGB');im.thumbnail((438,610))
        x=(i%2)*450+(450-im.width)//2;y=(i//2)*640+20
        sheet.paste(im,(x,y));ImageDraw.Draw(sheet).text((x+4,y-16),filename.stem,fill='black')
    sheet.save(folder/f'contact-{batch//4+1}.png')
with pdfplumber.open(root/f'output/pdf/SmartPlug-Panduan-Pengguna-R{revision}.pdf') as pdf:
    violations=[]
    for i,page in enumerate(pdf.pages,1):
        for ch in page.chars:
            if ch['x0'] < 48 or ch['x1'] > page.width-48 or ch['top'] < 35 or ch['bottom'] > page.height-20:
                violations.append((i,ch['text'],'bounds'))
            color=ch.get('non_stroking_color')
            if color not in (0,(0,0,0),[0,0,0],None): violations.append((i,ch['text'],'not black',color))
    result={'pages':len(pdf.pages),'violations':violations,'all_text_black':not violations}
    (folder/'qa.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps(result))
    assert not violations
