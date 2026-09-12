package dev.chestshare.open;

import dev.chestshare.ChestShare;
import dev.chestshare.ContainerRegistrar;
import dev.chestshare.SharedMarker;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import dev.chestshare.compat.ContainerCompatibility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SharedContainerOpener {
    private static final double MAX_USE_DISTANCE_SQ=64.0;
    private SharedContainerOpener() {}

    public static MenuProvider replaceFactory(ServerPlayer player, MenuProvider factory) {
        if (factory instanceof BlockEntity be && be.getLevel() instanceof ServerLevel world) {
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                SharedContainerEntry entry=resolveBlockEntry(world,randomizable);
                if(entry!=null) return blockContainerFactory(world,randomizable.getBlockPos(),entry,factory);
            } else if (!be.getClass().getName().startsWith("net.minecraft.")) {
                if (factory instanceof Container container) {
                    SharedContainerEntry entry=resolveGenericEntry(world,be,container);
                    if(entry!=null) return blockContainerFactory(world,be.getBlockPos(),entry,factory);
                } else {
                    // Must use resolveCompatEntry, not ContainerCompatibility.register()
                    // directly - see NOTES.md, this was a real "storage becomes unopenable" bug.
                    SharedContainerEntry entry=resolveCompatEntry(world,be);
                    if(entry!=null) return blockContainerFactory(world,be.getBlockPos(),entry,factory);
                }
            }
        }
        if (factory instanceof MinecartChest cart && cart.level() instanceof ServerLevel world) {
            SharedContainerEntry entry=resolveEntityEntry(world,cart);
            if(entry!=null) return entityContainerFactory(world,cart,entry,cart);
        }
        return factory;
    }


    public static SharedContainerEntry resolveGenericEntryForUse(ServerLevel world, BlockEntity be, Container container) {
        return resolveGenericEntry(world, be, container);
    }

    /** Opens the shared menu for a reflective-compat container directly from the
     *  block-use event. See NOTES.md. Returns true if the shared menu was opened
     *  (caller should cancel the block-use event); false if there was nothing to share. */
    public static boolean openCompatContainerForUse(ServerPlayer player, ServerLevel world, BlockEntity be) {
        SharedContainerEntry entry = resolveCompatEntry(world, be);
        if (entry == null) return false;
        MenuProvider title = be instanceof MenuProvider mp ? mp
                : provider(net.minecraft.network.chat.Component.translatable("container.chest"), (sync, inv, p) -> null);
        player.openMenu(blockContainerFactory(world, be.getBlockPos(), entry, title));
        return true;
    }

    /** Idempotent resolve for the reflective-compat path - see NOTES.md. */
    private static SharedContainerEntry resolveCompatEntry(ServerLevel world, BlockEntity be) {
        SharedContainersState state = SharedContainersState.get(world);
        BlockPos pos = be.getBlockPos();
        SharedContainerEntry entry = state.getBlock(pos);
        boolean marked = be instanceof SharedMarker m && m.chestshare$isShared();
        if (entry != null && !marked) { state.removeBlock(pos); return null; }
        if (entry != null) return entry;
        return ContainerCompatibility.register(world, be, true);
    }

    private static SharedContainerEntry resolveGenericEntry(ServerLevel world, BlockEntity be, Container container) {
        SharedContainersState state=SharedContainersState.get(world); BlockPos pos=be.getBlockPos();
        SharedContainerEntry entry=state.getBlock(pos); boolean marked=be instanceof SharedMarker m && m.chestshare$isShared();
        if(entry!=null&&!marked){state.removeBlock(pos);return null;}
        if(entry==null){
            entry=new SharedContainerEntry(new ContainerTemplate.ItemListTemplate(ContainerRegistrar.copyContents(container),container.getContainerSize()));
            state.putBlock(pos,entry);
            for(int i=0;i<container.getContainerSize();i++)container.setItem(i,ItemStack.EMPTY);
            if(be instanceof SharedMarker m)m.chestshare$setShared(true);
            be.setChanged();
            ChestShare.LOGGER.debug("Registered modded shared container {} at {}",be.getClass().getName(),pos);
        }
        return entry;
    }

    public static SharedContainerEntry resolveBlockEntry(ServerLevel world, RandomizableContainerBlockEntity container) {
        SharedContainersState state=SharedContainersState.get(world); BlockPos pos=container.getBlockPos();
        SharedContainerEntry entry=state.getBlock(pos); boolean marked=((SharedMarker)container).chestshare$isShared();
        if(entry!=null&&!marked){state.removeBlock(pos);return null;}
        if(entry==null&&marked)return ContainerRegistrar.registerForced(world,container);
        if(entry==null&&container.getLootTable()!=null)return ContainerRegistrar.register(world,container);
        return entry;
    }
    public static SharedContainerEntry resolveEntityEntry(ServerLevel world, MinecartChest cart) {
        SharedContainersState state=SharedContainersState.get(world); UUID id=cart.getUUID(); SharedContainerEntry entry=state.getEntity(id);
        if(entry==null&&cart.getLootTable()!=null){
            entry=new SharedContainerEntry(new ContainerTemplate.LootTableTemplate(cart.getLootTable().location(),cart.getLootTableSeed(),cart.getContainerSize()));
            cart.setLootTable(null); cart.setLootTableSeed(0L); state.putEntity(id,entry);
            ChestShare.LOGGER.debug("Registered shared chest minecart {}",id);
        }
        return entry;
    }
    public static MenuProvider doubleChestFactory(ServerLevel world, BlockPos primaryPos, BlockPos secondaryPos, SharedContainerEntry primary, SharedContainerEntry secondary) {
        return provider(net.minecraft.network.chat.Component.translatable("container.chestDouble"), (sync,inv,p)->{
            ServerPlayer player=(ServerPlayer)p;
            List<ItemStack> first=getOrCreateInstance(world,player,primary,Vec3.atCenterOf(primaryPos),fallbackSeedFor(world,primaryPos),27);
            List<ItemStack> second=getOrCreateInstance(world,player,secondary,Vec3.atCenterOf(secondaryPos),fallbackSeedFor(world,secondaryPos),27);
            SharedInventory si=new SharedInventory(54,x->{primary.putInstance(player.getUUID(),copyRange(x,0,27));secondary.putInstance(player.getUUID(),copyRange(x,27,54));SharedContainersState.get(world).setDirty();},viewer->stateValidBlock(world,primaryPos,primary,viewer)&&stateValidBlock(world,secondaryPos,secondary,viewer));
            for(int i=0;i<27;i++){si.setItem(i,first.get(i).copy());si.setItem(27+i,second.get(i).copy());} si.finishSeeding();
            return new ChestMenu(MenuType.GENERIC_9x6,sync,inv,si,6);
        });
    }
    private static List<ItemStack> copyRange(SharedInventory inv,int from,int to){List<ItemStack> out=new ArrayList<>(to-from);for(int i=from;i<to;i++)out.add(inv.getItem(i).copy());return out;}

    public static MenuProvider blockContainerFactory(ServerLevel world, BlockPos pos, SharedContainerEntry entry, MenuProvider title) {
        int rows=Math.max(1,Math.min(6,(entry.template().size()+8)/9)); int size=rows*9;
        return provider(title.getDisplayName(), (sync,inv,p)->menu(world,pos,entry,title.getDisplayName(),sync,inv,p,size));
    }
    public static MenuProvider entityContainerFactory(ServerLevel world, MinecartChest cart, SharedContainerEntry entry, MenuProvider title) {
        int rows=Math.max(1,Math.min(6,(entry.template().size()+8)/9)); int size=rows*9;
        return provider(title.getDisplayName(), (sync,inv,p)->{
            long seed=world.getGameTime()^cart.getUUID().getMostSignificantBits()^cart.getUUID().getLeastSignificantBits();
            List<ItemStack> initial=getOrCreateInstance(world,(ServerPlayer)p,entry,cart.position(),seed,size);
            SharedInventory si=new SharedInventory(size,x->save(entry,p,x,world),viewer->stateValidEntity(world,cart,entry,viewer)); fill(si,initial); si.finishSeeding();
            return new ChestMenu(screenType(rows),sync,inv,si,rows);
        });
    }
    private static AbstractContainerMenu menu(ServerLevel world,BlockPos pos,SharedContainerEntry entry,net.minecraft.network.chat.Component title,int sync,Inventory inv,Player p,int size){
        int rows=size/9; List<ItemStack> initial=getOrCreateInstance(world,(ServerPlayer)p,entry,Vec3.atCenterOf(pos),fallbackSeedFor(world,pos),size);
        SharedInventory si=new SharedInventory(size,x->save(entry,p,x,world),viewer->stateValidBlock(world,pos,entry,viewer)); fill(si,initial); si.finishSeeding();
        return new ChestMenu(screenType(rows),sync,inv,si,rows);
    }
    private static MenuProvider provider(net.minecraft.network.chat.Component title, net.minecraft.world.inventory.MenuConstructor constructor){ return new MenuProvider(){public net.minecraft.network.chat.Component getDisplayName(){return title;} public AbstractContainerMenu createMenu(int id,Inventory inv,Player p){return constructor.createMenu(id,inv,p);}}; }
    private static void fill(SharedInventory inv,List<ItemStack> items){for(int i=0;i<inv.getContainerSize();i++)inv.setItem(i,i<items.size()?items.get(i).copy():ItemStack.EMPTY);}
    private static void save(SharedContainerEntry entry,Player p,SharedInventory inv,ServerLevel world){entry.putInstance(p.getUUID(),ContainerRegistrar.copyContents(inv));SharedContainersState.get(world).setDirty();}
    private static boolean stateValidBlock(ServerLevel world,BlockPos pos,SharedContainerEntry e,Player p){return SharedContainersState.get(world).getBlock(pos)==e&&p.distanceToSqr(Vec3.atCenterOf(pos))<=MAX_USE_DISTANCE_SQ;}
    private static boolean stateValidEntity(ServerLevel world,MinecartChest cart,SharedContainerEntry e,Player p){return SharedContainersState.get(world).getEntity(cart.getUUID())==e&&!cart.isRemoved()&&p.distanceToSqr(cart.position())<=MAX_USE_DISTANCE_SQ;}
    public static List<ItemStack> getOrCreateInstance(ServerLevel world,ServerPlayer player,SharedContainerEntry entry,Vec3 origin,long fallbackSeed,int size){List<ItemStack> stored=entry.getInstance(player.getUUID()); if(stored!=null){List<ItemStack> out=new ArrayList<>();for(int i=0;i<size;i++)out.add(i<stored.size()?stored.get(i).copy():ItemStack.EMPTY);return out;} List<ItemStack> created=entry.template().createStacks(world,origin,player,fallbackSeed,size,world.registryAccess());entry.putInstance(player.getUUID(),created);SharedContainersState.get(world).setDirty();return created;}
    public static int listSize(SharedContainerEntry e){return Math.max(9,Math.min(54,((e.template().size()+8)/9)*9));}
    public static long fallbackSeedFor(ServerLevel world,BlockPos pos){return world.getSeed()^pos.asLong();}
    private static MenuType<ChestMenu> screenType(int rows){return switch(rows){case 1->MenuType.GENERIC_9x1;case 2->MenuType.GENERIC_9x2;case 3->MenuType.GENERIC_9x3;case 4->MenuType.GENERIC_9x4;case 5->MenuType.GENERIC_9x5;default->MenuType.GENERIC_9x6;};}
}
