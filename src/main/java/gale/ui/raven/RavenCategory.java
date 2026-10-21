package gale.ui.raven;

import gale.Gale;
import gale.b4port.util.Animation;
import gale.b4port.util.RenderUtils;
import gale.module.Category;
import gale.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Locale;

/** B4's CategoryComponent: a draggable frame of one category's modules. */
public class RavenCategory {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final Category category;
    public final String title;
    public final ItemStack icon, activeIcon;
    public float iconScale = 0.5f;
    public float titleHeight;
    public float x;
    public boolean dragging;
    public float width;
    public float iconInverse = 1.0f / this.iconScale;
    public boolean closing;
    public float yOffset;
    public ArrayList<RavenComponent> modules = new ArrayList<>();
    public boolean opened;
    public float moduleY;
    public float targetScroll;
    public Animation scrollAnimation;
    public float scroll;
    public float xOffset;
    public float bigSettingsHeight;
    public float maxHeightRatio = 0.8f;
    public float y;
    public boolean opening;
    public Animation openAnimation;
    public long animationDuration = 400L;
    public float dragY;

    public RavenCategory(Category category) {
        this.category = category;
        this.title = category.name().toLowerCase(Locale.ROOT);
        this.activeIcon = activeIcon(category);
        this.icon = icon(category);
        this.width = 92.0f;
        this.setX(5.0f);
        this.titleHeight = 13.0f;
        this.moduleY = this.titleHeight + 3.0f;
        this.xOffset = 0.0f;
        for (Module mod : Gale.modules().visibleIn(category)) this.addModule(mod);
    }

    // B4's icons; Gale's own categories get the icons of B4's nearest ones
    private static ItemStack icon(Category c) {
        switch (c) {
            case COMBAT: return new ItemStack(Items.diamond_sword);
            case MOVEMENT: return new ItemStack(Items.diamond_boots);
            case PLAYER: return new ItemStack(Items.golden_apple, 1, 0);
            case WORLD: return new ItemStack((Item) Items.filled_map);
            case RENDER: return new ItemStack(Items.ender_eye);
            case MINIGAMES: return new ItemStack(Items.gold_ingot);
            case OPTIMIZE: return new ItemStack(Items.clock);
            case ASSIST: return new ItemStack(Items.comparator);
            case SCRIPTS: return new ItemStack(Items.redstone);
            case EXTENSIONS: return new ItemStack(Items.nether_star);
            case CLIENT: return new ItemStack(Items.compass);
            default: return new ItemStack(Blocks.command_block);
        }
    }

    private static ItemStack activeIcon(Category c) {
        return c == Category.PLAYER ? new ItemStack(Items.golden_apple, 1, 1) : icon(c);
    }

    public void addModule(Module mod) {
        if (mod.isHide()) return;
        RavenModule c = new RavenModule(mod, this, this.moduleY);
        this.modules.add(c);
        this.moduleY += c.height;
        this.updateHeight();
    }

    public void setOpened(boolean on) {
        if (on == this.opened) return;
        this.opened = on;
        if (on) {
            this.opening = true;
            this.closing = false;
        } else {
            this.opening = false;
            this.closing = true;
        }
        if (this.openAnimation == null) this.openAnimation = new Animation(this.animationDuration);
        this.openAnimation.start();
    }

    public void mouseReleased() {
        this.overTitle(false);
        for (RavenComponent c : this.modules) c.onGuiClosed();
    }

    public float getY() { return this.y; }

