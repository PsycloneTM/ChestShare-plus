package dev.chestshare.mixin;

import dev.chestshare.ContainerRegistrar;
import dev.chestshare.open.SharedContainerOpener;
import dev.chestshare.state.SharedContainerEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
    @Inject(method="getMenuProvider", at=@At("RETURN"), cancellable=true)
    private void chestshare$redirect(BlockState state, Level level, BlockPos pos, CallbackInfoReturnable<MenuProvider> cir){
        if (!(level instanceof ServerLevel world)) return;
        if (!(world.getBlockEntity(pos) instanceof ChestBlockEntity primary)) return;

        // Lazy registration on open, not CHUNK_GENERATE scan - see NOTES.md
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            SharedContainerEntry entry = SharedContainerOpener.resolveBlockEntry(world, primary);
            if (entry != null) {
                cir.setReturnValue(SharedContainerOpener.blockContainerFactory(
                        world, pos, entry, primary));
            }
            return;
        }

        Direction dir = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(dir);
        if (!(world.getBlockEntity(other) instanceof ChestBlockEntity secondary)) return;

        SharedContainerEntry first = SharedContainerOpener.resolveBlockEntry(world, primary);
        SharedContainerEntry second = SharedContainerOpener.resolveBlockEntry(world, secondary);
        if (first == null && second == null) return;
        if (first == null) first = ContainerRegistrar.registerForced(world, primary);
        if (second == null) second = ContainerRegistrar.registerForced(world, secondary);

        boolean primaryIsLeft = state.getValue(ChestBlock.TYPE) == ChestType.LEFT;
        BlockPos firstPos = primaryIsLeft ? pos : other;
        BlockPos secondPos = primaryIsLeft ? other : pos;
        SharedContainerEntry firstEntry = primaryIsLeft ? first : second;
        SharedContainerEntry secondEntry = primaryIsLeft ? second : first;
        cir.setReturnValue(SharedContainerOpener.doubleChestFactory(
                world, firstPos, secondPos, firstEntry, secondEntry));
    }

}
