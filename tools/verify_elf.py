"""Check every PT_LOAD segment of packaged ELF libraries, independently of linker claims."""
import pathlib, struct, sys, zipfile

def verify(name, data):
    if data[:4] != b'\x7fELF': raise ValueError(f'{name}: not ELF')
    if data[4] != 2 or data[5] != 1: raise ValueError(f'{name}: expected little-endian ELF64')
    offset=struct.unpack_from('<Q',data,32)[0]
    size,count=struct.unpack_from('<HH',data,54)
    aligns=[]
    for i in range(count):
        h=struct.unpack_from('<IIQQQQQQ',data,offset+i*size)
        if h[0]==1:
            alignment=h[7];aligns.append(alignment)
            if alignment<16384 or (h[2]-h[3])%16384: raise ValueError(f'{name}: fails 16KB PT_LOAD alignment: {alignment}')
    if not aligns: raise ValueError(f'{name}: no PT_LOAD segments')
    print(f'{name}: PASS PT_LOAD alignment={aligns}')

path=pathlib.Path(sys.argv[1])
if path.suffix in ('.apk','.aar'):
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith('.so') and ('arm64-v8a/' in name or 'x86_64/' in name) and not name.startswith('prefab/'):
                verify(name,z.read(name))
else: verify(path.name,path.read_bytes())
