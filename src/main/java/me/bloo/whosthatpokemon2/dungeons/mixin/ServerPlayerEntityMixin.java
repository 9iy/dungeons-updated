package me.bloo.whosthatpokemon2.dungeons.mixin;

import me.bloo.whosthatpokemon2.dungeons.DungeonRuntime;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void dungeons$preventFreezeClockDrop(boolean entireStack, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        ItemStack stack = self.getInventory().getMainHandStack();
        if (DungeonRuntime.shouldPreventFreezeClockDrop(self, stack)) {
            cir.setReturnValue(null);
            cir.cancel();
        }
    }

    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void dungeons$preventFreezeClockDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (DungeonRuntime.shouldPreventFreezeClockDrop(self, stack)) {
            cir.setReturnValue(null);
            cir.cancel();
        }
    }
}
