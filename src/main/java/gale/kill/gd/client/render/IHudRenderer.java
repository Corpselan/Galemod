/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.client.render;

import gale.kill.gd.compat.GuiGraphics;

/**
 * 客户端 HUD 渲染器接口。
 * 定义了统一的渲染与触发规范。
 */
public interface IHudRenderer {
    /**
     * 渲染逻辑实现。
     *
     * @param guiGraphics 渲染画布
     * @param partialTick 渲染间隔偏移
     */
    void render(GuiGraphics guiGraphics, float partialTick);

    /**
     * 触发 HUD 元素的显示。
     * 所有的名称解析、本地化逻辑应在实现类中通过 ID 自行处理。
     *
     * @param context 触发上下文，包含触发所需的所有原始数据
     */
    void trigger(TriggerContext context);

    /**
     * 清除该渲染器的全部显示状态(预览/触发残留)。
     * 关闭配置界面时由 HudElementManager.clearAllPreviews() 统一调用,
     * 不采用各元素的单独消除逻辑。
     */
    default void resetPreview() {
    }

    /**
     * 触发上下文，用于封装触发事件所需的原始数据。
     */ static final class TriggerContext {
private final int type;
private final int entityId;
private final int comboCount;
private final String extraData;
private final float distance;
private final float bonusMultiplier;
private final float bonusScale;
public TriggerContext(int type, int entityId, int comboCount, String extraData, float distance, float bonusMultiplier, float bonusScale) {this.type = type; this.entityId = entityId; this.comboCount = comboCount; this.extraData = extraData; this.distance = distance; this.bonusMultiplier = bonusMultiplier; this.bonusScale = bonusScale;}
public int type() { return type; }
public int entityId() { return entityId; }
public int comboCount() { return comboCount; }
public String extraData() { return extraData; }
public float distance() { return distance; }
public float bonusMultiplier() { return bonusMultiplier; }
public float bonusScale() { return bonusScale; }

        public static TriggerContext of(int type, int entityId) {
            return new TriggerContext(type, entityId, 0, "", 0.0f, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, int entityId, int comboCount) {
            return new TriggerContext(type, entityId, comboCount, "", 0.0f, 0.0f, 0.0f);
        }
        
        public static TriggerContext of(int type, int entityId, int comboCount, String extraData) {
            return new TriggerContext(type, entityId, comboCount, extraData, 0.0f, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, int entityId, int comboCount, String extraData, float distance) {
            return new TriggerContext(type, entityId, comboCount, extraData, distance, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, String extraData) {
            return new TriggerContext(type, -1, 0, extraData, 0.0f, 0.0f, 0.0f);
        }
    }
}
