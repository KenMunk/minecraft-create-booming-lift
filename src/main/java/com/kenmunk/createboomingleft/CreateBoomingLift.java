package com.kenmunk.createboomingleft;

import net.neoforged.fml.common.Mod;

@Mod(CreateBoomingLift.MOD_ID)
public class CreateBoomingLift {
    public static final String MOD_ID = "create_booming_lift";

    public CreateBoomingLift() {
        new CrashDetectionTracker().register();
    }
}
