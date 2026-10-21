package gale.module.impl.render;

import gale.Gale;
import gale.module.Category;
import gale.module.Module;
import gale.render.model.KatanaRenderer;
import gale.render.model.KuronamiRenderer;
import gale.module.setting.ModeSetting;
import gale.util.ChatUtil;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.client.renderer.vertex.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.item.*;
import net.minecraft.util.*;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.model.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.tuple.Pair;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.util.*;

/** Perspective-aware replacement: original GUI models are retained verbatim. */
public abstract class WeaponModel extends Module {
    private static final List<WeaponModel> HANDLERS=new ArrayList<>();
    public final ModeSetting skin;
    private final boolean bow;
    private final Map<String,KuronamiRenderer> cached=new HashMap<>();
    private final Set<String> failedSkins=new HashSet<>();
    private long frames;
    private int selectedVariant=-1;
    private final long[] pullingFrames=new long[3];
    private float handPartial;
    private KatanaRenderer renderer;
    private final Map<Item,Wrapper> models=new IdentityHashMap<>();
    private ItemCameraTransforms.TransformType perspective=ItemCameraTransforms.TransformType.NONE;
    private final long[] contexts=new long[ItemCameraTransforms.TransformType.values().length];

    protected WeaponModel(String name,boolean bow,String defaultSkin,String... skins) {
        super(name,Category.RENDER,bow?"弓使用怒麟枪械模型；背包保留原版图标":"剑使用怒麟双刀或 Katana；背包保留原版图标");
        this.bow=bow;skin=mode(bow?"Model":"Skin",defaultSkin,skins);
        HANDLERS.add(this);MinecraftForge.EVENT_BUS.register(this);
    }
    @Override public String getSuffix(){return skin.get();}
    private boolean accepts(Item item){return bow?item instanceof ItemBow:item instanceof ItemSword;}
    @SubscribeEvent(receiveCanceled=true) public void handTime(RenderHandEvent event){handPartial=event.partialTicks;}

    @SubscribeEvent public void bake(ModelBakeEvent event) {
        models.clear();
        for(Item item:Item.itemRegistry) if(accepts(item)) {
            ResourceLocation id=Item.itemRegistry.getNameForObject(item);
            ModelResourceLocation key=new ModelResourceLocation(id,"inventory");
            IBakedModel original=event.modelRegistry.getObject(key);
            if(original==null)continue;
            Wrapper model=new Wrapper(original);models.put(item,model);event.modelRegistry.putObject(key,model);
            if(bow && id.getResourceDomain().equals("minecraft"))for(int pull=0;pull<3;pull++) {
                ModelResourceLocation variant=new ModelResourceLocation("minecraft:bow_pulling_"+pull,"inventory");
                IBakedModel pulling=event.modelRegistry.getObject(variant);
                if(pulling!=null)event.modelRegistry.putObject(variant,new Wrapper(pulling,pull));
            }
        }
        Gale.LOG.info("{} perspectives installed for {} item models; GUI delegates preserved",getName(),models.size());
    }

