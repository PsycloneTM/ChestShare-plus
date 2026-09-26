package dev.chestshare.mixin;

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

        boolean primaryIsLeft = state.getValue(ChestBlock.TYPE) == ChestType.LEFT;

        // Exactly one half is shared; the other is an ordinary chest (a null here means
        // resolveBlockEntry found nothing to share: no loot table, not registered, not marked).
        // That half is left completely alone - it is NOT force-registered. Force-registering it
        // would capture a player's own chest contents into a template that every other player
        // then receives a copy of, and would blank the chest itself. Instead the menu shows the
        // shared half from the player's own instance next to the ordinary half's live inventory.
        // (Handing doubleChestFactory a null entry NPEs the instant the menu is opened.)
        if (first == null || second == null) {
            boolean primaryShared = first != null;
            SharedContainerEntry sharedEntry = primaryShared ? first : second;
            BlockPos sharedPos = primaryShared ? pos : other;
            ChestBlockEntity plain = primaryShared ? secondary : primary;
            // "first" (slots 0-26) is the LEFT half, matching doubleChestFactory's ordering below.
            boolean sharedIsFirst = primaryShared == primaryIsLeft;
            cir.setReturnValue(SharedContainerOpener.mixedDoubleChestFactory(
                    world, sharedPos, sharedEntry, plain, sharedIsFirst));
            return;
        }

        BlockPos firstPos = primaryIsLeft ? pos : other;
        BlockPos secondPos = primaryIsLeft ? other : pos;
        SharedContainerEntry firstEntry = primaryIsLeft ? first : second;
        SharedContainerEntry secondEntry = primaryIsLeft ? second : first;
        cir.setReturnValue(SharedContainerOpener.doubleChestFactory(
                world, firstPos, secondPos, firstEntry, secondEntry));
    }

}
