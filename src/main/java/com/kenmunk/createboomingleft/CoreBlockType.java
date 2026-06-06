package com.kenmunk.createboomingleft;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;

public enum CoreBlockType {

    EXPLOSIVE("explosive") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            if (state.getBlock() instanceof TntBlock) return true;
            return tagPathContainsAny(state.getBlock(), "tnt", "explosive", "bomb", "dynamite", "grenade");
        }
    },

    LIFT("lift") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            return tagPathContainsAny(state.getBlock(),
                "lift", "balloon", "buoyancy", "hover", "aerostatic", "lighter_than_air");
        }
    },

    THRUST("thrust") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            return tagPathContainsAny(state.getBlock(),
                "thruster", "propeller", "engine", "turbine", "thrust", "rotor");
        }
    },

    KINETIC("kinetic") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            if (state.hasBlockEntity()) {
                final var be = level.getBlockEntity(pos);
                if (be instanceof KineticBlockEntity) return true;
            }
            return tagPathContainsAny(state.getBlock(), "kinetic", "shaft", "cogwheel", "gearbox", "rotational");
        }
    },

    BURNER("burner") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            return tagPathContainsAny(state.getBlock(), "burner", "heat_source", "heater", "furnace_heat");
        }
    },

    FURNACE("furnace") {
        @Override
        boolean detectByAttribute(final BlockState state, final BlockPos pos, final ServerLevel level) {
            if (state.getBlock() instanceof AbstractFurnaceBlock) return true;
            return tagPathContainsAny(state.getBlock(), "furnace", "smelter", "kiln", "oven", "smelting");
        }
    };

    private final TagKey<Block> overrideTag;

    CoreBlockType(final String name) {
        this.overrideTag = BlockTags.create(
            ResourceLocation.fromNamespaceAndPath("create_booming_lift", "core/" + name));
    }

    /** Returns true if the block at the given position belongs to this type. */
    final boolean matches(final BlockState state, final BlockPos pos, final ServerLevel level) {
        return state.is(overrideTag) || detectByAttribute(state, pos, level);
    }

    abstract boolean detectByAttribute(BlockState state, BlockPos pos, ServerLevel level);

    private static boolean tagPathContainsAny(final Block block, final String... keywords) {
        return BuiltInRegistries.BLOCK.wrapAsHolder(block).tags().anyMatch(tag -> {
            final String path = tag.location().getPath();
            for (final String kw : keywords) {
                if (path.contains(kw)) return true;
            }
            return false;
        });
    }
}