    private boolean ready() {
        String selected=skin.get();if(failedSkins.contains(selected))return false;
        try {
            if(selected.equals("Katana")){if(renderer==null)renderer=new KatanaRenderer();}
            else if(!cached.containsKey(selected))cached.put(selected,new KuronamiRenderer(selected));
        }catch(Throwable e){failedSkins.add(selected);Gale.LOG.error("Weapon model unavailable: "+selected,e);ChatUtil.error(selected+" 模型加载失败，已保留原版物品；详见日志");return false;}
        if(!(TileEntityItemStackRenderer.instance instanceof WeaponTile))
            TileEntityItemStackRenderer.instance=new WeaponTile(TileEntityItemStackRenderer.instance);
        return true;
    }
    public boolean replaces(ItemStack stack) { return isEnabled() && !failedSkins.contains(skin.get()) && stack!=null && accepts(stack.getItem()) && models.containsKey(stack.getItem()); }
    public int cachedModels(){return cached.size()+(renderer==null?0:1);}
    public long pullingDraws(int stage){return pullingFrames[stage];}
    public static int dispatcherLayers(){int n=0;TileEntityItemStackRenderer r=TileEntityItemStackRenderer.instance;while(r instanceof WeaponTile){n++;r=((WeaponTile)r).inner;}return n;}
    public boolean guiPreserved(Item item) {
        Wrapper w=models.get(item);
        if(w==null)return false;
        IFlexibleBakedModel gui=w.handlePerspective(ItemCameraTransforms.TransformType.GUI).getLeft();
        IBakedModel expected=w.original instanceof IPerspectiveAwareModel
            ? ((IPerspectiveAwareModel)w.original).handlePerspective(ItemCameraTransforms.TransformType.GUI).getLeft() : w.original;
        if(!sameQuads(gui.getGeneralQuads(),expected.getGeneralQuads()) || !gui.getParticleTexture().getIconName().equals(expected.getParticleTexture().getIconName())
            || gui.isBuiltInRenderer()!=expected.isBuiltInRenderer())return false;
        for(EnumFacing face:EnumFacing.values())if(!sameQuads(gui.getFaceQuads(face),expected.getFaceQuads(face)))return false;
        return true;
    }
    private static boolean sameQuads(List<BakedQuad> a,List<BakedQuad> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!Arrays.equals(a.get(i).getVertexData(),b.get(i).getVertexData()) || a.get(i).getTintIndex()!=b.get(i).getTintIndex())return false;
        return true;
    }
    public int replacedTypes() { return models.size(); }
    public long draws() { return frames; }
    public int triangles() { return skin.is("Katana")?(renderer==null?0:renderer.triangles):(cached.containsKey(skin.get())?cached.get(skin.get()).triangles:0); }
    public long draws(ItemCameraTransforms.TransformType type){return contexts[type.ordinal()];}

    /** One shared dispatcher avoids recursively wrapping the global native item renderer. */
    private static final class WeaponTile extends TileEntityItemStackRenderer {
        private final TileEntityItemStackRenderer inner;
        WeaponTile(TileEntityItemStackRenderer inner){this.inner=inner;}
        @Override public void renderByItem(ItemStack stack) {
            for(WeaponModel module:HANDLERS)if(module.replaces(stack) && module.ready()) {
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate(.5f,.5f,.5f);GlStateManager.rotate(180,0,1,0);
                    float light=mc.thePlayer==null?1:mc.thePlayer.getBrightness(1);
                    if(module.skin.is("Katana"))module.renderer.draw(light,stack.hasEffect());
                    else module.cached.get(module.skin.get()).draw(light,stack.hasEffect());
                    module.frames++;module.contexts[module.perspective.ordinal()]++;
                    if(module.selectedVariant>=0)module.pullingFrames[module.selectedVariant]++;
                } finally {GlStateManager.popMatrix();}
                return;
            }
            inner.renderByItem(stack);
        }
    }

    private class Flexible implements IFlexibleBakedModel {
        final IBakedModel original;
        Flexible(IBakedModel original){this.original=original;}
        @Override public List<BakedQuad> getFaceQuads(EnumFacing face){return original.getFaceQuads(face);}
        @Override public List<BakedQuad> getGeneralQuads(){return original.getGeneralQuads();}
        @Override public boolean isAmbientOcclusion(){return original.isAmbientOcclusion();}
        @Override public boolean isGui3d(){return original.isGui3d();}
        @Override public boolean isBuiltInRenderer(){return original.isBuiltInRenderer();}
        @Override public TextureAtlasSprite getParticleTexture(){return original.getParticleTexture();}
        @Override public ItemCameraTransforms getItemCameraTransforms(){return original.getItemCameraTransforms();}
        @Override public VertexFormat getFormat(){return DefaultVertexFormats.ITEM;}
    }
    private final class BuiltIn extends Flexible {
        BuiltIn(IBakedModel original){super(original);}
        @Override public List<BakedQuad> getFaceQuads(EnumFacing face){return Collections.emptyList();}
        @Override public List<BakedQuad> getGeneralQuads(){return Collections.emptyList();}
        @Override public boolean isBuiltInRenderer(){return true;}
    }
    private final class Wrapper extends Flexible implements IPerspectiveAwareModel {
        private final int variant;
        private final Matrix4f charged=new Matrix4f(),undoBowRotation=new Matrix4f();
        private final Vector3f chargeTranslation=new Vector3f();
        private Pair<? extends IFlexibleBakedModel,Matrix4f> chargedModel;
        private final Map<ItemCameraTransforms.TransformType,Pair<? extends IFlexibleBakedModel,Matrix4f>> normal=new EnumMap<>(ItemCameraTransforms.TransformType.class);
        private final Map<ItemCameraTransforms.TransformType,Pair<? extends IFlexibleBakedModel,Matrix4f>> katana=new EnumMap<>(ItemCameraTransforms.TransformType.class);
        Wrapper(IBakedModel original) {
            this(original,-1);
        }
        Wrapper(IBakedModel original,int variant) {
            super(original);Flexible normalModel=new Flexible(original);BuiltIn custom=new BuiltIn(original);
            this.variant=variant;
            undoBowRotation.rotX(8*.017453292f);
            Matrix4f r=new Matrix4f();r.rotY(12*.017453292f);undoBowRotation.mul(r);r.rotZ(18*.017453292f);undoBowRotation.mul(r);
            chargedModel=Pair.of(custom,charged);
            for(ItemCameraTransforms.TransformType type:ItemCameraTransforms.TransformType.values()) {
                normal.put(type,Pair.of(normalModel,ForgeHooksClient.getMatrix(original.getItemCameraTransforms().getTransform(type))));
                Matrix4f transform=new Matrix4f();transform.setIdentity();
                switch(type) {
                    case FIRST_PERSON: transform=transform(0,.20f,-.30f,0,-45,-10,1.25f);break;
                    case THIRD_PERSON: transform=transform(0,.04f,0,0,0,180,.75f);break;
                    case GROUND: transform=transform(0,.06f,0,0,0,-80,.42f);break;
                    case FIXED: transform=transform(0,-.12f,0,0,0,-20,.65f);break;
                    default:break;
                }
                if(bow)switch(type) {
                    case FIRST_PERSON: transform=transform(0,.28f,-.20f,0,-45,0,2.0f);break;
                    case THIRD_PERSON: transform=transform(0,.04f,0,-90,0,0,.80f);break;
                    case GROUND: transform=transform(0,.10f,0,0,90,0,.60f);break;
                    case FIXED: transform=transform(0,-.05f,0,0,90,0,.75f);break;
                    default:break;
                }
                katana.put(type,Pair.of(custom,transform));
            }
        }
        @Override public Pair<? extends IFlexibleBakedModel,Matrix4f> handlePerspective(ItemCameraTransforms.TransformType type) {
            if(type!=ItemCameraTransforms.TransformType.GUI && isEnabled() && ready()){
                perspective=type;selectedVariant=variant;
                if(bow && type==ItemCameraTransforms.TransformType.FIRST_PERSON && mc.thePlayer!=null && mc.thePlayer.getItemInUseCount()>0
                    && mc.thePlayer.getItemInUse()!=null && mc.thePlayer.getItemInUse().getItemUseAction()==EnumAction.BOW){
                    // Invert only vanilla's bow deformation, after preTransform's 2x scale.
                    // The bow's use timer and networking are never changed.
                    float ticks=mc.thePlayer.getItemInUse().getMaxItemUseDuration()-(mc.thePlayer.getItemInUseCount()-handPartial+1);
                    float pull=ticks/20;pull=Math.min(1,(pull*pull+2*pull)/3);
                    float shake=pull>.1f?MathHelper.sin((ticks-.1f)*1.3f)*(pull-.1f)*.01f:0;
                    float depth=1+.2f*pull;
                    charged.setIdentity();charged.m22=1/depth;charged.mul(undoBowRotation);
                    chargeTranslation.set(.45f,(-.2f-shake)*.5f,-.05f*pull/depth);charged.setTranslation(chargeTranslation);
                    charged.mul(katana.get(type).getRight());return chargedModel;
                }
                return katana.get(type);
            }
            if(original instanceof IPerspectiveAwareModel)return ((IPerspectiveAwareModel)original).handlePerspective(type);
            return normal.get(type);
        }
    }
    private static Matrix4f transform(float x,float y,float z,float rx,float ry,float rz,float scale) {
        Matrix4f result=new Matrix4f();result.setIdentity();Matrix4f r=new Matrix4f();
        r.rotX(rx*.017453292f);result.mul(r);r.rotY(ry*.017453292f);result.mul(r);r.rotZ(rz*.017453292f);result.mul(r);
        result.setScale(scale);result.setTranslation(new Vector3f(x,y,z));return result;
    }
}
