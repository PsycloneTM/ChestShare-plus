package dev.chestshare.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.chestshare.ChestShareConfig;
import dev.chestshare.ContainerRegistrar;
import dev.chestshare.ChestShare;
import dev.chestshare.SharedMarker;
import dev.chestshare.compat.ContainerCompatibility;
import dev.chestshare.compat.FingerprintRegistry;
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
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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
                .then(literal("adopt-structure")
                        .requires(s->FingerprintRegistry.isBuilt())
                        .then(argument("pos",BlockPosArgument.blockPos())
                                .executes(c->adoptStructure(c.getSource(),BlockPosArgument.getLoadedBlockPos(c,"pos")))))
                .then(literal("toggle")
                        .then(literal("restore-empty-structures")
                                .executes(c->reportRestoreEmptyToggle(c.getSource()))
                                .then(argument("value",BoolArgumentType.bool())
                                        .executes(c->setRestoreEmptyToggle(c.getSource(),BoolArgumentType.getBool(c,"value"))))))
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

    private static int adoptStructure(CommandSourceStack source, BlockPos pos) {
        if (!FingerprintRegistry.isBuilt()) {
            source.sendFailure(Component.literal("Fingerprint registry not built yet — wait for server startup to finish"));
            return 0;
        }
        ServerLevel world = source.getLevel();
        StructureStart start = world.structureManager().getStructureWithPieceAt(pos, h -> true);
        if (start == null || !start.isValid()) {
            source.sendFailure(Component.literal("No registered structure found at " + coordString(pos)));
            return 0;
        }

        int adopted = 0;
        int restored = 0;
        int skippedShared = 0;
        int skippedNotContainer = 0;
        int skippedFailed = 0;
        int piecesById = 0;
        int piecesByLayout = 0;
        int totalPieces = 0;
        int jigsawPieces = 0;
        int readableIds = 0;
        int templateEmpty = 0;

        // Two buckets, not one flat set: precisePositions carries the whole template block we
        // trust (from FingerprintRegistry, transformed through this piece's rotation/origin) -
        // its baked items OR its loot table - so those get restored to their exact original
        // contents even if already shared with the wrong contents. fallbackPositions is the old
        // bbox-scan path for pieces we can't resolve a template id for; we don't know what
        // SHOULD be in those containers, so it keeps the old "steal current contents, skip if
        // already shared" behavior instead of overwriting anything.
        java.util.Map<BlockPos, FingerprintRegistry.StructureStorageBlock> precisePositions = new java.util.LinkedHashMap<>();
        java.util.Set<BlockPos> fallbackPositions = new java.util.HashSet<>();

        for (StructurePiece piece : start.getPieces()) {
            totalPieces++;
            if (piece instanceof PoolElementStructurePiece poolPiece) {
                jigsawPieces++;
                // Resolve which structure template this piece was built from. In 1.21.1 the
                // element's toString() is "Single[Left[<ns>:<path>]]" (the template is an
                // Either), NOT "Single[<ns>:<path>]" - the old parser took everything up to the
                // first ']' and got "Left[<ns>:<path>", which is never a valid ResourceLocation,
                // so EVERY piece silently fell through to the bounding-box fallback (which can't
                // restore empty containers). See resolveTemplateId().
                ResourceLocation templateId = resolveTemplateId(poolPiece);
                if (templateId != null) readableIds++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure: piece {} element={} -> templateId={}",
                        poolPiece.getClass().getSimpleName(), poolPiece.getElement(), templateId);
                List<FingerprintRegistry.StructureStorageBlock> relBlocks = templateId == null ? null
                        : FingerprintRegistry.getStructureStorageBlocks(templateId);
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure: fingerprint registry has {} storage block(s) for {}",
                        relBlocks == null ? "no" : relBlocks.size(), templateId);
                net.minecraft.world.level.block.Rotation rotation = poolPiece.getRotation();
                BlockPos origin = poolPiece.getPosition();
                boolean resolvedById = relBlocks != null && !relBlocks.isEmpty();
                if (relBlocks == null || relBlocks.isEmpty()) {
                    // Last resort: id unresolvable (or template has no recorded storage). Find the
                    // one registered template whose storage layout, placed with this piece's
                    // rotation/origin, lands exactly on the containers that really exist here.
                    relBlocks = inferTemplateByLayout(world, poolPiece, rotation, origin);
                }
                if (relBlocks != null && !relBlocks.isEmpty()) {
                    net.minecraft.world.level.block.Mirror mirror = net.minecraft.world.level.block.Mirror.NONE;
                    java.util.Map<BlockPos, FingerprintRegistry.StructureStorageBlock> piecePositions = new java.util.LinkedHashMap<>();
                    int hits = 0;
                    for (FingerprintRegistry.StructureStorageBlock block : relBlocks) {
                        BlockPos relPos = new BlockPos(block.x(), block.y(), block.z());
                        BlockPos worldPos = StructureTemplate.transform(relPos, mirror, rotation, BlockPos.ZERO)
                                .offset(origin);
                        piecePositions.put(worldPos, block);
                        if (isAdoptableContainer(world.getBlockEntity(worldPos))) hits++;
                    }
                    if (hits > 0) {
                        if (resolvedById) piecesById++; else piecesByLayout++;
                        precisePositions.putAll(piecePositions);
                        continue; // handled this piece precisely, skip bbox fallback
                    }
                    // Template found, but none of its storage positions holds a container in the
                    // world: the placement transform must be off for this piece. Don't trust it -
                    // fall through so the content-alignment pass (which doesn't use this
                    // transform) can still locate the containers.
                    ChestShare.LOGGER.warn("[ChestShare] adopt-structure: template {} resolved but 0/{} of its storage positions hold a container "
                            + "(rotation={}, origin={}) - ignoring it for this piece", templateId, relBlocks.size(), rotation, coordString(origin));
                }
            } else {
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure: piece {} is not a PoolElementStructurePiece, using bounding-box fallback",
                        piece.getClass().getSimpleName());
            }
            // Fallback: scan every block entity inside this piece's bounding box
            // and accept any SS/CF container as a target.
            var bbox = piece.getBoundingBox();
            for (int x = bbox.minX(); x <= bbox.maxX(); x++) {
                for (int y = bbox.minY(); y <= bbox.maxY(); y++) {
                    for (int z = bbox.minZ(); z <= bbox.maxZ(); z++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (isAdoptableContainer(world.getBlockEntity(candidate))) {
                            fallbackPositions.add(candidate);
                        }
                    }
                }
            }
        }
        // A position resolved precisely by one piece shouldn't also be reprocessed as a
        // fallback guess from another (overlapping bounding boxes do happen).
        fallbackPositions.removeAll(precisePositions.keySet());

        // Content alignment: for whatever is still unresolved (piece isn't a jigsaw piece, id
        // couldn't be read, ...), locate the template from the containers that still hold their
        // original contents, then pin the emptied ones by position. See alignByContents().
        int alignedByContent = 0;
        int[] alignStats = new int[2];
        if (!fallbackPositions.isEmpty()) {
            alignedByContent = alignByContents(world, fallbackPositions, precisePositions, alignStats);
            fallbackPositions.removeAll(precisePositions.keySet());
        }

        if (precisePositions.isEmpty() && fallbackPositions.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No storage block positions found for this structure in the fingerprint registry — " +
                    "are Sophisticated Storage / CobbleFurnies active and is the structure template registered?"));
            return 0;
        }

        // Precise path: we know the exact original contents, so restore them via
        // registerWithItems regardless of current live contents or existing shared state -
        // this is what makes adopt-structure able to fix containers that were already
        // registered with the wrong (looted) contents, not just adopt untouched ones.
        int lootTableRestored = 0;
        // What the TEMPLATE says about these positions, counted before anything about the world
        // is checked - so the resolution line describes the template itself, not what survived in
        // the world. templateEmpty below is a different number on purpose: it only counts
        // positions where a real container is standing and the template gives it nothing.
        int templateItems = 0, templateLoot = 0, templateBlank = 0;
        for (var e : precisePositions.entrySet()) {
            BlockPos targetPos = e.getKey();
            FingerprintRegistry.StructureStorageBlock block = e.getValue();
            if (!block.items().isEmpty()) templateItems++;
            else if (block.hasLootTable()) templateLoot++;
            else templateBlank++;

            BlockEntity be = world.getBlockEntity(targetPos);
            if (!isAdoptableContainer(be)) {
                skippedNotContainer++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure: no recognized container at {}", coordString(targetPos));
                continue;
            }
            boolean wasShared = be instanceof SharedMarker m && m.chestshare$isShared();

            // Loot-table containers first: a vanilla-style loot container (vanilla chest,
            // Cobblemon gilded chest, anything else built on RandomizableContainerBlockEntity)
            // bakes NO items into the template at all, only a LootTable string. Checking
            // items.isEmpty() first - as this loop used to - classified every one of them as
            // "empty in the template itself" and left them alone, which is why an emptied
            // gilded chest sitting right next to a correctly-restored Sophisticated Storage
            // chest was never adopted. See command/NOTES.md.
            if (block.hasLootTable()) {
                ResourceLocation tableId = ResourceLocation.tryParse(block.lootTable());
                if (tableId == null) {
                    skippedFailed++;
                    ChestShare.LOGGER.warn("[ChestShare] adopt-structure: container at {} has an unparseable loot table id '{}' in the template",
                            coordString(targetPos), block.lootTable());
                    continue;
                }
                // Already pointed at this exact table: re-registering would throw away every
                // player's existing roll for no gain. /chestshare reset is the command for that.
                SharedContainerEntry existing = wasShared ? SharedContainersState.get(world).getBlock(targetPos) : null;
                if (existing != null && existing.template() instanceof ContainerTemplate.LootTableTemplate lt
                        && lt.lootTable().equals(tableId)) {
                    skippedShared++;
                    continue;
                }
                // Seed: the template's own baked LootTableSeed, which is 0 in practice for
                // almost every structure. 0 makes LootTableTemplate fall back to the
                // position-derived seed, so the same block always rolls the same loot for a
                // given player - the determinism rule convert() follows. See NOTES.md.
                SharedContainerEntry registered;
                if (be instanceof RandomizableContainerBlockEntity randomizable) {
                    registered = randomizable.getLootTable() != null
                            // Still holds its own loot table (never opened): that IS the placed
                            // state, so it beats the template's copy of it, which a datapack or
                            // a structure processor could have diverged from since generation.
                            ? ContainerRegistrar.register(world, randomizable, true)
                            : ContainerRegistrar.applyTemplate(world, randomizable,
                                    new ContainerTemplate.LootTableTemplate(tableId, block.lootTableSeed(),
                                            randomizable.getContainerSize()), true);
                } else {
                    registered = ContainerCompatibility.registerWithLootTable(world, be, tableId,
                            block.lootTableSeed(), true);
                }
                if (registered != null) {
                    lootTableRestored++;
                    ChestShare.LOGGER.debug("[ChestShare] adopt-structure: {} '{}' at {} in {} to loot table {}",
                            wasShared ? "re-pointed" : "registered", be.getClass().getSimpleName(),
                            coordString(targetPos), world.dimension().location(), tableId);
                } else {
                    skippedFailed++;
                    ChestShare.LOGGER.warn("[ChestShare] adopt-structure: container at {} ('{}') has no readable inventory slots - could not restore it",
                            coordString(targetPos), be.getClass().getName());
                }
                continue;
            }

            if (block.items().isEmpty()) {
                // The template itself places this container empty (a decorative cabinet, say)
                // AND gives it no loot table. Sharing it would only make a player's own storage
                // per-player - leave it alone.
                templateEmpty++;
                continue;
            }
            // Already shared with exactly these baked items: re-registering would wipe every
            // player's existing per-player roll for zero effect - identical to the loot-table
            // check above, just comparing an item list instead of a loot table id. This is what
            // makes running the command twice in a row settle into "already shared" instead of
            // reporting a "restore" every single time.
            if (wasShared) {
                SharedContainerEntry existing = SharedContainersState.get(world).getBlock(targetPos);
                if (existing != null && sharedEntryMatchesBaked(existing, block.items())) {
                    skippedShared++;
                    continue;
                }
            }
            SharedContainerEntry registered;
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                // Vanilla-class container with baked items: go through ContainerRegistrar so the
                // block entity's own loot table gets cleared too, not just its inventory.
                registered = ContainerRegistrar.applyTemplate(world, randomizable,
                        new ContainerTemplate.ItemListTemplate(
                                ContainerCompatibility.toStacks(block.items(), randomizable.getContainerSize()),
                                randomizable.getContainerSize()), true);
            } else {
                registered = ContainerCompatibility.registerWithItems(world, be, block.items(), true);
            }
            if (registered != null) {
                if (wasShared) restored++; else adopted++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure: {} '{}' at {} in {} ({} baked items)",
                        wasShared ? "restored" : "registered", be.getClass().getSimpleName(),
                        coordString(targetPos), world.dimension().location(), block.items().size());
            } else {
                skippedFailed++;
                ChestShare.LOGGER.warn("[ChestShare] adopt-structure: container at {} ('{}') has no readable inventory slots - could not restore it",
                        coordString(targetPos), be.getClass().getName());
            }
        }

        // Fallback path: no known-correct contents for these, so behave like before -
        // steal whatever's currently there, and don't touch anything already shared.
        int skippedEmpty = 0;
        for (BlockPos targetPos : fallbackPositions) {
            BlockEntity be = world.getBlockEntity(targetPos);
            if (!isAdoptableContainer(be)) {
                skippedNotContainer++;
                continue;
            }
            if (be instanceof SharedMarker m && m.chestshare$isShared()) {
                skippedShared++;
                continue;
            }
            // A loot container that still has its loot table is unambiguously untouched
            // world-gen loot, whatever piece it sits in - register it from the table rather
            // than from its (unrolled, therefore empty) inventory. A player's own chest never
            // has a loot table, so this can't sweep up player storage the way a contents-steal
            // inside a structure's bounding box could.
            if (be instanceof RandomizableContainerBlockEntity randomizable && randomizable.getLootTable() != null) {
                SharedContainerEntry fromTable = ContainerRegistrar.register(world, randomizable, true);
                if (fromTable != null) {
                    lootTableRestored++;
                    ChestShare.LOGGER.debug("[ChestShare] adopt-structure (fallback): registered '{}' at {} in {} from its intact loot table",
                            be.getClass().getSimpleName(), coordString(targetPos), world.dimension().location());
                    continue;
                }
            }
            if (!ContainerCompatibility.isSupportedContainer(be)) {
                // Vanilla-class container, no loot table left, and no template to restore from.
                // Stealing its current contents here would be as likely to capture a player's
                // own chest as anything else, so leave it alone and say so.
                skippedEmpty++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure (fallback): '{}' at {} has no loot table left and no "
                        + "template entry - no known original contents, left unshared",
                        be.getClass().getSimpleName(), coordString(targetPos));
                continue;
            }
            SharedContainerEntry registered = ContainerCompatibility.register(world, be, true);
            if (registered != null) {
                adopted++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure (fallback): registered '{}' at {} in {}",
                        be.getClass().getSimpleName(), coordString(targetPos), world.dimension().location());
            } else {
                // register() bails silently on a currently-empty container - there's nothing
                // to steal, and this fallback path (unlike the precise path) has no known
                // baked contents to fill it with instead. Counted and logged rather than
                // silently vanishing from every bucket in the summary, which is exactly what
                // made "12 containers but only 10 accounted for" undiagnosable from chat alone.
                skippedEmpty++;
                ChestShare.LOGGER.debug("[ChestShare] adopt-structure (fallback): '{}' at {} is empty and not fingerprinted - "
                        + "no known original contents to restore, left unshared",
                        be.getClass().getSimpleName(), coordString(targetPos));
            }
        }

        final int fAdopted = adopted, fRestored = restored, fShared = skippedShared, fMissing = skippedNotContainer, fEmpty = skippedEmpty;
        final int fFailed = skippedFailed, fById = piecesById, fByLayout = piecesByLayout, fAligned = alignedByContent;
        final int fTotal = totalPieces, fJigsaw = jigsawPieces, fIds = readableIds, fTplEmpty = templateEmpty;
        final int fAnchors = alignStats[0], fAnchorHits = alignStats[1];
        final int fFallback = fallbackPositions.size();
        final int fLoot = lootTableRestored, fTplItems = templateItems, fTplLoot = templateLoot;
        final int fTplBlank = templateBlank;

        // One result line, listing only the buckets that actually happened - the per-piece and
        // per-container detail is at DEBUG, not INFO, so a normal run leaves the server log alone.
        String summary = "adopt-structure: " + fAdopted + " container(s) adopted"
                + (fRestored > 0 ? ", " + fRestored + " restored to original contents" : "")
                + (fLoot > 0 ? ", " + fLoot + " restored from the template's loot table" : "")
                + (fShared > 0 ? ", " + fShared + " already shared (left as-is)" : "")
                + (fEmpty > 0 ? ", " + fEmpty + " empty with no known original contents" : "")
                + (fMissing > 0 ? ", " + fMissing + " position(s) had no recognized container (empty/broken/replaced)" : "")
                + (fTplEmpty > 0 ? ", " + fTplEmpty + " empty in the template itself (left alone)" : "")
                + (fFailed > 0 ? ", " + fFailed + " could not be restored (see log)" : "");
        source.sendSuccess(() -> Component.literal(summary), true);
        ChestShare.LOGGER.info("[ChestShare] {} (in {})", summary, world.dimension().location());

        // Second line only when something was left unhandled: on a clean run it says nothing the
        // first line didn't, and on a bad run it is the difference between "why wasn't X restored"
        // being answerable from chat and needing a source read. Reading it: "0 readable ids" = a
        // piece/id problem; "0 match a known template block" = the registry's baked contents don't
        // explain what's in the world; matches but 0 located = the layout doesn't fit.
        if (fMissing > 0 || fEmpty > 0 || fFailed > 0 || fFallback > 0) {
            source.sendSuccess(() -> Component.literal(
                    "adopt-structure resolution: " + fTotal + " piece(s), " + fJigsaw + " jigsaw, "
                    + fIds + " with a readable template id; " + fById + " matched by id, " + fByLayout
                    + " by layout. Template blocks: " + fTplItems + " with baked items, " + fTplLoot
                    + " with a loot table, " + fTplBlank + " empty. Content alignment: " + fAnchors
                    + " container(s) holding items, " + fAnchorHits + " match a known template block, "
                    + fAligned + " position(s) located. "
                    + fFallback + " container(s) left on the contents-only fallback."),
                    false);
        }
        return adopted + restored + lootTableRestored;
    }

    /** True if an already-shared entry's item list is exactly the template's baked items - same
     *  slots, same ids, same counts. Compared as unordered {@link FingerprintRegistry.FingerprintItem}
     *  sets (slot+id+count), the same granularity {@link FingerprintRegistry} itself matches at,
     *  rather than full ItemStack equality (which would also compare NBT/components the registry
     *  never recorded in the first place). Used so a second adopt-structure run over an already-
     *  correct container reports "already shared", not another "restored". */
    private static boolean sharedEntryMatchesBaked(SharedContainerEntry existing,
            List<FingerprintRegistry.FingerprintItem> baked) {
        if (!(existing.template() instanceof ContainerTemplate.ItemListTemplate t)) return false;
        java.util.Set<FingerprintRegistry.FingerprintItem> current = new java.util.HashSet<>();
        List<ItemStack> items = t.items();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack == null || stack.isEmpty()) continue;
            current.add(new FingerprintRegistry.FingerprintItem(i, FingerprintRegistry.idOf(stack), stack.getCount()));
        }
        return current.equals(new java.util.HashSet<>(baked));
    }

    /** The live-world counterpart of compat/ContainerFingerprintFilter: is this block entity
     *  something adopt-structure can take over?
     *
     *  Kept deliberately narrow on the vanilla side. HopperBlockEntity, DispenserBlockEntity and
     *  DropperBlockEntity are all RandomizableContainerBlockEntity too, and counting a
     *  structure's hoppers as containers would put positions in the live set that no template
     *  block matches - which breaks inferTemplateByLayout (it compares position sets exactly)
     *  and drags down StructureAligner's coverage test. A vanilla block entity that actually
     *  carries a loot table is accepted whatever its type, since that is world-gen loot by
     *  definition. */
    private static boolean isAdoptableContainer(BlockEntity be) {
        if (be == null) return false;
        // Modded storage (Sophisticated Storage, CobbleFurnies, Cobblemon's gilded chest, and
        // anything else implementing Container or matching the reflective handler).
        if (ContainerCompatibility.isSupportedContainer(be)) return true;
        if (!(be instanceof RandomizableContainerBlockEntity randomizable)) return false;
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity || randomizable.getLootTable() != null;
    }

    /**
     * Locates registered structure templates purely from container contents: any unresolved
     * container that still holds exactly some template block's baked items is an anchor, and
     * StructureAligner finds the (template, rotation, origin) that explains them. Every storage
     * block of that template - including ones a player already emptied - then gets its exact
     * original contents added to precise. Already-shared containers are anchored by the items
     * in their shared template, since their live inventory is empty by design.
     * Returns how many new positions were resolved.
     */
    private static int alignByContents(ServerLevel world, java.util.Set<BlockPos> candidates,
            java.util.Map<BlockPos, FingerprintRegistry.StructureStorageBlock> precise, int[] stats) {
        java.util.Map<String, List<FingerprintRegistry.StructureStorageBlock>> raw = new java.util.HashMap<>();
        java.util.Map<String, List<dev.chestshare.compat.StructureAligner.Blk>> templates = new java.util.HashMap<>();
        for (var e : FingerprintRegistry.allStructureBlocks().entrySet()) {
            List<dev.chestshare.compat.StructureAligner.Blk> blks = new ArrayList<>();
            for (FingerprintRegistry.StructureStorageBlock b : e.getValue()) {
                java.util.Set<dev.chestshare.compat.StructureAligner.Itm> items = new java.util.HashSet<>();
                for (FingerprintRegistry.FingerprintItem fi : b.items()) {
                    items.add(new dev.chestshare.compat.StructureAligner.Itm(fi.slot(), fi.id(), fi.count()));
                }
                blks.add(new dev.chestshare.compat.StructureAligner.Blk(b.x(), b.y(), b.z(), items));
            }
            raw.put(e.getKey().toString(), e.getValue());
            templates.put(e.getKey().toString(), blks);
        }

        SharedContainersState state = SharedContainersState.get(world);
        List<dev.chestshare.compat.StructureAligner.Anch> anchors = new ArrayList<>();
        java.util.Set<Long> live = new java.util.HashSet<>();
        for (BlockPos p : candidates) {
            BlockEntity be = world.getBlockEntity(p);
            if (!isAdoptableContainer(be)) continue;
            ContainerCompatibility.CompatInventory inv = ContainerCompatibility.getContainerInventory(be);
            if (inv == null) continue;
            live.add(dev.chestshare.compat.StructureAligner.pack(p.getX(), p.getY(), p.getZ()));
            java.util.Set<dev.chestshare.compat.StructureAligner.Itm> items = new java.util.HashSet<>();
            SharedContainerEntry entry = (be instanceof SharedMarker m && m.chestshare$isShared()) ? state.getBlock(p) : null;
            if (entry != null) {
                if (entry.template() instanceof ContainerTemplate.ItemListTemplate t) {
                    for (int i = 0; i < t.items().size(); i++) {
                        ItemStack st = t.items().get(i);
                        if (st != null && !st.isEmpty()) {
                            items.add(new dev.chestshare.compat.StructureAligner.Itm(i, FingerprintRegistry.idOf(st), st.getCount()));
                        }
                    }
                }
            } else {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack st = inv.get(i);
                    if (st != null && !st.isEmpty()) {
                        items.add(new dev.chestshare.compat.StructureAligner.Itm(i, FingerprintRegistry.idOf(st), st.getCount()));
                    }
                }
            }
            if (!items.isEmpty()) {
                anchors.add(new dev.chestshare.compat.StructureAligner.Anch(p.getX(), p.getY(), p.getZ(), items));
            }
        }
        int matching = dev.chestshare.compat.StructureAligner.countMatchingAnchors(templates, anchors);
        stats[0] = anchors.size();
        stats[1] = matching;
        ChestShare.LOGGER.debug("[ChestShare] adopt-structure: content alignment: {} container(s) unresolved, {} hold items ({} of those exactly match a known template block)",
                candidates.size(), anchors.size(), matching);

        int added = 0;
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (var e : dev.chestshare.compat.StructureAligner.locateAll(templates, anchors, live)) {
            dev.chestshare.compat.StructureAligner.Placed pl = e.getValue();
            BlockPos wp = new BlockPos(pl.x(), pl.y(), pl.z());
            FingerprintRegistry.StructureStorageBlock baked = raw.get(e.getKey()).get(pl.blockIndex());
            if (precise.putIfAbsent(wp, baked) == null) added++;
            used.add(e.getKey());
        }
        if (added > 0) {
            ChestShare.LOGGER.debug("[ChestShare] adopt-structure: located template(s) {} by content alignment ({} position(s))", used, added);
        } else {
            ChestShare.LOGGER.debug("[ChestShare] adopt-structure: content alignment found no template that explains the containers here");
        }
        return added;
    }

    /** Delegates to {@link dev.chestshare.compat.StructurePlacement#resolveTemplateId}: the passive
     *  empty-container restore resolves piece templates the same way, so the logic lives in one
     *  place. */
    private static ResourceLocation resolveTemplateId(PoolElementStructurePiece piece) {
        return dev.chestshare.compat.StructurePlacement.resolveTemplateId(piece);
    }

    static ResourceLocation parseTemplateId(String elementString) {
        return dev.chestshare.compat.StructurePlacement.parseTemplateId(elementString);
    }

    /** Layout-matching safety net for pieces whose template id can't be resolved. Collects every
     *  supported container that really exists inside the piece's bounding box, then looks for a
     *  registered template whose storage positions - transformed by this piece's rotation and
     *  origin - are EXACTLY that set. Returns that template's storage blocks only if precisely one
     *  distinct layout matches; ambiguous or no match returns null (caller falls back to the
     *  contents-stealing bbox scan). */
    private static List<FingerprintRegistry.StructureStorageBlock> inferTemplateByLayout(
            ServerLevel world, PoolElementStructurePiece piece,
            net.minecraft.world.level.block.Rotation rotation, BlockPos origin) {
        java.util.Set<BlockPos> live = new java.util.HashSet<>();
        var bbox = piece.getBoundingBox();
        for (int x = bbox.minX(); x <= bbox.maxX(); x++) {
            for (int y = bbox.minY(); y <= bbox.maxY(); y++) {
                for (int z = bbox.minZ(); z <= bbox.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isAdoptableContainer(world.getBlockEntity(p))) live.add(p);
                }
            }
        }
        if (live.isEmpty()) return null;

        List<FingerprintRegistry.StructureStorageBlock> match = null;
        ResourceLocation matchId = null;
        for (var e : FingerprintRegistry.allStructureBlocks().entrySet()) {
            List<FingerprintRegistry.StructureStorageBlock> blocks = e.getValue();
            if (blocks.size() != live.size()) continue;
            java.util.Set<BlockPos> placed = new java.util.HashSet<>();
            for (FingerprintRegistry.StructureStorageBlock b : blocks) {
                placed.add(StructureTemplate.transform(new BlockPos(b.x(), b.y(), b.z()),
                        net.minecraft.world.level.block.Mirror.NONE, rotation, BlockPos.ZERO).offset(origin));
            }
            if (!placed.equals(live)) continue;
            if (match != null && !sameContents(match, blocks)) {
                ChestShare.LOGGER.warn("[ChestShare] adopt-structure: layout match is ambiguous ({} and {} both fit) - not guessing",
                        matchId, e.getKey());
                return null;
            }
            if (match == null) { match = blocks; matchId = e.getKey(); }
        }
        if (match != null) {
            ChestShare.LOGGER.debug("[ChestShare] adopt-structure: template id unresolved; matched by container layout to {}", matchId);
        } else {
            ChestShare.LOGGER.debug("[ChestShare] adopt-structure: template id unresolved and no template layout matches the {} container(s) found in this piece", live.size());
        }
        return match;
    }

    /** Two candidate layouts are interchangeable if they place identical items in identical slots at identical positions. */
    private static boolean sameContents(List<FingerprintRegistry.StructureStorageBlock> a, List<FingerprintRegistry.StructureStorageBlock> b) {
        return restoreView(a).equals(restoreView(b));
    }

    /** What restoring a template block would actually do: position plus baked items / loot table.
     *  The block id and palette flag the registry also records are deliberately left out - they
     *  only matter to the passive restore, and two layouts that restore identically are
     *  interchangeable here whatever block they happen to place. */
    private static java.util.Set<List<Object>> restoreView(List<FingerprintRegistry.StructureStorageBlock> blocks) {
        java.util.Set<List<Object>> view = new java.util.HashSet<>();
        for (FingerprintRegistry.StructureStorageBlock b : blocks) {
            view.add(java.util.Arrays.asList(b.x(), b.y(), b.z(), b.items(), b.lootTable(), b.lootTableSeed()));
        }
        return view;
    }

    private static int reportRestoreEmptyToggle(CommandSourceStack source) {
        boolean value = ChestShareConfig.restoreEmptyStructureContainers();
        source.sendSuccess(() -> Component.literal("restore-empty-structures is currently "
                + (value ? "ON" : "OFF") + " — use /chestshare toggle restore-empty-structures <true|false> to change"), false);
        return value ? 1 : 0;
    }

    private static int setRestoreEmptyToggle(CommandSourceStack source, boolean value) {
        boolean saved = ChestShareConfig.setRestoreEmptyStructureContainers(value);
        source.sendSuccess(() -> Component.literal("restore-empty-structures set to " + (value ? "ON" : "OFF")
                + (saved ? " (saved, persists across restarts)" : " (applied for this session only — could not save to disk, see log)")), true);
        return value ? 1 : 0;
    }
}
