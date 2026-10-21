"""Read the supplied GLB and bake a static, material-colored GPU mesh. Requires numpy."""
from pathlib import Path
import sys,json,struct,hashlib
import numpy as np

source=Path(sys.argv[1]); target=Path(sys.argv[2])/'models/katana';target.mkdir(parents=True,exist_ok=True)
raw=source.read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw))
size=struct.unpack_from('<I',raw,12)[0];g=json.loads(raw[20:20+size]);binary=raw[28+size:]
def accessor(i):
    a=g['accessors'][i];v=g['bufferViews'][a['bufferView']]
    dtype={5126:'<f4',5125:'<u4',5123:'<u2',5121:'u1'}[a['componentType']]
    c={'VEC3':3,'VEC2':2,'SCALAR':1}[a['type']];n=np.dtype(dtype).itemsize
    return np.ndarray((a['count'],c),dtype,binary,offset=v.get('byteOffset',0)+a.get('byteOffset',0),strides=(v.get('byteStride',c*n),n)).copy()

parts=[]
def visit(i,parent):
    node=g['nodes'][i]
    assert not any(k in node for k in ('translation','rotation','scale')), 'This source uses matrix nodes'
    matrix=parent@np.array(node.get('matrix',np.eye(4).T.flatten())).reshape(4,4).T
    mesh=node.get('mesh')
    # The source contains a separate scabbard behind the blade. Do not attach that
    # independently positioned presentation prop to the player's sword hand.
    if mesh is not None and mesh not in range(5,15):
        for p in g['meshes'][mesh]['primitives']:
            pos=accessor(p['attributes']['POSITION']);normal=accessor(p['attributes']['NORMAL'])
            pos=(np.c_[pos,np.ones(len(pos))]@matrix.T)[:,:3]
            normal=normal@np.linalg.inv(matrix[:3,:3]);normal/=np.maximum(np.linalg.norm(normal,axis=1,keepdims=True),1e-8)
            idx=accessor(p['indices']).reshape(-1,3)
            if np.linalg.det(matrix[:3,:3])<0:idx=idx[:,[0,2,1]]
            mat=g['materials'][p.get('material',0)];color=np.array(mat.get('pbrMetallicRoughness',{}).get('baseColorFactor',[1,1,1,1]))[:3]
            # glTF factors are linear; the Minecraft framebuffer expects display colors.
            color=np.where(color<=.0031308,color*12.92,1.055*color**(1/2.4)-.055)
            emissive=1.0 if mat.get('emissiveFactor') else 0.0
            parts.append((pos,normal,idx,color,emissive,mesh))
    for child in node.get('children',[]):visit(child,matrix)
for node in g['scenes'][g.get('scene',0)]['nodes']:visit(node,np.eye(4))

# Blade tip -> pommel direction is diagonally across the source XY plane.
# Put the grip center at zero, blade up +Y and the broad face in the XY plane.
grip=np.array([4.05,.98,-.06728673]);direction=np.array([-0.92582,.377966,0]);direction/=np.linalg.norm(direction)
right=np.array([direction[1],-direction[0],0]);basis=np.stack([right,direction,[0,0,1]])
all_pos=np.concatenate([p[0] for p in parts]);projected=(all_pos-grip)@basis.T
scale=1.1/np.ptp(projected[:,1])
vertices=[];indices=[];offset=0
for pos,normal,idx,color,emissive,mesh in parts:
    pos=(pos-grip)@basis.T*scale;normal=normal@basis.T
    data=np.c_[pos,normal,np.tile(color,(len(pos),1)),np.full(len(pos),emissive)]
    vertices.append(data);indices.append(idx.reshape(-1)+offset);offset+=len(pos)
v=np.concatenate(vertices);idx=np.concatenate(indices)
assert v.shape[1]==10 and np.isfinite(v).all() and idx.max()<len(v)
with (target/'katana.bin').open('wb') as f:
    f.write(b'GALEKATA');f.write(struct.pack('>iii',1,len(v),len(idx)))
    f.write(v.astype('>f4').tobytes());f.write(idx.astype('>i4').tobytes())
info={'source_sha256':hashlib.sha256(raw).hexdigest(),'source_credit':g['asset'].get('extras',{}),
      'vertices':len(v),'triangles':len(idx)//3,'materials':len(g['materials']),
      'changes':['Separate scabbard omitted (source meshes 5-14)','Grip aligned and scaled to Minecraft','Base material colors with emissive eyes; no PBR reflections'],
      'bounds':[v[:,:3].min(0).tolist(),v[:,:3].max(0).tolist()]}
(target/'model-info.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(info,ensure_ascii=False,indent=2))

if len(sys.argv)>3:
    # Orthographic geometry inspection, not a modification of a raster image.
    from PIL import Image,ImageDraw
    image=Image.new('RGB',(1300,800),'#24282c');draw=ImageDraw.Draw(image)
    p=v[:,:3];screen=np.c_[650+p[:,0]*620,650-p[:,1]*620]
    tris=idx.reshape(-1,3);depth=p[tris,2].mean(1)
    for tri in tris[np.argsort(depth)]:
        norm=v[tri,3:6].mean(0);shade=.45+.55*max(0,np.dot(norm,[-.3,.6,.74]));col=np.clip(v[tri[0],6:9]*shade*255,0,255).astype(int)
        draw.polygon([tuple(x) for x in screen[tri]],fill=tuple(col))
    image.save(sys.argv[3])
