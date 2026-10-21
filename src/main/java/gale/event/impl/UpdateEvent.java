package gale.event.impl;

import gale.event.Event;

/**
 * Every tick in a world, just before you move: the place to decide what to do this tick (like B4's onPreUpdate).
 * Keys and clicks of this tick have already been handled. Rotations asked for here with
 * {@code RotationManager.request} are used for this tick's movement packet. Game thread.
 */
public final class UpdateEvent extends Event {
}
