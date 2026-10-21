"""Resume ranged downloads from official URLs; verify byte counts for every segment."""
import concurrent.futures, pathlib, urllib.request, sys, time
url, dest = sys.argv[1:3]
parts = int(sys.argv[3]) if len(sys.argv)>3 else 12
out=pathlib.Path(dest); out.parent.mkdir(parents=True,exist_ok=True)
size=int(urllib.request.urlopen(urllib.request.Request(url,method='HEAD'),timeout=60).headers['Content-Length'])
def fetch(i):
    start=size*i//parts; end=size*(i+1)//parts-1
    part=out.with_suffix(out.suffix+f'.part{i}')
    done=part.stat().st_size if part.exists() else 0
    if done==end-start+1:return part
    for attempt in range(4):
        try:
            req=urllib.request.Request(url,headers={'Range':f'bytes={start+done}-{end}'})
            with urllib.request.urlopen(req,timeout=120) as r:
                if r.status!=206:raise RuntimeError('Server did not honor Range')
                with part.open('ab') as f:
                    while b:=r.read(256*1024):f.write(b)
            if part.stat().st_size!=end-start+1:raise RuntimeError('Wrong segment size')
            print(f'{i+1}/{parts} complete',flush=True); return part
        except Exception:
            done=part.stat().st_size if part.exists() else 0
            if attempt==3:raise
            time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=parts) as pool: result=list(pool.map(fetch,range(parts)))
with out.open('wb') as f:
    for p in result:
        with p.open('rb') as r:
            while b:=r.read(1024*1024):f.write(b)
print(f'Downloaded {out}: {size} bytes',flush=True)
