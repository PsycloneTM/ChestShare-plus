package dev.chestshare.mixin;

import dev.chestshare.SharedMarker;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public class BlockEntityMixin implements SharedMarker {
    @Unique private boolean chestshare$shared;
    @Override public boolean chestshare$isShared(){return chestshare$shared;}
    @Override public void chestshare$setShared(boolean shared){chestshare$shared=shared;}
    @Inject(method="saveAdditional",at=@At("TAIL"))
    private void chestshare$save(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci){if(chestshare$shared)tag.putBoolean("chestshare:shared",true);}
    @Inject(method="loadAdditional",at=@At("TAIL"))
    private void chestshare$load(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci){chestshare$shared=tag.getBoolean("chestshare:shared");}
}
