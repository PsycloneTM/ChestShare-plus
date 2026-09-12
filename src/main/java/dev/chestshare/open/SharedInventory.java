package dev.chestshare.open;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Consumer;
import java.util.function.Predicate;

public class SharedInventory extends SimpleContainer {
    private final Consumer<SharedInventory> saver;
    private final Predicate<Player> canUse;
    private boolean seeding = true;
    public SharedInventory(int size, Consumer<SharedInventory> saver, Predicate<Player> canUse) { super(size); this.saver=saver; this.canUse=canUse; }
    @Override public void setChanged() { super.setChanged(); if (!seeding) saver.accept(this); }
    public void finishSeeding() { seeding=false; }
    @Override public boolean stillValid(Player player) { return canUse.test(player); }
}
