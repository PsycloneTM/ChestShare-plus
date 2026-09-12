package dev.chestshare.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public class SharedContainersState extends SavedData {
    public static final String ID = "chestshare_containers";
    private final Map<BlockPos, SharedContainerEntry> blocks = new HashMap<>();
    private final Map<UUID, SharedContainerEntry> entities = new HashMap<>();
    public static SharedContainersState get(ServerLevel world) { return world.getDataStorage().computeIfAbsent(factory(), ID); }
    public SharedContainerEntry getBlock(BlockPos pos) { return blocks.get(pos); }
    public void putBlock(BlockPos pos, SharedContainerEntry entry) { blocks.put(pos.immutable(), entry); setDirty(); }
    public void removeBlock(BlockPos pos) { blocks.remove(pos); setDirty(); }
    public void forEachBlock(BiConsumer<BlockPos, SharedContainerEntry> consumer) { blocks.forEach(consumer); }
    public SharedContainerEntry getEntity(UUID id) { return entities.get(id); }
    public void putEntity(UUID id, SharedContainerEntry entry) { entities.put(id, entry); setDirty(); }
    public void removeEntity(UUID id) { entities.remove(id); setDirty(); }
    @Override public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        ListTag bs = new ListTag(); blocks.forEach((pos,e)->{CompoundTag x=new CompoundTag(); x.putLong("Pos",pos.asLong()); x.put("Entry",e.toNbt(registries)); bs.add(x);}); nbt.put("Blocks",bs);
        ListTag es = new ListTag(); entities.forEach((id,e)->{CompoundTag x=new CompoundTag(); x.putUUID("Uuid",id); x.put("Entry",e.toNbt(registries)); es.add(x);}); nbt.put("Entities",es); return nbt;
    }
    public static SharedContainersState fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        SharedContainersState s=new SharedContainersState(); ListTag bs=nbt.getList("Blocks",10); for(int i=0;i<bs.size();i++){CompoundTag x=bs.getCompound(i);s.blocks.put(BlockPos.of(x.getLong("Pos")),SharedContainerEntry.fromNbt(x.getCompound("Entry"),registries));}
        ListTag es=nbt.getList("Entities",10); for(int i=0;i<es.size();i++){CompoundTag x=es.getCompound(i);s.entities.put(x.getUUID("Uuid"),SharedContainerEntry.fromNbt(x.getCompound("Entry"),registries));} return s;
    }
    private static SavedData.Factory<SharedContainersState> factory() {
        return new SavedData.Factory<>(SharedContainersState::new, SharedContainersState::fromNbt, null);
    }
}
