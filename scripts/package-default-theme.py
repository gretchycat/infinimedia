#!/usr/bin/env python3
"""Package this checkout's custom theme without rendering or changing source artwork."""
from pathlib import Path
import argparse,json,zipfile

IMAGE_ROLES=set('main gen cbuttons titlebar posbar volume shufrep text numbers monoster playpaus pledit balance eqmain eq_ex nums_ex video genex albumlist amarok mikro mb'.split())
METADATA={'theme.json','skininfo.xml','readme.txt','region.txt','pledit.txt','albumlist.txt','viscolor.txt','screenshot.png'}

def package(source,output):
    manifest=json.loads((source/'theme.json').read_text())
    for value in manifest['images'].values():
        for name in ([value] if isinstance(value,str) else value):
            if Path(name).name != name or not (source/name).is_file():raise ValueError('Missing/local-only theme layer: '+name)
    selected={name.lower() for value in manifest['images'].values() for name in ([value] if isinstance(value,str) else value)}
    overridden={name.lower().rsplit('.',1)[0] for name in manifest['images']}
    files=sorted(p for p in source.iterdir() if p.is_file() and (p.name.lower() in selected or p.name.lower() in METADATA or p.stem.lower() in IMAGE_ROLES)
                 and (p.stem.lower() not in overridden or p.name.lower() in selected))
    if len(files)>512 or sum(p.stat().st_size for p in files)>24*1024*1024:raise ValueError('Theme exceeds import bounds')
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for p in files:
            if p.stat().st_size>8*1024*1024:raise ValueError('Oversized theme entry: '+p.name)
            item=zipfile.ZipInfo(p.name,(1980,1,1,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;item.external_attr=0o100644<<16
            archive.writestr(item,p.read_bytes())
    return len(files)
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[1]/'theme');parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    print('Packaged',package(args.source,args.output),'custom theme assets into',args.output)
