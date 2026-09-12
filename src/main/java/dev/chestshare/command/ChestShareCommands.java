package dev.chestshare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.chestshare.ContainerRegistrar;
import dev.chestshare.SharedMarker;
import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.state.ContainerTemplate;
import dev.chestshare.state.SharedContainerEntry;
import dev.chestshare.state.SharedContainersState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class ChestShareCommands {
    private ChestShareCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("chestshare").requires(s->s.hasPermission(2))
                .then(literal("convert").then(argument("pos", BlockPosArgument.blockPos()).then(argument("loot_table", ResourceLocationArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                context.getSource().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE),
                                builder))
                        .executes(c->convert(c.getSource(),BlockPosArgument.getLoadedBlockPos(c,"pos"),ResourceLocationArgument.getId(c,"loot_table"))))))
                .then(literal("reset").then(argument("pos",BlockPosArgument.blockPos()).executes(c->reset(c.getSource(),BlockPosArgument.getLoadedBlockPos(c,"pos")))))
                .then(literal("export").then(argument("file",StringArgumentType.word()).executes(c->exportContainers(c.getSource(),StringArgumentType.getString(c,"file")))))
                .then(literal("import")
                        .then(literal("cancel").requires(s->ImportJob.ACTIVE!=null).executes(c->cancelImport(c.getSource())))
                        .then(literal("status").requires(s->ImportJob.ACTIVE!=null).executes(c->importStatus(c.getSource())))
                        .then(argument("file",StringArgumentType.word()).requires(s->ImportJob.ACTIVE==null)
                                .executes(c->importContainers(c.getSource(),StringArgumentType.getString(c,"file"),false,1))
                                .then(argument("parallel",IntegerArgumentType.integer(1,8))
                                        .executes(c->importContainers(c.getSource(),StringArgumentType.getString(c,"file"),false,IntegerArgumentType.getInteger(c,"parallel"))))
                                .then(literal("force")
                                        .executes(c->importContainers(c.getSource(),StringArgumentType.getString(c,"file"),true,1))
                                        .then(argument("parallel",IntegerArgumentType.integer(1,8))
                                                .executes(c->importContainers(c.getSource(),StringArgumentType.getString(c,"file"),true,IntegerArgumentType.getInteger(c,"parallel")))))))
        );
    }
    // Never use BlockPos.toString() for chat/log output - see NOTES.md
    private static String coordString(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
    // Clients cache the command tree from login (requires() is evaluated once, then baked
    // into what that client sees in tab-complete) - see NOTES.md. Must be called any time
    // ImportJob.ACTIVE changes, or cancel/status will never appear to already-connected players.
    public static void refreshCommandTrees(MinecraftServer server) {
        if (server == null) return;
        for (var player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }
    private static int convert(CommandSourceStack source, BlockPos pos, ResourceLocation id) {
        ServerLevel world=source.getLevel(); BlockEntity be=world.getBlockEntity(pos);
        var lootTables = world.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE);
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> lootTableKey=ResourceKey.create(Registries.LOOT_TABLE,id);
        if(lootTables.get(lootTableKey).isEmpty()){
            source.sendFailure(Component.literal("No loot table registered with ID "+id+" (check spelling / that the providing mod or datapack is loaded)."));
            return 0;
        }
        if(be instanceof RandomizableContainerBlockEntity c){
            // Seed MUST be 0L here - see NOTES.md (re-convert determinism bug)
            c.setLootTable(lootTableKey); c.setLootTableSeed(0L);
            SharedContainerEntry entry=ContainerRegistrar.register(world,c);
            if(entry==null){source.sendFailure(Component.literal("The container has no loot table or items to share."));return 0;}
            source.sendSuccess(()->Component.literal("Converted container at "+coordString(pos)+" to shared loot "+id+" (previous contents discarded)"),true); return 1;
        }
        if(be!=null && ContainerCompatibility.isSupportedContainer(be)){
            ContainerCompatibility.CompatInventory inv=ContainerCompatibility.getContainerInventory(be);
            // Seed MUST be 0L here too - see NOTES.md
            ContainerTemplate template=new ContainerTemplate.LootTableTemplate(id, 0L, inv.size());
            for(int s=0;s<inv.size();s++){inv.set(s, ItemStack.EMPTY);}
            if(be instanceof SharedMarker marker) marker.chestshare$setShared(true);
            be.setChanged();
            SharedContainersState.get(world).putBlock(pos, new SharedContainerEntry(template));
            source.sendSuccess(()->Component.literal("Converted container at "+coordString(pos)+" to shared loot "+id+" (previous contents discarded)"),true); return 1;
        }
        source.sendFailure(Component.literal("No lootable container at "+coordString(pos))); return 0;
    }
    private static int reset(CommandSourceStack source, BlockPos pos) {
        ServerLevel world=source.getLevel();
        SharedContainersState state=SharedContainersState.get(world);
        SharedContainerEntry entry=state.getBlock(pos);
        if(entry==null){
            source.sendFailure(Component.literal("No shared container registered at "+coordString(pos)));
            return 0;
        }
        // reset() semantics - see NOTES.md
        entry.clearInstances();
        state.setDirty();
        source.sendSuccess(()->Component.literal("Reset all player instances of the shared container at "+coordString(pos)),true);
        return 1;
    }
    private static Path resolveDataFile(MinecraftServer server,String file){if(file.contains("/")||file.contains("\\")||file.contains(".."))return null;return server.getServerDirectory().resolve(file+".nbt");}
    private static int exportContainers(CommandSourceStack source,String fileName){
        Path path=resolveDataFile(source.getServer(),fileName); if(path==null){source.sendFailure(Component.literal("Invalid file name (plain name only, no path)"));return 0;}
        CompoundTag root=new CompoundTag(); root.putInt("Version",1); ListTag dims=new ListTag(); int count=0;
        for(ServerLevel world:source.getServer().getAllLevels()){
            SharedContainersState state=SharedContainersState.get(world); CompoundTag dim=new CompoundTag(); dim.putString("Dimension",world.dimension().location().toString()); ListTag blocks=new ListTag(); final int[] n={0}; state.forEachBlock((pos,e)->{CompoundTag b=new CompoundTag();b.putLong("Pos",pos.asLong());b.put("Template",e.template().toNbt(source.getServer().registryAccess()));blocks.add(b);n[0]++;}); if(!blocks.isEmpty()){dim.put("Blocks",blocks);dims.add(dim);count+=n[0];}
        }
        root.put("Dimensions",dims); try{NbtIo.writeCompressed(root,path);}catch(IOException e){source.sendFailure(Component.literal("Export failed: "+e.getMessage()));return 0;}
        final int exportedCount=count; final Path exportedPath=path; source.sendSuccess(()->Component.literal("Exported "+exportedCount+" shared containers to "+exportedPath),true);return exportedCount;
    }
    private static int importContainers(CommandSourceStack source,String fileName,boolean force,int parallel){
        Path path=resolveDataFile(source.getServer(),fileName);if(path==null){source.sendFailure(Component.literal("Invalid file name (plain name only, no path)"));return 0;}
        CompoundTag root;try{root=NbtIo.readCompressed(path,new net.minecraft.nbt.NbtAccounter(64L*1024L*1024L, 16));}catch(IOException e){source.sendFailure(Component.literal("Import failed: "+e.getMessage()));return 0;}
        if(ImportJob.ACTIVE!=null){source.sendFailure(Component.literal("An import is already running"));return 0;}

        Map<ServerLevel,Map<Long,List<ImportJob.PendingPos>>> byChunk=new LinkedHashMap<>();
        int positionCount=0, preSkippedShared=0, preSkippedMissing=0;
        ListTag dims=root.getList("Dimensions",10);
        for(int d=0;d<dims.size();d++){
            CompoundTag dim=dims.getCompound(d);
            ServerLevel world=ImportJob.resolveWorld(source.getServer(),dim.getString("Dimension"));
            ListTag blocks=dim.getList("Blocks",10);
            // Unresolvable dimension - see NOTES.md
            if(world==null){preSkippedMissing+=blocks.size();continue;}
            SharedContainersState state=SharedContainersState.get(world);
            Map<Long,List<ImportJob.PendingPos>> worldChunks=byChunk.computeIfAbsent(world,w->new LinkedHashMap<>());
            for(int i=0;i<blocks.size();i++){
                CompoundTag b=blocks.getCompound(i);
                BlockPos pos=BlockPos.of(b.getLong("Pos"));
                if(state.getBlock(pos)!=null){preSkippedShared++;continue;}
                long chunkKey=ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);
                worldChunks.computeIfAbsent(chunkKey,k->new ArrayList<>()).add(new ImportJob.PendingPos(pos,b.getCompound("Template")));
                positionCount++;
            }
        }

        Deque<ImportJob.ChunkWork> queue=new ArrayDeque<>();
        for(Map.Entry<ServerLevel,Map<Long,List<ImportJob.PendingPos>>> worldEntry:byChunk.entrySet()){
            ServerLevel world=worldEntry.getKey();
            for(Map.Entry<Long,List<ImportJob.PendingPos>> chunkEntry:worldEntry.getValue().entrySet()){
                ChunkPos chunkPos=new ChunkPos(chunkEntry.getKey());
                queue.add(new ImportJob.ChunkWork(world,chunkPos,chunkEntry.getValue()));
            }
        }

        int chunkCount=queue.size();
        ImportJob job=new ImportJob(queue,force,parallel,preSkippedShared,preSkippedMissing,source);
        ImportJob.ACTIVE=job;
        refreshCommandTrees(source.getServer());
        int effectiveParallel=job.parallelism();
        final int finalPositionCount=positionCount, finalPreSkippedShared=preSkippedShared;
        source.sendSuccess(()->Component.literal("Import started: "+finalPositionCount+" positions across "+chunkCount
                +" chunks ("+finalPreSkippedShared+" pre-skipped as already shared), parallel "+effectiveParallel
                +", processed in background via async chunk loading; progress in server log"),true);
        return positionCount;
    }
    private static int cancelImport(CommandSourceStack source){
        ImportJob job=ImportJob.ACTIVE;
        if(job==null){source.sendFailure(Component.literal("No import running"));return 0;}
        job.cancel(source.getServer());
        return 1;
    }
    private static int importStatus(CommandSourceStack source){
        ImportJob job=ImportJob.ACTIVE;
        if(job==null){source.sendSuccess(()->Component.literal("No import running"),false);return 0;}
        String status="Import running: "+job.processed()+"/"+job.total()+" positions, "+job.converted()+" converted, in-flight "+job.inFlightCount()+" chunks, parallel "+job.parallelism();
        source.sendSuccess(()->Component.literal(status),false);
        return 1;
    }
}
