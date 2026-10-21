package gale.netease.model;

/** A playlist (or a chart): its id, name, how many songs, cover; owned when the logged-in user made it. */
public final class NeteasePlaylist {
    public final long id;
    public final String name, cover, owner;
    public final int tracks;
    public final boolean owned;

    public NeteasePlaylist(long id, String name, int tracks, String cover, String owner, boolean owned) {
        this.id = id;
        this.name = name;
        this.tracks = tracks;
        this.cover = cover;
        this.owner = owner;
        this.owned = owned;
    }

    @Override
    public String toString() { return name + " (" + tracks + ")"; }
}
