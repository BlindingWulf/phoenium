package dev.phoenium.service;

/** S9-D16: PlayerMap's key-set change count (PlayerMapRevisionMixin). Read and written on the server thread. */
public interface PlayerMapRevision {
    int phoenium$revision();
}
