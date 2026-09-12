package dev.chestshare.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// getVisibleChunkIfPresent(long) is package-private - accessor needed for ImportJob. See NOTES.md.
@Mixin(ServerChunkCache.class)
public interface ServerChunkManagerAccessor {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder chestshare$getChunkHolder(long chunkPos);
}
