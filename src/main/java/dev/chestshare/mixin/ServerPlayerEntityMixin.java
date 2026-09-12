package dev.chestshare.mixin;

import dev.chestshare.open.SharedContainerOpener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {
    @ModifyVariable(method="openMenu",at=@At("HEAD"),argsOnly=true)
    private MenuProvider chestshare$redirect(MenuProvider provider){return SharedContainerOpener.replaceFactory((ServerPlayer)(Object)this,provider);}
}
