package gale.b4port.module;

/** B4's categories, and where each one goes in Gale. */
public enum Category {
    combat(gale.module.Category.COMBAT),
    movement(gale.module.Category.MOVEMENT),
    player(gale.module.Category.PLAYER),
    world(gale.module.Category.WORLD),
    render(gale.module.Category.RENDER),
    network(gale.module.Category.COMBAT),
    minigames(gale.module.Category.MINIGAMES),
    other(gale.module.Category.MINIGAMES),
    client(gale.module.Category.CLIENT);

    public final gale.module.Category galeCategory;

    Category(gale.module.Category galeCategory) {
        this.galeCategory = galeCategory;
    }
}
