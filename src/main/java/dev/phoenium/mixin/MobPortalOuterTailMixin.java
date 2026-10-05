package dev.phoenium.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Binding point for MobPortalOuterTails: the mixin plugin verifies the pinned tail of each target at
 * preApply and installs its resume method and suspended early return. The target list mirrors
 * mob-portal-tail-pins.txt (a contract test keeps them identical).
 */
@Mixin(targets = {
    "net.minecraft.world.entity.ambient.Bat",
    "net.minecraft.world.entity.animal.AgeableWaterCreature",
    "net.minecraft.world.entity.animal.allay.Allay",
    "net.minecraft.world.entity.animal.armadillo.Armadillo",
    "net.minecraft.world.entity.animal.axolotl.Axolotl",
    "net.minecraft.world.entity.animal.bee.Bee",
    "net.minecraft.world.entity.animal.camel.Camel",
    "net.minecraft.world.entity.animal.dolphin.Dolphin",
    "net.minecraft.world.entity.animal.feline.Cat",
    "net.minecraft.world.entity.animal.fish.AbstractSchoolingFish",
    "net.minecraft.world.entity.animal.fish.Pufferfish",
    "net.minecraft.world.entity.animal.fish.WaterAnimal",
    "net.minecraft.world.entity.animal.fox.Fox",
    "net.minecraft.world.entity.animal.frog.Frog",
    "net.minecraft.world.entity.animal.golem.CopperGolem",
    "net.minecraft.world.entity.animal.happyghast.HappyGhast",
    "net.minecraft.world.entity.animal.nautilus.AbstractNautilus",
    "net.minecraft.world.entity.animal.nautilus.Nautilus",
    "net.minecraft.world.entity.animal.panda.Panda",
    "net.minecraft.world.entity.animal.parrot.ShoulderRidingEntity",
    "net.minecraft.world.entity.animal.polarbear.PolarBear",
    "net.minecraft.world.entity.animal.rabbit.Rabbit",
    "net.minecraft.world.entity.animal.sniffer.Sniffer",
    "net.minecraft.world.entity.animal.wolf.Wolf",
    "net.minecraft.world.entity.monster.Creeper",
    "net.minecraft.world.entity.monster.Endermite",
    "net.minecraft.world.entity.monster.Phantom",
    "net.minecraft.world.entity.monster.Shulker",
    "net.minecraft.world.entity.monster.Silverfish",
    "net.minecraft.world.entity.monster.Strider",
    "net.minecraft.world.entity.monster.Vex",
    "net.minecraft.world.entity.monster.breeze.Breeze",
    "net.minecraft.world.entity.monster.creaking.Creaking",
    "net.minecraft.world.entity.monster.cubemob.SulfurCube",
    "net.minecraft.world.entity.monster.illager.SpellcasterIllager",
    "net.minecraft.world.entity.monster.skeleton.Skeleton",
    "net.minecraft.world.entity.monster.spider.Spider",
    "net.minecraft.world.entity.monster.warden.Warden",
    "net.minecraft.world.entity.monster.zombie.Zombie",
    "net.minecraft.world.entity.monster.zombie.ZombieVillager"
})
abstract class MobPortalOuterTailMixin { }
