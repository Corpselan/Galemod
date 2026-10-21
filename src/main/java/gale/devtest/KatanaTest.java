package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.*;
import gale.module.impl.render.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.*;
import net.minecraft.util.*;
import org.lwjgl.opengl.GL11;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

final class KatanaTest {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private static final Item[] SWORDS={Items.wooden_sword,Items.stone_sword,Items.iron_sword,Items.golden_sword,Items.diamond_sword};
    private static final String[] NAMES={"wood","stone","iron","gold","diamond"};
    private static String shot;
    private static boolean fixed,glClean=true;
    private static net.minecraft.util.MouseHelper mouse;
    private static long before;
    private static SwordModel swords(){return Gale.modules().get(SwordModel.class);}
    private static JettModel jett(){return Gale.modules().get(JettModel.class);}
    private static void check(String n,boolean ok,Object detail){DevTest.check(n,ok,String.valueOf(detail));}
    static void phases() {
        Gale.BUS.listen(MouseEvent.class,Priority.HIGHEST,e->{if(fixed)e.cancel();},"katana-test");
        Gale.BUS.listen(InputEvent.class,Priority.LOWEST,e->{if(fixed){e.stop();e.setSneak(false);e.jump=false;}},"katana-test");
        Gale.BUS.listen(FrameEvent.class,Priority.MONITOR,e->{
            if(shot==null)return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir,shot+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
            DevTest.line("screenshot screenshots/"+shot+".png");shot=null;
        },"katana-test");
        Gale.BUS.listen(Render2DEvent.class,Priority.MONITOR,e->{if(GL11.glGetError()!=0)glClean=false;},"katana-test");
        phase("original inventory icons",25,()->{
            for(gale.module.Module m:Gale.modules().all())if(!m.isAlwaysOn())m.setEnabled(false);
            swords().skin.set("Katana");
            fixed=true;mouse=mc.mouseHelper;
            mc.mouseHelper=new net.minecraft.util.MouseHelper(){
                @Override public void mouseXYChange(){mouse.mouseXYChange();deltaX=deltaY=0;}
                @Override public void grabMouseCursor(){mouse.grabMouseCursor();}
                @Override public void ungrabMouseCursor(){mouse.ungrabMouseCursor();}
            };
            mc.gameSettings.thirdPersonView=0;mc.gameSettings.hideGUI=false;mc.gameSettings.fovSetting=70;
            mc.thePlayer.rotationYaw=mc.thePlayer.prevRotationYaw=0;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;
            for(int i=0;i<5;i++){mc.thePlayer.inventory.setInventorySlotContents(i,new ItemStack(SWORDS[i]));final int slot=i;DevTest.server(p->p.inventory.setInventorySlotContents(slot,new ItemStack(SWORDS[slot])));}
            mc.thePlayer.inventory.currentItem=0;mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
        },t->{if(t==18)shot="katana-00-gui-original";},()->{});
        phase("katana with unchanged inventory icons",25,()->swords().setEnabled(true),t->{if(t==18)shot="katana-01-gui-enabled";},()->{
            check("all five vanilla swords have replacement delegates",swords().replacedTypes()>=5,swords().replacedTypes());
            for(int i=0;i<5;i++)check(NAMES[i]+" GUI uses original geometry and texture",swords().guiPreserved(SWORDS[i]),"original model delegate");
            check("inventory icon pixels identical",sameIcons(),"five 16x16 icon regions at native GUI scale");
            check("GUI never invokes katana renderer",swords().draws(TransformType.GUI)==0,swords().draws(TransformType.GUI));
            mc.displayGuiScreen(null);jett().setEnabled(true);
        });
        for(int i=0;i<5;i++){
            final int index=i;
            phase("held "+NAMES[i]+" katana",24,()->{equip(new ItemStack(SWORDS[index]));before=swords().draws();},t->{if(t==16)shot="katana-0"+(index+2)+"-"+NAMES[index];},()->{
                check(NAMES[index]+" sword draws supplied mesh",swords().draws()>before+10,swords().draws()-before);
                check(NAMES[index]+" keeps original item identity",mc.thePlayer.getHeldItem().getItem()==SWORDS[index],mc.thePlayer.getHeldItem().getItem());
            });
        }
        phase("katana blocking and swing",30,()->{},t->{mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),72000);if(t==18)shot="katana-07-block";},()->mc.thePlayer.clearItemInUse());
        phase("katana enchanted",25,()->{ItemStack s=new ItemStack(Items.diamond_sword);s.addEnchantment(Enchantment.sharpness,1);equip(s);},t->{if(t==16)shot="katana-08-enchanted";},()->check("enchantment remains on original sword",mc.thePlayer.getHeldItem().hasEffect(),"custom shimmer + original GUI glint"));
        phase("Jett third person and local spin",30,()->{mc.gameSettings.thirdPersonView=2;Gale.modules().get(LocalSpin.class).setEnabled(true);},t->{if(t==17)shot="katana-09-jett-third";},()->{
            check("Jett NONE perspective uses katana",swords().draws(TransformType.NONE)>30,swords().draws(TransformType.NONE));
            Gale.modules().get(LocalSpin.class).setEnabled(false);
        });
        phase("vanilla first person",25,()->{jett().setEnabled(false);mc.gameSettings.thirdPersonView=0;},t->{if(t==17)shot="katana-10-vanilla-first";},()->check("vanilla first person uses katana",swords().draws(TransformType.FIRST_PERSON)>10,swords().draws(TransformType.FIRST_PERSON)));
        phase("vanilla third person",25,()->mc.gameSettings.thirdPersonView=2,t->{if(t==17)shot="katana-11-vanilla-third";},()->check("vanilla third person uses katana",swords().draws(TransformType.THIRD_PERSON)>10,swords().draws(TransformType.THIRD_PERSON)));
        phase("dropped sword",30,()->{
            mc.gameSettings.thirdPersonView=0;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=35;
            EntityItem item=new EntityItem(mc.theWorld,mc.thePlayer.posX,mc.thePlayer.posY+.2,mc.thePlayer.posZ+2,new ItemStack(Items.iron_sword));
            item.motionX=item.motionY=item.motionZ=0;mc.theWorld.addEntityToWorld(-98765,item);
        },t->{if(t==20)shot="katana-12-dropped";},()->check("dropped item uses katana geometry",swords().draws(TransformType.GROUND)>5,swords().draws(TransformType.GROUND)));
        phase("disabled restores original held models",20,()->{swords().setEnabled(false);before=swords().draws();mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;jett().setEnabled(true);},t->{if(t==14)shot="katana-13-disabled";},()->{
            check("disabled stops katana drawing",swords().draws()==before,swords().draws()-before);
            check("static source mesh cached",swords().triangles()==79868,swords().triangles());
            check("no OpenGL errors",glClean,"GUI, first/third person, dropped and enchantment");
            fixed=false;mc.mouseHelper=mouse;swords().setEnabled(true);jett().setEnabled(false);
        });
    }
    private static void equip(ItemStack item){mc.thePlayer.inventory.currentItem=0;mc.thePlayer.inventory.setInventorySlotContents(0,item);DevTest.server(p->p.inventory.setInventorySlotContents(0,item.copy()));}
    private static void phase(String name,int length,Runnable enter,java.util.function.IntConsumer tick,Runnable exit){DevTest.PHASES.add(new DevTest.Phase("Katana "+name,length,enter,tick,exit));}
    private static boolean sameIcons(){
        try {
            BufferedImage a=ImageIO.read(new File(mc.mcDataDir,"screenshots/katana-00-gui-original.png"));
            BufferedImage b=ImageIO.read(new File(mc.mcDataDir,"screenshots/katana-01-gui-enabled.png"));
            ScaledResolution sr=new ScaledResolution(mc);int scale=sr.getScaleFactor(),left=(sr.getScaledWidth()-176)/2,top=(sr.getScaledHeight()-166)/2;
            for(int slot=0;slot<5;slot++)for(int y=0;y<16*scale;y++)for(int x=0;x<16*scale;x++){
                int px=(left+8+slot*18)*scale+x,py=(top+142)*scale+y;
                if(a.getRGB(px,py)!=b.getRGB(px,py))return false;
            }return true;
        }catch(Exception e){DevTest.line("icon comparison: "+e);return false;}
    }
}
