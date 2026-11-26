package com.leek.esp;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

// MOD_ID must equal mod_id in gradle.properties.
@Mod(LeekEsp.MOD_ID)
public class LeekEsp {
    public static final String MOD_ID = "leekesp";

    public LeekEsp() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            EspClient.init();
        }
    }
}
