package com.kenmunk.createboomingleft;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Invisible, AI-free entity placed at each core block position in a Sable physics sublevel.
 * Extends AbstractVillager so Zombies target it via their built-in goal without any injection.
 * Damage dealt to this entity is routed directly to the sublevel's health system.
 * The entity self-discards once its sublevel is removed from the world.
 */
public class SubLevelSentinelEntity extends AbstractVillager {

    private UUID sublevelId = null;

    public SubLevelSentinelEntity(final EntityType<? extends AbstractVillager> type, final Level level) {
        super(type, level);
        setNoAi(true);
        setInvisible(true);
        setNoGravity(true);
        noPhysics = true;
        setSilent(true);
        setPersistenceRequired();
    }

    public void setSublevelId(final UUID id) { this.sublevelId = id; }
    public UUID getSublevelId()              { return sublevelId; }

    @Override
    public void tick() {
        if (!level().isClientSide() && sublevelId != null) {
            final SubLevel s = Sable.HELPER.getContaining((ServerLevel) level(), blockPosition());
            if (!(s instanceof ServerSubLevel ss) || !ss.getUniqueId().equals(sublevelId)) {
                discard();
                return;
            }
        }
        super.tick();
    }

    @Override
    public boolean hurt(final DamageSource source, final float amount) {
        if (sublevelId != null) {
            CrashDetectionTracker.applySentinelDamage(sublevelId, amount);
        }
        return true; // signal hit landed so attackers continue pursuing
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    protected void registerGoals() {}

    // --- AbstractVillager / AgeableMob abstract methods (all no-ops) ---
    @Override public boolean isTrading()                                               { return false; }
    @Override public void openTradingScreen(Player p, Component title, int lvl)       {}
    @Override protected void updateTrades()                                            {}
    @Override public int getVillagerXp()                                               { return 0; }
    @Override public boolean showProgressBar()                                         { return false; }
    @Override public void notifyTrade(MerchantOffer offer)                             {}
    @Override public void notifyTradeUpdated(ItemStack stack)                          {}
    @Override public void rewardTradeXp(MerchantOffer offer)                          {}
    @Override public AgeableMob getBreedOffspring(ServerLevel lvl, AgeableMob mate)   { return null; }
}
