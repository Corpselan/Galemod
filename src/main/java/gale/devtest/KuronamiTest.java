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

/** Native rendering checks, including dynamic bow model variants and GUI pixels. */
final class KuronamiTest {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private static final Item[] ITEMS={Items.wooden_sword,Items.stone_sword,Items.iron_sword,Items.golden_sword,Items.diamond_sword,Items.bow};
    private static final String[] GUNS={"Vandal","Sheriff","Marshal","Spectre"};
    private static final int[] TRIANGLES={13793,4049,8661,11475};
    private static String shot;
    private static boolean fixed,glClean=true;
    private static MouseHelper mouse;
    private static long before,swordBefore;
    private static SwordModel swords(){return Gale.modules().get(SwordModel.class);}
    private static BowModel bows(){return Gale.modules().get(BowModel.class);}
    private static JettModel jett(){return Gale.modules().get(JettModel.class);}
    private static void check(String n,boolean ok,Object detail){DevTest.check(n,ok,String.valueOf(detail));}
    static void phases(){
        Gale.BUS.listen(MouseEvent.class,Priority.HIGHEST,e->{if(fixed)e.cancel();},"kuronami-test");
        Gale.BUS.listen(InputEvent.class,Priority.LOWEST,e->{if(fixed){e.stop();e.setSneak(false);e.jump=false;}},"kuronami-test");
        Gale.BUS.listen(FrameEvent.class,Priority.MONITOR,e->{if(shot!=null){ScreenShotHelper.saveScreenshot(mc.mcDataDir,shot+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());DevTest.line("screenshot screenshots/"+shot+".png");shot=null;}},"kuronami-test");
        Gale.BUS.listen(Render2DEvent.class,Priority.MONITOR,e->{if(GL11.glGetError()!=0)glClean=false;},"kuronami-test");
        phase("original inventory",25,()->{
            for(gale.module.Module m:Gale.modules().all())if(!m.isAlwaysOn())m.setEnabled(false);
            bows().scope.set(false);
            fixed=true;mouse=mc.mouseHelper;
            mc.mouseHelper=new MouseHelper(){@Override public void mouseXYChange(){mouse.mouseXYChange();deltaX=deltaY=0;}@Override public void grabMouseCursor(){mouse.grabMouseCursor();}@Override public void ungrabMouseCursor(){mouse.ungrabMouseCursor();}};
            mc.gameSettings.thirdPersonView=0;mc.gameSettings.hideGUI=false;mc.gameSettings.fovSetting=70;
            mc.thePlayer.rotationYaw=mc.thePlayer.prevRotationYaw=0;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;
            for(int i=0;i<ITEMS.length;i++){final int slot=i;ItemStack stack=new ItemStack(ITEMS[i]);mc.thePlayer.inventory.setInventorySlotContents(i,stack);DevTest.server(p->p.inventory.setInventorySlotContents(slot,stack.copy()));}
            mc.thePlayer.inventory.currentItem=0;mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
        },t->{if(t==18)shot="kuronami-00-gui-original";},()->{});
        phase("custom inventory",25,()->{swords().skin.set("Kuronami");swords().setEnabled(true);bows().setEnabled(true);},t->{if(t==18)shot="kuronami-01-gui-enabled";},()->{
            for(int i=0;i<5;i++)check("sword "+i+" GUI geometry preserved",swords().guiPreserved(ITEMS[i]),ITEMS[i]);
            check("bow GUI geometry preserved",bows().guiPreserved(Items.bow),"native model delegate");
            check("inventory icon pixels identical",sameIcons(),"five swords plus bow, six native icon regions");
            check("GUI never draws custom mesh",swords().draws(TransformType.GUI)==0 && bows().draws(TransformType.GUI)==0,"original GUI perspective");
            mc.displayGuiScreen(null);jett().setEnabled(true);
        });
        for(int i=0;i<5;i++){final int index=i;phase("sword "+i,23,()->{equip(new ItemStack(ITEMS[index]));before=swords().draws();},t->{if(t==16 && index==4)shot="kuronami-02-knife-jett";},()->{
            check("sword "+index+" uses Kuronami mesh",swords().draws()>before+10 && swords().triangles()==11508,swords().triangles());
            check("sword "+index+" identity retained",mc.thePlayer.getHeldItem().getItem()==ITEMS[index],mc.thePlayer.getHeldItem());
        });}
        phase("Katana retained",23,()->{swords().skin.set("Katana");before=swords().draws();},t->{if(t==16)shot="kuronami-03-old-katana";},()->{check("Katana option retained",swords().draws()>before+10 && swords().triangles()==79868,swords().triangles());swords().skin.set("Kuronami");});
        for(int i=0;i<GUNS.length;i++){final int index=i;
            phase(GUNS[i]+" Jett first",25,()->{bows().skin.set(GUNS[index]);equip(new ItemStack(Items.bow));before=bows().draws();},t->{if(t==18)shot="kuronami-04-"+GUNS[index].toLowerCase();},()->{
                check(GUNS[index]+" supplied mesh renders",bows().draws()>before+10 && bows().triangles()==TRIANGLES[index],bows().triangles());
                check(GUNS[index]+" remains a native bow",mc.thePlayer.getHeldItem().getItem()==Items.bow && mc.thePlayer.getHeldItem().getItemUseAction()==EnumAction.BOW,mc.thePlayer.getHeldItem());
            });
            phase(GUNS[i]+" charge",25,()->before=bows().draws(),t->{mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),72000-22);if(t==18)shot="kuronami-05-"+GUNS[index].toLowerCase()+"-charge";},()->{check(GUNS[index]+" stays custom while charging",bows().draws()>before+10,bows().draws()-before);mc.thePlayer.clearItemInUse();});
        }
        phase("vanilla knife first",25,()->{jett().setEnabled(false);equip(new ItemStack(Items.diamond_sword));before=swords().draws();},t->{if(t==18)shot="kuronami-06-knife-vanilla";},()->check("native first person knife",swords().draws()>before+10,swords().draws(TransformType.FIRST_PERSON)));
        phase("vanilla gun first",25,()->{bows().skin.set("Vandal");equip(new ItemStack(Items.bow));before=bows().draws();},t->{if(t==18)shot="kuronami-07-vandal-vanilla";},()->check("native first person gun",bows().draws()>before+10,bows().draws(TransformType.FIRST_PERSON)));
        for(int i=0;i<3;i++){final int pull=i;phase("native pulling variant "+i,22,()->before=bows().pullingDraws(pull),t->{mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(),72000-(pull==0?5:pull==1?15:22));if(t==16)shot="kuronami-08-pull-"+pull;},()->{
            check("native bow pulling "+pull+" remains custom",bows().pullingDraws(pull)>before+10,bows().pullingDraws(pull)-before);mc.thePlayer.clearItemInUse();
        });}
        phase("native third person",25,()->{mc.gameSettings.thirdPersonView=2;before=bows().draws();},t->{if(t==18)shot="kuronami-09-vanilla-third";},()->check("native third person gun",bows().draws()>before+10,bows().draws(TransformType.THIRD_PERSON)));
        phase("Jett third person",25,()->{jett().setEnabled(true);before=bows().draws();},t->{if(t==18)shot="kuronami-10-jett-third";},()->check("Jett third person gun",bows().draws()>before+10,bows().draws(TransformType.NONE)));
        phase("enchanted gun",25,()->{mc.gameSettings.thirdPersonView=0;ItemStack s=new ItemStack(Items.bow);s.addEnchantment(Enchantment.power,1);equip(s);},t->{if(t==18)shot="kuronami-11-enchanted";},()->check("native bow enchantment retained",mc.thePlayer.getHeldItem().hasEffect(),mc.thePlayer.getHeldItem()));
        phase("dropped weapons",25,()->{
            jett().setEnabled(false);mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=35;
            for(int i=0;i<2;i++){EntityItem e=new EntityItem(mc.theWorld,mc.thePlayer.posX+(i==0?-.35:.35),mc.thePlayer.posY+.3,mc.thePlayer.posZ+2,new ItemStack(i==0?Items.bow:Items.iron_sword));e.motionX=e.motionY=e.motionZ=0;mc.theWorld.addEntityToWorld(-98760-i,e);}
        },t->{if(t==18)shot="kuronami-12-dropped";},()->{check("dropped bow uses custom mesh",bows().draws(TransformType.GROUND)>5,bows().draws(TransformType.GROUND));check("dropped knife uses custom mesh",swords().draws(TransformType.GROUND)>5,swords().draws(TransformType.GROUND));mc.theWorld.removeEntityFromWorld(-98760);mc.theWorld.removeEntityFromWorld(-98761);});
        phase("switch and spin",28,()->{jett().setEnabled(true);mc.gameSettings.thirdPersonView=2;mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=0;Gale.modules().get(LocalSpin.class).setEnabled(true);},t->{if(t%4==0)equip(new ItemStack(t%8==0?Items.bow:Items.diamond_sword));},()->{
            check("shared native renderer has exactly one dispatcher",WeaponModel.dispatcherLayers()==1,WeaponModel.dispatcherLayers());
            check("four gun models cached once",bows().cachedModels()==4,bows().cachedModels());check("both sword skins cached once",swords().cachedModels()==2,swords().cachedModels());
            Gale.modules().get(LocalSpin.class).setEnabled(false);
        });
        phase("disabled fallback",20,()->{bows().setEnabled(false);swords().setEnabled(false);equip(new ItemStack(Items.bow));mc.gameSettings.thirdPersonView=0;before=bows().draws();swordBefore=swords().draws();},t->{if(t==14)shot="kuronami-13-disabled";},()->{
            check("disabled restores native rendering",bows().draws()==before && swords().draws()==swordBefore,"no custom submissions");check("no OpenGL errors",glClean,"GUI, all skins, charge stages, first/third, dropped, enchantment and switching");
            fixed=false;mc.mouseHelper=mouse;swords().setEnabled(true);bows().setEnabled(true);swords().skin.set("Kuronami");bows().skin.set("Vandal");bows().scope.set(true);jett().setEnabled(false);
        });
    }
    private static void equip(ItemStack s){mc.thePlayer.clearItemInUse();mc.thePlayer.inventory.currentItem=0;mc.thePlayer.inventory.setInventorySlotContents(0,s);DevTest.server(p->p.inventory.setInventorySlotContents(0,s.copy()));}
    private static void phase(String n,int ticks,Runnable enter,java.util.function.IntConsumer tick,Runnable exit){DevTest.PHASES.add(new DevTest.Phase("Kuronami "+n,ticks,enter,tick,exit));}
    private static boolean sameIcons(){try{
        BufferedImage a=ImageIO.read(new File(mc.mcDataDir,"screenshots/kuronami-00-gui-original.png")),b=ImageIO.read(new File(mc.mcDataDir,"screenshots/kuronami-01-gui-enabled.png"));
        ScaledResolution sr=new ScaledResolution(mc);int scale=sr.getScaleFactor(),left=(sr.getScaledWidth()-176)/2,top=(sr.getScaledHeight()-166)/2;
        for(int slot=0;slot<6;slot++)for(int y=0;y<16*scale;y++)for(int x=0;x<16*scale;x++){int px=(left+8+slot*18)*scale+x,py=(top+142)*scale+y;if(a.getRGB(px,py)!=b.getRGB(px,py))return false;}return true;
    }catch(Exception e){DevTest.line("icon comparison: "+e);return false;}}
}
