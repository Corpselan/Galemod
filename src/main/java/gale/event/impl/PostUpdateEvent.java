package gale.event.impl;

import gale.event.Event;

/**
 * Every tick in a world, after you moved and the movement packet (with this tick's rotation) was sent, before the
 * camera gets its own rotation back: {@code player.rotationYaw} is still the server rotation here. The place to hit or
 * place after rotating in UpdateEvent, so the server gets the rotation first. Game thread.
 */
public final class PostUpdateEvent extends Event {
}
