package dev.mrsterner.guardvillagers.common.entity;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dev.mrsterner.guardvillagers.GuardVillagersConfig;
import dev.mrsterner.guardvillagers.common.GuardLootTables;
import dev.mrsterner.guardvillagers.common.entity.ai.goals.*;
import dev.mrsterner.guardvillagers.mixin.MeleeAttackGoalAccessor;
import dev.mrsterner.guardvillagers.mixin.ServerPlayerEntityAccessor;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.PolarBearEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.village.VillagerType;
import net.minecraft.world.*;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.apache.logging.log4j.core.jmx.Server;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

public class GuardEntity extends PathAwareEntity implements CrossbowUser, RangedAttackMob, Angerable, InventoryChangedListener {
    private static final UUID MODIFIER_UUID = UUID.fromString("5CD17E52-A79A-43D3-A529-90FDE04B181E");
    private static final EntityAttributeModifier USE_ITEM_SPEED_PENALTY = new EntityAttributeModifier(MODIFIER_UUID, "Use item speed penalty", -0.25D, EntityAttributeModifier.Operation.ADDITION);
    private static final TrackedData<Optional<BlockPos>> GUARD_POS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Boolean> PATROLLING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> GUARD_VARIANT = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> RUNNING_TO_EAT = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> DATA_CHARGING_STATE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> EATING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> KICKING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> FOLLOWING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    protected static final TrackedData<Optional<UUID>> OWNER_UNIQUE_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final Map<EntityPose, EntityDimensions> SIZE_BY_POSE = ImmutableMap.<EntityPose, EntityDimensions>builder()
    .put(EntityPose.STANDING, EntityDimensions.changing(0.6F, 1.95F)).put(EntityPose.SLEEPING, SLEEPING_DIMENSIONS)
    .put(EntityPose.FALL_FLYING, EntityDimensions.changing(0.6F, 0.6F))
    .put(EntityPose.SWIMMING, EntityDimensions.changing(0.6F, 0.6F))
    .put(EntityPose.SPIN_ATTACK, EntityDimensions.changing(0.6F, 0.6F))
    .put(EntityPose.CROUCHING, EntityDimensions.changing(0.6F, 1.75F))
    .put(EntityPose.DYING, EntityDimensions.fixed(0.2F, 0.2F)).build();
    public SimpleInventory guardInventory = new SimpleInventory(6);
    public int kickTicks;
    public int shieldCoolDown;
    public int kickCoolDown;
    public boolean interacting;
    private int remainingPersistentAngerTime;
    private static final UniformIntProvider angerTime = TimeHelper.betweenSeconds(20, 39);
    private UUID persistentAngerTarget;
    private static final Map<EquipmentSlot, Identifier> EQUIPMENT_SLOT_ITEMS = Util.make(Maps.newHashMap(),
    (slotItems) -> {
        slotItems.put(EquipmentSlot.MAINHAND, GuardLootTables.GUARD_MAIN_HAND);
        slotItems.put(EquipmentSlot.OFFHAND, GuardLootTables.GUARD_OFF_HAND);
        slotItems.put(EquipmentSlot.HEAD, GuardLootTables.GUARD_HELMET);
        slotItems.put(EquipmentSlot.CHEST, GuardLootTables.GUARD_CHEST);
        slotItems.put(EquipmentSlot.LEGS, GuardLootTables.GUARD_LEGGINGS);
        slotItems.put(EquipmentSlot.FEET, GuardLootTables.GUARD_FEET);
    });

