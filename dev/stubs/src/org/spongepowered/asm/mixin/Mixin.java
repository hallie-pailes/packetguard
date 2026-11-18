package org.spongepowered.asm.mixin;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) public @interface Mixin { Class<?>[] value() default {}; int priority() default 1000; }