    public void render(FontRenderer renderer, int mouseX, int mouseY) {
        float animated;
        float target;
        this.width = 92.0f;
        boolean active = false;
        if ((this.opened || this.closing) && !this.modules.isEmpty()) {
            animated = 0.0f;
            float y = target = 2.0f;
            for (RavenComponent c : this.modules) target += c.getHeight();
            this.bigSettingsHeight = target;
            target = Math.min(target, (float) RenderUtils.scaledHeight * this.maxHeightRatio);
            if (this.openAnimation != null) {
                float v;
                if (this.opening) {
                    v = this.openAnimation.getValue(animated, target, 2);
                    if (v == target) this.opening = false;
                    animated = v;
                } else if (this.closing) {
                    v = target - this.openAnimation.getValue(animated, target, 2);
                    if (v == animated) this.closing = false;
                    animated = v;
                } else {
                    animated = target;
                }
            }
            float top = this.y + this.titleHeight + 3.0f;
            int bottom = (int) ((double) top + Math.floor(animated));
            RenderUtils.roundedRectOutlined(this.x - 2.0f, this.y, this.x + this.width + 2.0f, top + animated, 10.0f,
                RavenStyle.categoryFill(), RavenStyle.outlineLeft(), RavenStyle.outlineRight());
            GL11.glPushMatrix();
            GL11.glEnable(3089);
            GL11.glScissor((int) ((this.x - 1.0f) * (float) RenderUtils.scaleFactor), mc.displayHeight - bottom * RenderUtils.scaleFactor,
                (int) ((this.width + 2.0f) * (float) RenderUtils.scaleFactor), (int) (((float) bottom - top) * (float) RenderUtils.scaleFactor));
            for (RavenComponent c : this.modules) {
                RavenModule m = (RavenModule) c;
                m.render(mouseX, mouseY, y + m.height);
                y += m.getHeight();
            }
            GL11.glDisable(3089);
            GL11.glPopMatrix();
            float max = Math.min(this.bigSettingsHeight, (float) RenderUtils.scaledHeight * this.maxHeightRatio);
            this.targetScroll = Math.max(max - this.bigSettingsHeight, Math.min(0.0f, this.targetScroll));
            if (this.scrollAnimation != null && this.scroll != this.targetScroll) {
                this.scroll = this.scroll > this.targetScroll
                    ? (this.scroll -= this.scrollAnimation.getValue(0.0f, this.scroll - this.targetScroll, 0))
                    : (this.targetScroll == 0.0f
                    ? (this.scroll += this.scrollAnimation.getValue(this.targetScroll, -this.scroll, 0))
                    : this.scrollAnimation.getValue(this.scroll, this.targetScroll, 0));
            }
            this.updateHeight();
            active = true;
        } else if (this.overTitle(mouseX, mouseY)) {
            RenderUtils.roundedRectOutlined(this.x - 2.0f, this.y, this.x + this.width + 2.0f, this.y + this.titleHeight + 3.0f, 10.0f,
                RavenStyle.categoryFill(), RavenStyle.outlineLeft(), RavenStyle.outlineRight());
            active = true;
        } else {
            RenderUtils.roundedRectOutlined(this.x - 2.0f, this.y, this.x + this.width + 2.0f, this.y + this.titleHeight + 3.0f, 10.0f,
                RavenStyle.categoryFill(), RavenStyle.closedOutlineLeft, RavenStyle.closedOutlineRight);
        }
        GL11.glPushMatrix();
        float nameX = 13.5f;
        if (this.openAnimation != null) {
            float center = this.getWidth() / 2.0f - (float) mc.fontRendererObj.getStringWidth(this.title) / 2.0f;
            if (this.opening) nameX = this.openAnimation.getValue(nameX, center, 2);
            else if (this.closing) nameX = nameX + center - this.openAnimation.getValue(nameX, center, 2);
            else if (this.opened) nameX = center;
        }
        renderer.drawString(this.title, this.x + nameX, this.y + 4.0f, RavenStyle.categoryNameColor.rgb, false);
        RenderUtils.resetColor();
        GlStateManager.scale(this.iconScale, this.iconScale, this.iconScale);
        RenderUtils.drawItem(active ? this.activeIcon : this.icon, (this.x + 2.0f) * this.iconInverse, (this.y + 4.0f) * this.iconInverse);
        GL11.glPopMatrix();
    }

    public boolean overCategoryIcon(int x, int y) {
        return (float) x >= this.x && (float) x <= this.x + 11.0f && (float) y >= this.y + 2.0f && (float) y <= this.y + this.titleHeight + 1.0f;
    }

    public float getX() { return this.x; }

    public void overTitle(boolean d) { this.dragging = d; }

    public void updateHeight() {
        float y = this.titleHeight + this.scroll + 3.0f;
        this.yOffset = 2.0f;
        for (RavenComponent c : this.modules) {
            c.setOffset(y);
            y += c.getHeight();
            this.yOffset += c.getHeight();
        }
    }

    public void onScroll(int mouseX, int mouseY, int dir) {
        if (!this.isHovered(mouseX, mouseY)) return;
        if (this.scrollAnimation == null) this.scrollAnimation = new Animation(1000.0f);
        this.scrollAnimation.start();
        this.targetScroll = (float) ((double) this.targetScroll + (double) dir * RavenStyle.settings().scrollSpeed.get());
    }

    public void mousePosition(int mouseX, int mouseY) {
        if (this.dragging) {
            this.setX((float) mouseX - this.xOffset);
            this.setY((float) mouseY - this.dragY);
        }
    }

    public void setY(float y) { this.y = y; }

    public float getWidth() { return this.width; }

    public boolean isHovered(int x, int y) {
        return (float) x >= this.x && (float) x <= this.x + this.width && (float) y >= this.y + this.titleHeight && (float) y <= this.y + this.titleHeight + this.yOffset;
    }

    public void setX(float x) { this.x = x; }

    public boolean overTitle(int x, int y) {
        return (float) x >= this.x && (float) x <= this.x + this.width && (float) y >= this.y && (float) y <= this.y + this.titleHeight;
    }
}
