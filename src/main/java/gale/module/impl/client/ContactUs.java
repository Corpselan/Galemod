package gale.module.impl.client;

import gale.core.Notifications;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.util.ContactLinks;

import java.util.concurrent.atomic.AtomicBoolean;

/** Momentary actions, available in both Gale and Raven's settings panels. */
public final class ContactUs extends Module {
    private final BoolSetting qq = bool("QQ", false).describe("点击加入 QQ 群；无法打开时自动复制邀请链接");
    private final BoolSetting discord = bool("Discord", false).describe("点击加入 Discord；无法打开时自动复制邀请链接");
    private final AtomicBoolean opening = new AtomicBoolean();

    public ContactUs() {
        super("联系我们", Category.CLIENT, "展开后选择 QQ 或 Discord，点击即可加入");
        alwaysOn();
        qq.onChange(on -> { if (on) { qq.set(false); open("QQ", ContactLinks.QQ); } });
        discord.onChange(on -> { if (on) { discord.set(false); open("Discord", ContactLinks.DISCORD); } });
    }

    private void open(String name, String url) {
        if (!opening.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                ContactLinks.Result result = ContactLinks.open(url);
                mc.addScheduledTask(() -> {
                    if (result == ContactLinks.Result.OPENED) Notifications.success("已打开 " + name + " 邀请链接");
                    else if (result == ContactLinks.Result.COPIED) Notifications.warn("无法打开 " + name + "，邀请链接已复制到剪贴板");
                    else Notifications.error("无法打开或复制 " + name + " 邀请链接，请重试");
                });
            } finally {
                opening.set(false);
            }
        }, "Gale-contact");
        worker.setDaemon(true);
        worker.start();
    }
}