    public GuardEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.guardInventory.addListener(this);
        this.setPersistent();
    }

    @Nullable
    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, @Nullable EntityData entityData, @Nullable NbtCompound entityNbt) {
        this.setPersistent();
        int type = GuardEntity.getRandomTypeForBiome(world, this.getBlockPos());
        if (entityData instanceof GuardEntity.GuardData) {
            type = ((GuardEntity.GuardData) entityData).variantData;
            entityData = new GuardEntity.GuardData(type);
        }
        this.setGuardVariant(type);
        this.initEquipment(difficulty);
        return super.initialize(world, difficulty, spawnReason, entityData, entityNbt);
    }

    @Override
    protected void initEquipment(LocalDifficulty difficulty) {
        for (EquipmentSlot equipmentslottype : EquipmentSlot.values()) {
            for (ItemStack stack : this.getItemsFromLootTable(equipmentslottype)) {
                this.equipStack(equipmentslottype, stack);
            }
        }
        this.handDropChances[EquipmentSlot.MAINHAND.getEntitySlotId()] = 100.0F;
        this.handDropChances[EquipmentSlot.OFFHAND.getEntitySlotId()] = 100.0F;
    }

    @Override
    protected void pushAway(Entity entity) {
        if (entity instanceof PathAwareEntity) {
            PathAwareEntity living = (PathAwareEntity) entity;
            boolean attackTargets = living.getTarget() instanceof VillagerEntity || living.getTarget() instanceof IronGolemEntity || living.getTarget() instanceof GuardEntity;
            if (attackTargets)
                this.setTarget(living);
        }
        super.pushAway(entity);
    }

    @Nullable
    public void setPatrolPos(BlockPos position) {
        this.dataTracker.set(GUARD_POS, Optional.ofNullable(position));
    }

    @Nullable
    public BlockPos getPatrolPos() {
        return this.dataTracker.get(GUARD_POS).orElse((BlockPos) null);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        if (this.isBlocking()) {
            return SoundEvents.ITEM_SHIELD_BLOCK;
        } else {
            return SoundEvents.ENTITY_VILLAGER_HURT;
        }
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_VILLAGER_DEATH;
    }

    public static int slotToInventoryIndex(EquipmentSlot slot) {
        switch (slot) {
            case CHEST:
                return 1;
            case FEET:
                return 3;
            case HEAD:
                return 0;
            case LEGS:
                return 2;
            default:
                break;
        }
        return 0;
    }

    @Override
    protected void dropEquipment(DamageSource source, int lootingMultiplier, boolean allowDrops) {
        for (int i = 0; i < this.guardInventory.size(); ++i) {
            ItemStack itemstack = this.guardInventory.getStack(i);
            if (!itemstack.isEmpty() && !EnchantmentHelper.hasVanishingCurse(itemstack))
                this.dropStack(itemstack);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        UUID uuid = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        if (uuid != null) {
            try {
                this.setOwnerId(uuid);
            } catch (Throwable throwable) {
                this.setOwnerId(null);
            }
        }
        this.setGuardVariant(nbt.getInt("Type"));
        this.kickTicks = nbt.getInt("KickTicks");
        this.setFollowing(nbt.getBoolean("Following"));
        this.interacting = nbt.getBoolean("Interacting");
        this.setEating(nbt.getBoolean("Eating"));
        this.setPatrolling(nbt.getBoolean("Patrolling"));
        this.setRunningToEat(nbt.getBoolean("RunningToEat"));
        this.shieldCoolDown = nbt.getInt("KickCooldown");
        this.kickCoolDown = nbt.getInt("ShieldCooldown");
        if (nbt.contains("PatrolPosX")) {
            int x = nbt.getInt("PatrolPosX");
            int y = nbt.getInt("PatrolPosY");
            int z = nbt.getInt("PatrolPosZ");
            this.dataTracker.set(GUARD_POS, Optional.ofNullable(new BlockPos(x, y, z)));
        }
        NbtList listnbt = nbt.getList("Inventory", 10);
        for (int i = 0; i < listnbt.size(); ++i) {
            NbtCompound compoundnbt = listnbt.getCompound(i);
            int j = compoundnbt.getByte("Slot") & 255;
            this.guardInventory.setStack(j, ItemStack.fromNbt(compoundnbt));
        }
        /*
        if (nbt.contains("ArmorItems", 9)) {
            NbtList armorItems = nbt.getList("ArmorItems", 10);
            for (int i = 0; i < this.armorItems.size(); ++i) {
                int index = GuardEntity
                .slotToInventoryIndex(MobEntity.getEquipmentForSlot(ItemStack.fromNbt(armorItems.getCompound(i))));
                this.guardInventory.setItem(index, ItemStack.fromNbt(armorItems.getCompound(i)));
            }
        }

         */
        /*
        if (nbt.contains("HandItems", 9)) {
            NbtList handItems = nbt.getList("HandItems", 10);
            for (int i = 0; i < this.handItems.size(); ++i) {
                int handSlot = i == 0 ? 5 : 4;
                this.guardInventory.setStack(handSlot, ItemStack.fromNbt(handItems.getCompound(i)));
            }
        }

         */
        if (!world.isClient())
            this.readAngerFromNbt((ServerWorld) this.world, nbt);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Type", this.getGuardVariant());
        nbt.putInt("KickTicks", this.kickTicks);
        nbt.putInt("ShieldCooldown", this.shieldCoolDown);
        nbt.putInt("KickCooldown", this.kickCoolDown);
        nbt.putBoolean("Following", this.isFollowing());
        nbt.putBoolean("Interacting", this.interacting);
        nbt.putBoolean("Eating", this.isEating());
        nbt.putBoolean("Patrolling", this.isPatrolling());
        nbt.putBoolean("RunningToEat", this.isRunningToEat());
        if (this.getOwnerId() != null) {
            nbt.putUuid("Owner", this.getOwnerId());
        }
        NbtList listnbt = new NbtList();
        for (int i = 0; i < this.guardInventory.size(); ++i) {
            ItemStack itemstack = this.guardInventory.getStack(i);
            if (!itemstack.isEmpty()) {
                NbtCompound compoundnbt = new NbtCompound();
                compoundnbt.putByte("Slot", (byte) i);
                itemstack.writeNbt(compoundnbt);
                listnbt.add(compoundnbt);
            }
        }
        nbt.put("Inventory", listnbt);
        if (this.getPatrolPos() != null) {
            nbt.putInt("PatrolPosX", this.getPatrolPos().getX());
            nbt.putInt("PatrolPosY", this.getPatrolPos().getY());
            nbt.putInt("PatrolPosZ", this.getPatrolPos().getZ());
        }
        this.readAngerFromNbt((ServerWorld) this.world, nbt);
    }

    public void setOwnerId(@Nullable UUID p_184754_1_) {
        this.dataTracker.set(OWNER_UNIQUE_ID, Optional.ofNullable(p_184754_1_));
    }

    public void setFollowing(boolean following) {
        this.dataTracker.set(FOLLOWING, following);
    }

    public void setEating(boolean eating) {
        this.dataTracker.set(EATING, eating);
    }

    public void setPatrolling(boolean patrolling) {
        this.dataTracker.set(PATROLLING, patrolling);
    }

    public boolean isRunningToEat() {
        return this.dataTracker.get(RUNNING_TO_EAT);
    }

    public void setRunningToEat(boolean running) {
        this.dataTracker.set(RUNNING_TO_EAT, running);
    }

    public UUID getOwnerId() {
        return this.dataTracker.get(OWNER_UNIQUE_ID).orElse(null);
    }

    public int getGuardVariant() {
        return this.dataTracker.get(GUARD_VARIANT);
    }


    public int getKickTicks() {
        return this.kickTicks;
    }

    public void setKicking(boolean kicking) {
        this.dataTracker.set(KICKING, kicking);
    }

    public boolean isFollowing() {
        return this.dataTracker.get(FOLLOWING);
    }

    public boolean isEating() {
        return this.dataTracker.get(EATING);
    }

    public boolean isPatrolling() {
        return this.dataTracker.get(PATROLLING);
    }

        // TODO
    /*
    public ItemStack getPickedResult(HitResult target) {
        return new ItemStack(GuardItems.GUARD_SPAWN_EGG.get());
    }

     */

    @Nullable
    public LivingEntity getOwner() {
        try {
            UUID uuid = this.getOwnerId();
            return (uuid == null || uuid != null && this.world.getPlayerByUuid(uuid) != null
            && !this.world.getPlayerByUuid(uuid).hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)) ? null
            : this.world.getPlayerByUuid(uuid);
        } catch (IllegalArgumentException illegalargumentexception) {
            return null;
        }
    }

    @Override
    public boolean tryAttack(Entity target) {
        if (this.isKicking()) {
            ((LivingEntity) target).takeKnockback(1.0F, Math.sin(this.getYaw() * ((float) Math.PI / 180F)), (-Math.cos(this.getYaw() * ((float) Math.PI / 180F))));
            this.kickTicks = 10;
            this.world.sendEntityStatus(this, (byte) 4);
            this.lookAtEntity(target, 90.0F, 90.0F);
        }
        ItemStack hand = this.getMainHandStack();
        hand.damage(1, this, (entity) -> entity.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        return super.tryAttack(target);
    }

    public boolean isKicking() {
        return this.dataTracker.get(KICKING);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new KickGoal(this));
        this.goalSelector.add(0, new GuardEatFoodGoal(this));
        this.goalSelector.add(0, new RaiseShieldGoal(this));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        this.goalSelector.add(1, new GuardSetRunningToEatGoal(this, 1.0D));
        this.goalSelector.add(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
        this.goalSelector.add(2, new RangedBowAttackPassiveGoal<>(this, 0.5D, 20, 15.0F));
        this.goalSelector.add(2, new GuardEntity.GuardMeleeGoal(this, 0.8D, true));
        this.goalSelector.add(3, new GuardEntity.FollowHeroGoal(this));
        if (GuardVillagersConfig.get().GuardsRunFromPolarBears)
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        this.goalSelector.add(3, new WanderAroundPointOfInterestGoal(this, 0.5D, false));
        this.goalSelector.add(3, new IronGolemWanderAroundGoal(this, 0.5D));
        this.goalSelector.add(3, new MoveThroughVillageGoal(this, 0.5D, false, 4, () -> false));
        if (GuardVillagersConfig.get().GuardsOpenDoors)
            this.goalSelector.add(3, new LongDoorInteractGoal(this, true) {
                @Override
                public void start() {
                    this.mob.swingHand(Hand.MAIN_HAND);
                    super.start();
                }
            });
        if (GuardVillagersConfig.get().GuardFormation)
            this.goalSelector.add(5, new FollowShieldGuards(this)); // phalanx
        if (GuardVillagersConfig.get().ClericHealing)
            this.goalSelector.add(6, new RunToClericGoal(this));
        if (GuardVillagersConfig.get().armorerRepairGuardArmor)
            this.goalSelector.add(6, new ArmorerRepairGuardArmorGoal(this));
        this.goalSelector.add(4, new WalkBackToCheckPointGoal(this, 0.5D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, MerchantEntity.class, 8.0F));
        this.goalSelector.add(8, new WanderAroundGoal(this, 0.5D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.targetSelector.add(5, new GuardEntity.DefendVillageGuardGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, RavagerEntity.class, true));
        this.targetSelector.add(2, (new RevengeGoal(this, GuardEntity.class, IronGolemEntity.class)).setGroupRevenge());
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, WitchEntity.class, true));
        this.targetSelector.add(3, new HeroHurtByTargetGoal(this));
        this.targetSelector.add(3, new HeroHurtTargetGoal(this));
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, IllagerEntity.class, true));
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, RaiderEntity.class, true));
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, IllusionerEntity.class, true));
        if (GuardVillagersConfig.get().AttackAllMobs) {
            this.targetSelector.add(3, new ActiveTargetGoal<>(this, MobEntity.class, 5, true, true, (mob) -> {
                return mob instanceof Monster && !GuardVillagersConfig.get().MobBlackList.contains(mob.getEntityName());
            }));
        }
        this.targetSelector.add(3,
        new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false, this::shouldAngerAt));
        this.targetSelector.add(4, new ActiveTargetGoal<>(this, ZombieEntity.class, true));
        this.targetSelector.add(4, new UniversalAngerGoal<>(this, false));
    }

    @Override
    public void onDeath(DamageSource source) {
        if ((this.world.getDifficulty() == Difficulty.NORMAL || this.world.getDifficulty() == Difficulty.HARD)
        && source.getSource() instanceof ZombieEntity) {
            if (this.world.getDifficulty() != Difficulty.HARD && this.random.nextBoolean()) {
                return;
            }
            ZombieVillagerEntity zombieguard = this.convertTo(EntityType.ZOMBIE_VILLAGER, true);
            zombieguard.initialize((ServerWorldAccess) this.world,
            this.world.getLocalDifficulty(zombieguard.getBlockPos()), SpawnReason.CONVERSION,
            new ZombieEntity.ZombieData(false, true), (NbtCompound) null);
            if (!this.isSilent())
                this.world.syncWorldEvent((PlayerEntity) null, 1026, this.getBlockPos(), 0);
            this.discard();
        }
        super.onDeath(source);
    }

    @Override
    protected void consumeItem() {
        Hand interactionhand = this.getActiveHand();
        if (!this.activeItemStack.equals(this.getStackInHand(interactionhand))) {
            this.stopUsingItem();
        } else {
            if (!this.activeItemStack.isEmpty() && this.isUsingItem()) {
                this.spawnConsumptionEffects(this.activeItemStack, 16);
                ItemStack copy = this.activeItemStack.copy();
                ItemStack itemstack = net.minecraftforge.event.ForgeEventFactory.onItemUseFinish(this, copy,
                getItemUseTimeLeft(), this.activeItemStack.finishUsing(this.world, this));
                if (itemstack != this.activeItemStack) {
                    this.setStackInHand(interactionhand, itemstack);
                }
                if (!this.activeItemStack.isFood())
                    this.activeItemStack.decrement(1);
                this.stopUsingItem();
            }
        }
    }

    @Override
    public ItemStack eatFood(World world, ItemStack stack) {
        if (stack.isFood()) {
            this.heal(stack.getItem().getFoodComponent().getHunger());
        }
        super.eatFood(world, stack);
        world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5F,
        world.random.nextFloat() * 0.1F + 0.9F);
        this.setEating(false);
        return stack;
    }

    @Override
    public void tickMovement() {
        if (this.kickTicks > 0) {
            --this.kickTicks;
        }
        if (this.kickCoolDown > 0) {
            --this.kickCoolDown;
        }
        if (this.shieldCoolDown > 0) {
            --this.shieldCoolDown;
        }
        if (this.getHealth() < this.getMaxHealth() && this.age % 200 == 0) {
            this.heal(GuardVillagersConfig.get().amountOfHealthRegenerated);
        }
        if (!this.world.isClient())
            this.tickAngerLogic((ServerWorld) this.world, true);
        this.tickHandSwing();
        super.tickMovement();
    }

    @Override
    public EntityDimensions getDimensions(EntityPose poseIn) {
        return SIZE_BY_POSE.getOrDefault(poseIn, EntityDimensions.changing(0.6F, 1.95F));
    }

    @Override
    public float getActiveEyeHeight(EntityPose poseIn, EntityDimensions sizeIn) {
        if (poseIn == EntityPose.CROUCHING) {
            return 1.40F;
        }
        return super.getActiveEyeHeight(poseIn, sizeIn);
    }

    @Override
    protected void takeShieldHit(LivingEntity entityIn) {
        super.takeShieldHit(entityIn);
        if (entityIn.getMainHandStack().canDisableShield(this.activeItemStack, this, entityIn))
            this.disableShield(true);
    }

    @Override
    protected void damageShield(float damage) {
        if (this.activeItemStack.canPerformAction(net.minecraftforge.common.ToolActions.SHIELD_BLOCK)) {
            if (damage >= 3.0F) {
                int i = 1 + MathHelper.floor(damage);
                Hand hand = this.getActiveHand();
                this.activeItemStack.damage(i, this, (entity) -> entity.sendToolBreakStatus(hand));
                if (this.activeItemStack.isEmpty()) {
                    if (hand == Hand.MAIN_HAND) {
                        this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    } else {
                        this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                    }
                    this.activeItemStack = ItemStack.EMPTY;
                    this.playSound(SoundEvents.ITEM_SHIELD_BREAK, 0.8F, 0.8F + this.world.random.nextFloat() * 0.4F);
                }
            }
        }
    }

    @Override
    public void setCurrentHand(Hand hand) {
        ItemStack itemstack = this.getStackInHand(hand);
        if (itemstack.canPerformAction(net.minecraftforge.common.ToolActions.SHIELD_BLOCK)) {
            EntityAttributeInstance modifiableattributeinstance = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            modifiableattributeinstance.removeModifier(USE_ITEM_SPEED_PENALTY);
            modifiableattributeinstance.addTemporaryModifier(USE_ITEM_SPEED_PENALTY);
        }
        super.setCurrentHand(hand);
    }

    @Override
    public void stopUsingItem() {
        if (this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).hasModifier(USE_ITEM_SPEED_PENALTY))
            this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).removeModifier(USE_ITEM_SPEED_PENALTY);
        super.stopUsingItem();
    }

    public void disableShield(boolean increase) {
        float chance = 0.25F + (float) EnchantmentHelper.getEfficiency(this) * 0.05F;
        if (increase)
            chance += 0.75;
        if (this.random.nextFloat() < chance) {
            this.shieldCoolDown = 100;
            this.stopUsingItem();
            this.world.sendEntityStatus(this, (byte) 30);
        }
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(GUARD_VARIANT, 0);
        this.dataTracker.startTracking(DATA_CHARGING_STATE, false);
        this.dataTracker.startTracking(KICKING, false);
        this.dataTracker.startTracking(OWNER_UNIQUE_ID, Optional.empty());
        this.dataTracker.startTracking(EATING, false);
        this.dataTracker.startTracking(FOLLOWING, false);
        this.dataTracker.startTracking(GUARD_POS, Optional.empty());
        this.dataTracker.startTracking(PATROLLING, false);
        this.dataTracker.startTracking(RUNNING_TO_EAT, false);
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
        super.equipStack(slot, stack);
        switch (slot) {
            case CHEST:
                if (this.guardInventory.getStack(1).isEmpty())
                    //this.guardInventory.setStack(1, this.getEquippedStack(slot.getEntitySlotId()));//TODO
                break;
            case FEET:
                if (this.guardInventory.getStack(3).isEmpty())
                    //this.guardInventory.setStack(3, this.getArmorItems().iterator().next());
                break;
            case HEAD:
                if (this.guardInventory.getStack(0).isEmpty())
                    //this.guardInventory.setStack(0, this.getArmorItems().get(slotIn.getIndex()));
                break;
            case LEGS:
                if (this.guardInventory.getStack(2).isEmpty())
                    //this.guardInventory.setStack(2, this.armorItems.get(slotIn.getIndex()));
                break;
            case MAINHAND:
                if (this.guardInventory.getStack(5).isEmpty())
                    //this.guardInventory.setStack(5, this.handItems.get(slotIn.getIndex()));
                break;
            case OFFHAND:
                if (this.guardInventory.getStack(4).isEmpty())
                    //this.guardInventory.setStack(4, this.handItems.get(slotIn.getIndex()));
                break;
        }
    }

    public static int getRandomTypeForBiome(ServerWorldAccess world, BlockPos pos) {
        VillagerType type = VillagerType.forBiome(world.getBiomeKey(pos));
        if (type == VillagerType.SNOW)
            return 6;
        else if (type == VillagerType.TAIGA)
            return 5;
        else if (type == VillagerType.JUNGLE)
            return 4;
        else if (type == VillagerType.SWAMP)
            return 3;
        else if (type == VillagerType.SAVANNA)
            return 2;
        if (type == VillagerType.DESERT)
            return 1;
        else return 0;
    }

    @Override
    public boolean canBeLeashedBy(PlayerEntity player) {
        return false;
    }

    public List<ItemStack> getItemsFromLootTable(EquipmentSlot slot) {
        if (EQUIPMENT_SLOT_ITEMS.containsKey(slot)) {
            LootTable loot = this.world.getServer().getLootManager().getTable(EQUIPMENT_SLOT_ITEMS.get(slot));
            LootContext.Builder lootcontext$builder = (new LootContext.Builder((ServerWorld) this.world)).parameter(LootContextParameters.THIS_ENTITY, this).random(this.getRandom());
            return loot.generateLoot(lootcontext$builder.build(GuardLootTables.SLOT));
        }
        return null;
    }

    @Override
    public void setCharging(boolean charging) {

    }

    @Override
    public void shoot(LivingEntity target, ItemStack crossbow, ProjectileEntity projectile, float multiShotSpray) {
        this.shoot(this, target, projectile, multiShotSpray, 1.6F);
    }

    @Override
    public void setTarget(LivingEntity entity) {
        if (entity instanceof GuardEntity || entity instanceof VillagerEntity || entity instanceof IronGolemEntity)
            return;
        super.setTarget(entity);
    }


    @Override
    protected void knockback(LivingEntity entityIn) {
        if (this.isKicking()) {
            this.setKicking(false);
        }
        super.knockback(this);
    }



    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        boolean configValues = !GuardVillagersConfig.get().giveGuardStuffHOTV || !GuardVillagersConfig.get().setGuardPatrolHotv
        || player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.get().giveGuardStuffHOTV
        || player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.get().setGuardPatrolHotv
        || player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.get().giveGuardStuffHOTV
        && GuardVillagersConfig.get().setGuardPatrolHotv;
        boolean inventoryRequirements = !player.shouldCancelInteraction() && this.onGround;
        if (configValues && inventoryRequirements) {
            if (this.getTarget() != player && this.canMoveVoluntarily()) {
                if (player instanceof ServerPlayerEntity) {
                    this.openGui((ServerPlayerEntity) player);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.CONSUME;
        }
        return super.interactMob(player, hand);
    }

    public void openGui(ServerPlayerEntity player) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            player.closeScreenHandler();
        }
        this.interacting = true;
        ((ServerPlayerEntityAccessor) player).incrementScreenHandlerSyncId();
        GuardPacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new GuardOpenInventoryPacket(
        ((ServerPlayerEntityAccessor) player).screenHandlerSyncId(), this.guardInventory.size(), this.getId()));
        player.currentScreenHandler = new GuardContainer(((ServerPlayerEntityAccessor) player).screenHandlerSyncId(), player.getInventory(), this.guardInventory,
        this);
        ((ServerPlayerEntityAccessor) player).onScreenHandlerOpened(player.currentScreenHandler);
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, player.containerMenu));
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 20).add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.5)
        .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0D).add(EntityAttributes.GENERIC_FOLLOW_RANGE, 20);
    }

    public static Hand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getActiveItem().getItem()) ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    @Override
    public void attack(LivingEntity target, float pullProgress) {
        this.shieldCoolDown = 8;
        if (this.getActiveItem().getItem() instanceof CrossbowItem)
            this.shoot(this, 6.0F);
        if (this.getActiveItem().getItem() instanceof BowItem) {
            ItemStack itemstack = this.getArrowType(this.getStackInHand(getHandWith(this, item -> item instanceof BowItem)));
            ItemStack hand = this.getActiveItem();
            PersistentProjectileEntity abstractarrowentity = ProjectileUtil.createArrowProjectile(this, itemstack, pullProgress);
            abstractarrowentity = ((net.minecraft.item.BowItem) this.getActiveItem().getItem()).createArrow(abstractarrowentity);
            int powerLevel = EnchantmentHelper.getLevel(Enchantments.POWER, itemstack);
            if (powerLevel > 0)
                abstractarrowentity
                .setDamage(abstractarrowentity.getDamage() + (double) powerLevel * 0.5D + 0.5D);
            int punchLevel = EnchantmentHelper.getLevel(Enchantments.PUNCH, itemstack);
            if (punchLevel > 0)
                abstractarrowentity.setPunch(punchLevel);
            if (EnchantmentHelper.getLevel(Enchantments.FLAME, itemstack) > 0)
                abstractarrowentity.setFireTicks(100);
            double d0 = target.getX() - this.getX();
            double d1 = target.getY()*1/3 - abstractarrowentity.getY();
            double d2 = target.getZ() - this.getZ();
            double d3 = MathHelper.sqrt((float) (d0 * d0 + d2 * d2));
            abstractarrowentity.setVelocity(d0, d1 + d3 * (double) 0.2F, d2, 1.6F,
            (float) (14 - this.world.getDifficulty().getId() * 4));
            this.playSound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
            this.world.spawnEntity(abstractarrowentity);
            hand.damage(1, this, (entity) -> entity.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        }
    }

    @Override
    public ItemStack getArrowType(ItemStack shootable) {
        if (shootable.getItem() instanceof RangedWeaponItem) {
            Predicate<ItemStack> predicate = ((RangedWeaponItem) shootable.getItem()).getHeldProjectiles();
            ItemStack itemstack = RangedWeaponItem.getHeldProjectile(this, predicate);
            return itemstack.isEmpty() ? new ItemStack(Items.ARROW) : itemstack;
        } else {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean canTarget(LivingEntity target) {
        return !GuardVillagersConfig.get().MobBlackList.contains(target.getEntityName())
        && !target.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && !this.isOwner(target)
        && !(target instanceof VillagerEntity) && !(target instanceof IronGolemEntity) && !(target instanceof GuardEntity)
        && super.canTarget(target);
    }

    public boolean isOwner(LivingEntity entityIn) {
        return entityIn == this.getOwner();
    }


    @Override
    public void tickRiding() {
        super.tickRiding();
        if (this.getVehicle() instanceof PathAwareEntity) {
            PathAwareEntity creatureentity = (PathAwareEntity) this.getVehicle();
            this.bodyYaw = creatureentity.bodyYaw;
        }
    }

    @Override
    public void postShoot() {
        this.despawnCounter = 0;
    }


    @Override
    public int getAngerTime() {
        return 0;
    }

    @Override
    public void setAngerTime(int ticks) {

    }

    @Nullable
    @Override
    public UUID getAngryAt() {
        return null;
    }

    @Override
    public void setAngryAt(@Nullable UUID uuid) {

    }

    @Override
    public void chooseRandomAngerTime() {

    }

    @Override
    public void onInventoryChanged(Inventory sender) {

    }

    public void setGuardVariant(int typeId) {
        this.dataTracker.set(GUARD_VARIANT, typeId);
    }

    public static class GuardData implements EntityData {
        public final int variantData;

        public GuardData(int type) {
            this.variantData = type;
        }
    }

    public static class DefendVillageGuardGoal extends TrackTargetGoal {
        private final GuardEntity guard;
        private LivingEntity villageAggressorTarget;

        public DefendVillageGuardGoal(GuardEntity guardIn) {
            super(guardIn, false, true);
            this.guard = guardIn;
            this.setControls(EnumSet.of(Goal.Control.TARGET, Goal.Control.MOVE));
        }

        @Override
        public boolean canStart() {
            Box axisalignedbb = this.guard.getBoundingBox().expand(10.0D, 8.0D, 10.0D);
            List<VillagerEntity> list = guard.world.getNonSpectatingEntities(VillagerEntity.class, axisalignedbb);
            List<PlayerEntity> list1 = guard.world.getNonSpectatingEntities(PlayerEntity.class, axisalignedbb);
            for (LivingEntity livingentity : list) {
                VillagerEntity villagerentity = (VillagerEntity) livingentity;
                for (PlayerEntity playerentity : list1) {
                    int i = villagerentity.getReputation(playerentity);
                    if (i <= -100) {
                        this.villageAggressorTarget = playerentity;
                    }
                }
            }
            return villageAggressorTarget != null && !villageAggressorTarget.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)
            && !this.villageAggressorTarget.isSpectator()
            && !((PlayerEntity) this.villageAggressorTarget).isCreative();
        }

        @Override
        public void start() {
            this.guard.setTarget(this.villageAggressorTarget);
            super.start();
        }
    }

    public static class FollowHeroGoal extends Goal {
        public final GuardEntity guard;

        public FollowHeroGoal(GuardEntity mob) {
            guard = mob;
            this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
        }

        @Override
        public void start() {
            super.start();
            if (guard.getOwner() != null) {
                guard.getNavigation().startMovingTo(guard.getOwner(), 0.9D);
            }
        }

        @Override
        public void tick() {
            if (guard.getOwner() != null) {
                guard.getNavigation().startMovingTo(guard.getOwner(), 0.9D);
            }
        }

        @Override
        public boolean shouldContinue() {
            return guard.isFollowing() && this.canStart();
        }

        @Override
        public boolean canStart() {
            List<PlayerEntity> list = this.guard.world.getNonSpectatingEntities(PlayerEntity.class, this.guard.getBoundingBox().expand(10.0D));
            if (!list.isEmpty()) {
                for (LivingEntity mob : list) {
                    PlayerEntity player = (PlayerEntity) mob;
                    if (!player.isInvisible() && player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)) {
                        guard.setOwnerId(player.getUuid());
                        return guard.isFollowing();
                    }
                }
            }
            return false;
        }

        @Override
        public void stop() {
            this.guard.getNavigation().stop();
            if (guard.getOwner() != null && !guard.getOwner().hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)) {
                guard.setOwnerId(null);
                guard.setFollowing(false);
            }
        }
    }

    public class GuardMeleeGoal extends MeleeAttackGoal {
        public final GuardEntity guard;

        public GuardMeleeGoal(GuardEntity guard, double speedIn, boolean useLongMemory) {
            super(guard, speedIn, useLongMemory);
            this.guard = guard;
        }

        @Override
        public boolean canStart() {
            return !(this.guard.getMainHandStack().getItem() instanceof CrossbowItem) && this.guard.getTarget() != null
            && !this.guard.isEating() && super.canStart();
        }

        @Override
        public boolean shouldContinue() {
            return super.shouldContinue() && this.guard.getTarget() != null
            && !(this.guard.getMainHandStack().getItem() instanceof CrossbowItem);
        }

        @Override
        public void tick() {
            LivingEntity target = guard.getTarget();
            if (target != null) {
                if (target.distanceTo(guard) <= 3.0D && !guard.isBlocking()) {
                    guard.getMoveControl().strafeTo(-2.0F, 0.0F);
                    guard.lookAtEntity(target, 30.0F, 30.0F);
                }
                if (((MeleeAttackGoalAccessor)this).path() != null && target.distanceTo(guard) <= 2.0D)
                    guard.getNavigation().stop();
                super.tick();
            }
        }

        @Override
        protected double getSquaredMaxAttackDistance(LivingEntity attackTarget) {
            return super.getSquaredMaxAttackDistance(attackTarget) * 3.55D;
        }

        @Override
        protected void attack(LivingEntity enemy, double distToEnemySqr) {
            double d0 = this.getSquaredMaxAttackDistance(enemy);
            if (distToEnemySqr <= d0 && this.getCooldown() <= 0) {
                this.resetCooldown();
                this.guard.stopUsingItem();
                if (guard.shieldCoolDown == 0)
                    this.guard.shieldCoolDown = 8;
                this.guard.swingHand(Hand.MAIN_HAND);
                this.guard.tryAttack(enemy);
            }
        }
    }
}
