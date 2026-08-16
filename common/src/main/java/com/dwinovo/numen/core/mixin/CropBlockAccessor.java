package com.dwinovo.numen.core.mixin;

import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to CropBlock's protected {@code getAgeProperty} so the gentle-harvest
 * task can reset a mature crop's age to 0 (the villager trick).
 */
@Mixin(CropBlock.class)
public interface CropBlockAccessor {

    @Invoker("getAgeProperty")
    IntegerProperty numen$getAgeProperty();
}
