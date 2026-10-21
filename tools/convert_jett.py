"""Bake the supplied Jett GLB into Gale's compact, 15-driver GPU-skinned model.

Usage: python tools/convert_jett.py path/to/jett-valorant.zip src/main/resources/assets/galemod
Requires numpy. Source meshes/textures are read only; no external files in the archive are executed.
"""
from pathlib import Path
import sys, json, zipfile, struct, hashlib
import numpy as np

archive = Path(sys.argv[1])
output = Path(sys.argv[2]) / 'models/jett'
output.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    raw = z.read('source/Jett.glb')
assert struct.unpack_from('<4sII', raw) == (b'glTF', 2, len(raw))
json_size = struct.unpack_from('<I', raw, 12)[0]
g = json.loads(raw[20:20 + json_size])
binary = raw[28 + json_size:]

def accessor(index):
    a = g['accessors'][index]
    v = g['bufferViews'][a['bufferView']]
    dtype = {5126:'<f4', 5123:'<u2', 5121:'u1', 5125:'<u4'}[a['componentType']]
    components = {'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}[a['type']]
    size = np.dtype(dtype).itemsize
    result = np.ndarray((a['count'], components), dtype=dtype, buffer=binary,
        offset=v.get('byteOffset', 0) + a.get('byteOffset', 0),
        strides=(v.get('byteStride', components * size), size)).copy()
    if a.get('normalized'):
        result = result.astype(np.float64) / np.iinfo(dtype).max
    return result

def transform(node):
    x,y,z,w = node.get('rotation', [0,0,0,1])
    m = np.eye(4)
    m[:3,:3] = [[1-2*y*y-2*z*z,2*x*y-2*z*w,2*x*z+2*y*w],
                 [2*x*y+2*z*w,1-2*x*x-2*z*z,2*y*z-2*x*w],
                 [2*x*z-2*y*w,2*y*z+2*x*w,1-2*x*x-2*y*y]]
    m[:3,:3] = m[:3,:3] @ np.diag(node.get('scale', [1,1,1]))
    m[:3,3] = node.get('translation', [0,0,0])
    return m

nodes = g['nodes']
parents = [-1] * len(nodes)
for i,n in enumerate(nodes):
    for child in n.get('children', []): parents[child] = i
world = [None] * len(nodes)
curled = [None] * len(nodes)
for i,n in enumerate(nodes):
    m = transform(n)
    extra = np.eye(4)
    name = n.get('name', '')
    if any(name == side + '_' + finger + part for side in ('L','R')
           for finger in ('Index','Middle','Ring','Pinky') for part in ('1','2','3')):
        angle = np.deg2rad({'1':30,'2':55,'3':40}[name[-1]] * (1 if name.startswith('L_') else -1))
        extra[:3,:3] = [[np.cos(angle),-np.sin(angle),0],[np.sin(angle),np.cos(angle),0],[0,0,1]]
    world[i] = (world[parents[i]] if parents[i] >= 0 else np.eye(4)) @ m
    curled[i] = (curled[parents[i]] if parents[i] >= 0 else np.eye(4)) @ m @ extra

names = ['Pelvis','Spine4','Head','L_Shoulder','L_Elbow','L_Hand','R_Shoulder','R_Elbow','R_Hand',
         'L_Hip','L_Knee','L_Foot','R_Hip','R_Knee','R_Foot']
driver_nodes = [next(i for i,n in enumerate(nodes) if n.get('name') == name) for name in names]
driver_of = {}
for i,n in enumerate(nodes):
    node = i
    while node >= 0 and node not in driver_nodes: node = parents[node]
    driver_of[i] = driver_nodes.index(node) if node >= 0 else 0
# Clavicles and shoulder pads follow the torso rather than the upper arms.
for i,n in enumerate(nodes):
    if n.get('name') in ('L_Clavicle','R_Clavicle','L_ShoulderPad','R_ShoulderPad'): driver_of[i] = 1
    if n.get('name') in ('Neck','Collar'): driver_of[i] = 2
    if n.get('name', '').startswith('Spine'): driver_of[i] = 1

skin = g['skins'][0]
inv_mesh = np.linalg.inv(world[0])
inverse_bind = accessor(skin['inverseBindMatrices']).reshape(-1,4,4).transpose(0,2,1)
rest = np.array([inv_mesh @ world[n] @ inverse_bind[k] for k,n in enumerate(skin['joints'])])
fist = np.array([inv_mesh @ curled[n] @ inverse_bind[k] for k,n in enumerate(skin['joints'])])
# Unreal's export faces +X; Gale's character faces -Z, with the right arm at +X.
axis = np.array([[0,0,1],[0,1,0],[-1,0,0]], dtype=np.float64)
pivots = np.array([(inv_mesh @ world[n])[:3,3] @ axis.T for n in driver_nodes])

def translate(v):
    m=np.eye(4); m[:3,3]=v; return m

def align(a,b):
    a=a/np.linalg.norm(a); b=b/np.linalg.norm(b)
    v=np.cross(a,b); c=np.dot(a,b)
    if c > 0.999999: return np.eye(4)
    k=np.array([[0,-v[2],v[1]],[v[2],0,-v[0]],[-v[1],v[0],0]])
    m=np.eye(4); m[:3,:3]=np.eye(3)+k+k@k/(1+c); return m

neutral = np.repeat(np.eye(4)[None], 15, axis=0)
for upper,lower,end in ((3,4,5),(6,7,8),(9,10,11),(12,13,14)):
    pivot=pivots[upper]
    u=translate(pivot) @ align(pivots[lower]-pivot,np.array([0,-1,0])) @ translate(-pivot)
    elbow=(u @ np.append(pivots[lower],1))[:3]
    end_point=(u @ np.append(pivots[end],1))[:3]
    f=translate(elbow) @ align(end_point-elbow,np.array([0,-1,0])) @ translate(-elbow) @ u
    neutral[upper]=u; neutral[lower]=f; neutral[end]=f
new_pivots=np.array([(neutral[i]@np.append(pivots[i],1))[:3] for i in range(15)])

materials=[]
repaired_vertices=0
for primitive in g['meshes'][0]['primitives']:
    attrs=primitive['attributes']
    pos=accessor(attrs['POSITION']).astype(np.float64)
    normal=accessor(attrs['NORMAL']).astype(np.float64) @ axis.T
    uv=accessor(attrs['TEXCOORD_0']).astype(np.float64)
    joints=accessor(attrs['JOINTS_0']).astype(int)
    weights=accessor(attrs['WEIGHTS_0']).astype(np.float64)
    indices=accessor(primitive['indices']).astype(np.int32).reshape(-1,3)
    # This supplied Unreal body export contains malformed UNORM8 weights (including
    # zero totals and packed index bytes). Its JOINTS_0 dominant joints are intact.
    # Rebuild that mesh from the dominant joints, then smooth across welded topology.
    repair=np.mean(np.abs(weights.sum(1)-1)>0.02)>0.1
    if repair:
        repaired_vertices+=len(pos)
        weights[:]=0; weights[:,0]=1
    total=weights.sum(1); missing=total<1e-8
    weights[missing,0]=1; weights/=np.maximum(weights.sum(1,keepdims=True),1e-8)
    hp=np.c_[pos,np.ones(len(pos))]
    skin_pos=np.einsum('nk,nkij,nj->ni',weights,rest[joints],hp)[:,:3]
    grip_pos=np.einsum('nk,nkij,nj->ni',weights,fist[joints],hp)[:,:3]
    # Zero-weight vertices in this exporter use their original mesh coordinates.
    skin_pos[missing]=pos[missing]; grip_pos[missing]=pos[missing]
    skin_pos=skin_pos@axis.T; grip_pos=grip_pos@axis.T
    mapped=np.array([[driver_of[skin['joints'][b]] for b in row] for row in joints])
    compact=np.zeros((len(pos),15))
    for k in range(4): np.add.at(compact,(np.arange(len(pos)),mapped[:,k]),weights[:,k])
    if repair:
        _,weld=np.unique(np.round(pos,6),axis=0,return_inverse=True)
        count=weld.max()+1
        mass=np.bincount(weld,minlength=count)[:,None]
        smooth=np.zeros((count,15)); np.add.at(smooth,weld,compact); smooth/=mass
        edges=np.concatenate([indices[:,[0,1]],indices[:,[1,2]],indices[:,[2,0]]])
        edges=np.unique(np.sort(weld[edges],axis=1),axis=0)
        edges=edges[edges[:,0]!=edges[:,1]]
        degree=np.bincount(edges.reshape(-1),minlength=count)[:,None]
        for _ in range(5):
            neighbor=np.zeros_like(smooth)
            np.add.at(neighbor,edges[:,0],smooth[edges[:,1]])
            np.add.at(neighbor,edges[:,1],smooth[edges[:,0]])
            smooth=.6*smooth+.4*neighbor/np.maximum(degree,1)
        compact=smooth[weld]
        # Smooth the small finger-curl displacement on the same welded surface.
        delta=np.zeros((count,3)); np.add.at(delta,weld,grip_pos-skin_pos); delta/=mass
        for _ in range(3):
            neighbor=np.zeros_like(delta)
            np.add.at(neighbor,edges[:,0],delta[edges[:,1]])
            np.add.at(neighbor,edges[:,1],delta[edges[:,0]])
            delta=.6*delta+.4*neighbor/np.maximum(degree,1)
        grip_pos=skin_pos+delta[weld]
    bones=np.argsort(compact,axis=1)[:,-4:][:,::-1]
    blend=np.take_along_axis(compact,bones,axis=1)
    blend/=np.maximum(blend.sum(1,keepdims=True),1e-8)
    posed=np.einsum('nk,nkij,nj->ni',blend,neutral[bones],np.c_[skin_pos,np.ones(len(pos))])[:,:3]
    posed_grip=np.einsum('nk,nkij,nj->ni',blend,neutral[bones],np.c_[grip_pos,np.ones(len(pos))])[:,:3]
    normals=np.einsum('nk,nkij,nj->ni',blend,neutral[bones][...,:3,:3],normal)
    normals/=np.maximum(np.linalg.norm(normals,axis=1,keepdims=True),1e-8)
    arm_weight=compact[:,3:9].sum(1)
    arm_indices=indices[np.all(arm_weight[indices]>.97,axis=1)].reshape(-1)
    material=g['materials'][primitive['material']]
    texture=material['pbrMetallicRoughness']['baseColorTexture']
    tf=texture.get('extensions',{}).get('KHR_texture_transform',{})
    uv=uv*np.array(tf.get('scale',[1,1]))+np.array(tf.get('offset',[0,0]))
    image=g['images'][g['textures'][texture['index']]['source']]
    view=g['bufferViews'][image['bufferView']]
    texture_name=('body','hair','eye')[primitive['material']]+'.png'
    (output/texture_name).write_bytes(binary[view.get('byteOffset',0):view.get('byteOffset',0)+view['byteLength']])
    (output/(texture_name+'.mcmeta')).write_text('{"texture":{"blur":true,"clamp":false}}\n')
    materials.append(dict(pos=posed,normal=normals,uv=uv,bones=bones,weights=blend,grip=posed_grip-posed,
        indices=indices.reshape(-1),arms=arm_indices,texture=texture_name,blend=material.get('alphaMode')=='BLEND'))

all_pos=np.concatenate([m['pos'] for m in materials])
floor=all_pos[:,1].min(); height=all_pos[:,1].max()-floor; scale=1.8/height
for m in materials:
    m['pos'][:,1]-=floor; m['pos']*=scale; m['grip']*=scale
new_pivots[:,1]-=floor; new_pivots*=scale
driver_parents=[-1,0,1,1,3,4,1,6,7,0,9,10,0,12,13]
with (output/'jett.bin').open('wb') as f:
    f.write(b'GALEJETT'); f.write(struct.pack('>ii',1,15))
    for i,p in enumerate(new_pivots): f.write(struct.pack('>i3f',driver_parents[i],*p))
    f.write(struct.pack('>i',len(materials)))
    for m in materials:
        texture=('models/jett/'+m['texture']).encode()
        f.write(struct.pack('>H',len(texture))+texture)
        f.write(struct.pack('>?iii',m['blend'],len(m['pos']),len(m['indices']),len(m['arms'])))
        vertices=np.c_[m['pos'],m['normal'],m['uv'],m['bones'],m['weights'],m['grip']]
        assert vertices.shape[1]==19 and np.all(np.isfinite(vertices))
        f.write(vertices.astype('>f4').tobytes())
        f.write(m['indices'].astype('>i4').tobytes()); f.write(m['arms'].astype('>i4').tobytes())
summary={'source_sha256':hashlib.sha256(raw).hexdigest(),'source_bones':len(skin['joints']),
    'animation_drivers':15,'source_animations':len(g.get('animations',[])),
    'vertices':sum(len(m['pos']) for m in materials),'triangles':sum(len(m['indices'])//3 for m in materials),
    'first_person_triangles':sum(len(m['arms'])//3 for m in materials),
    'repaired_body_weight_vertices':repaired_vertices,
    'drivers':dict(zip(names,new_pivots.tolist()))}
(output/'model-info.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
