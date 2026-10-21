"""Bake the supplied Kuronami GLB into grip-aligned, static meshes and small atlases.
Source is read only; requires numpy and Pillow. No source code from the asset is executed.
"""
from pathlib import Path
import hashlib, io, json, struct, sys
import numpy as np
from PIL import Image

source=Path(sys.argv[1]);out=Path(sys.argv[2])/'models/kuronami';out.mkdir(parents=True,exist_ok=True)
raw=source.read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw))
length=struct.unpack_from('<I',raw,12)[0];g=json.loads(raw[20:20+length]);binary=raw[28+length:]
def accessor(i):
    a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];c={'VEC3':3,'VEC2':2,'SCALAR':1}[a['type']]
    t={5126:'<f4',5125:'<u4',5123:'<u2',5121:'u1'}[a['componentType']];n=np.dtype(t).itemsize
    return np.ndarray((a['count'],c),t,binary,offset=v.get('byteOffset',0)+a.get('byteOffset',0),strides=(v.get('byteStride',c*n),n)).copy()
parts={}
def visit(i,parent):
    node=g['nodes'][i];assert not any(k in node for k in ('translation','rotation','scale'))
    matrix=parent@np.array(node.get('matrix',np.eye(4).T.flatten())).reshape(4,4).T
    if 'mesh'in node:
        mesh=node['mesh'];p=g['meshes'][mesh]['primitives'][0];pos=accessor(p['attributes']['POSITION']);normal=accessor(p['attributes']['NORMAL'])
        pos=(np.c_[pos,np.ones(len(pos))]@matrix.T)[:,:3]
        normal=normal@np.linalg.inv(matrix[:3,:3]);normal/=np.maximum(np.linalg.norm(normal,axis=1,keepdims=True),1e-8)
        idx=accessor(p['indices']).reshape(-1,3)
        if np.linalg.det(matrix[:3,:3])<0:idx=idx[:,[0,2,1]]
        parts[mesh]=(pos,normal,accessor(p['attributes']['TEXCOORD_0']),idx,p['material'])
    for child in node.get('children',[]):visit(child,matrix)
for node in g['scenes'][g.get('scene',0)]['nodes']:visit(node,np.eye(4))

definitions={'Kuronami':([7],[.31,-.235,.0],.95),
             'Vandal':([0,1,2,3],[.015,.32,0],1.15),
             'Sheriff':([4],[-1.235,.31,0],.52),
             'Marshal':([5,6],[-.05,.96,0],1.45),
             'Spectre':([8,9,10],[1.30,.39,0],.87)}
info={'source_sha256':hashlib.sha256(raw).hexdigest(),'source_credit':g['asset'].get('extras',{}),'models':{},
      'notes':['Static presentation geometry; no source animations supplied',
               'Base-color textures capped at 1024px, atlas cached once per selected model',
               'Water shader surfaces use blue material approximation; PBR and source water simulation omitted',
               'Kuronami retains both blades and connecting chain as one static object']}
for name,(ids,grip,target_length) in definitions.items():
    mats=sorted({parts[i][4] for i in ids});images={}
    for m in mats:
        tex=g['materials'][m].get('pbrMetallicRoughness',{}).get('baseColorTexture')
        if tex:
            image=g['images'][g['textures'][tex['index']]['source']];v=g['bufferViews'][image['bufferView']];o=v.get('byteOffset',0)
            im=Image.open(io.BytesIO(binary[o:o+v['byteLength']])).convert('RGB');im.thumbnail((1024,1024),Image.Resampling.LANCZOS);images[m]=im
    width=sum(im.width for im in images.values()) or 8;height=max([im.height for im in images.values()]+[1])+8
    atlas=Image.new('RGB',(width,height),'white');rects={};x=0
    for m,im in images.items():atlas.paste(im,(x,0));rects[m]=(x,0,im.width,im.height);x+=im.width
    atlas.save(out/(name.lower()+'.png'),optimize=True)
    (out/(name.lower()+'.png.mcmeta')).write_text('{"texture":{"blur":true,"clamp":true}}\n')
    if name=='Kuronami':
        direction=np.array([-.85,-.53,0]);direction/=np.linalg.norm(direction);basis=np.stack([[direction[1],-direction[0],0],direction,[0,0,1]])
    else:basis=np.array([[0,0,1],[0,1,0],[-1,0,0]])
    allpos=np.concatenate([parts[i][0]for i in ids]);projected=(allpos-np.array(grip))@basis.T
    scale=target_length/np.ptp(projected[:,1 if name=='Kuronami' else 2])
    vertices=[];indices=[];offset=0
    for i in ids:
        pos,normal,uv,idx,m=parts[i];pbr=g['materials'][m].get('pbrMetallicRoughness',{});emissive=0
        if m in rects:
            x,y,w,h=rects[m];uv=np.clip(uv,0,1);uv=(uv*np.array([w-1,h-1])+np.array([x+.5,y+.5]))/np.array([width,height]);color=np.array(pbr.get('baseColorFactor',[1,1,1,1]))[:3]
        else:
            uv=np.tile([.5,(height-4)/height],(len(pos),1));color=np.array([.07,.40,.63]);emissive=.45
        color=np.where(color<=.0031308,color*12.92,1.055*color**(1/2.4)-.055)
        vertices.append(np.c_[(pos-grip)@basis.T*scale,normal@basis.T,uv,np.tile(color,(len(pos),1)),np.full(len(pos),emissive)])
        indices.append(idx.reshape(-1)+offset);offset+=len(pos)
    v=np.concatenate(vertices);idx=np.concatenate(indices);assert v.shape[1]==12 and np.isfinite(v).all() and idx.max()<len(v)
    with (out/(name.lower()+'.bin')).open('wb')as f:
        f.write(b'GALEKURI');f.write(struct.pack('>iii',1,len(v),len(idx)));f.write(v.astype('>f4').tobytes());f.write(idx.astype('>i4').tobytes())
    info['models'][name]={'vertices':len(v),'triangles':len(idx)//3,'bounds':[v[:,:3].min(0).tolist(),v[:,:3].max(0).tolist()],'atlas':[width,height]}
(out/'model-info.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(info,ensure_ascii=False,indent=2))
