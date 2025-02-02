package net.illuc.kontraption

import mekanism.client.ClientRegistrationUtil
import mekanism.common.Mekanism
import mekanism.common.base.IModModule
import mekanism.common.config.MekanismModConfig
import mekanism.common.inventory.container.tile.MekanismTileContainer
import mekanism.common.lib.Version
import mekanism.common.lib.multiblock.MultiblockCache
import mekanism.common.lib.multiblock.MultiblockManager
import net.illuc.kontraption.ClientEvents.ClientRuntimeEvents
import net.illuc.kontraption.KontraptionParticleTypes.MUZZLE_FLASH
import net.illuc.kontraption.KontraptionParticleTypes.THRUSTER
import net.illuc.kontraption.blockEntities.TileEntityCannon
import net.illuc.kontraption.client.KontraptionClientTickHandler
import net.illuc.kontraption.client.MuzzleFlashParticle
import net.illuc.kontraption.client.ThrusterParticle
import net.illuc.kontraption.client.gui.GuiGun
import net.illuc.kontraption.command.CommandKontraption
import net.illuc.kontraption.config.KontraptionConfigs
import net.illuc.kontraption.config.KontraptionKeyBindings
import net.illuc.kontraption.entity.KontraptionShipMountingEntity
import net.illuc.kontraption.multiblocks.largeHydrogenThruster.LiquidFuelThrusterMultiblockData
import net.illuc.kontraption.multiblocks.largeHydrogenThruster.LiquidFuelThrusterValidator
import net.illuc.kontraption.multiblocks.railgun.RailgunMultiblockData
import net.illuc.kontraption.multiblocks.railgun.RailgunValidator
import net.illuc.kontraption.network.KontraptionPacketHandler
import net.illuc.kontraption.util.BlockDamageManager
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.SpriteSet
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.CreativeModeTab
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.RegisterParticleProvidersEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegisterEvent
import net.minecraftforge.registries.RegistryObject
import net.minecraftforge.versions.forge.ForgeVersion.MOD_ID
import org.valkyrienskies.mod.client.EmptyRenderer
import thedarkcolour.kotlinforforge.forge.MOD_BUS

/**
 * Main mod class. Should be an `object` declaration annotated with `@Mod`.
 * The modid should be declared in this object and should match the modId entry
 * in mods.toml.
 *
 * An example for blocks is in the `blocks` package of this mod.
 */

@Mod(Kontraption.MODID)
class Kontraption : IModModule {
    /**
     * Kontraption version number
     */
    val versionNumber: Version

    /**
     * Kontraption Packet Pipeline
     */
    private val packetHandler: KontraptionPacketHandler

    private val KONTRAPTION_SHIP_MOUNTING_ENTITY_REGISTRY: RegistryObject<EntityType<KontraptionShipMountingEntity>>
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Kontraption.MODID)
    val TAB_REGISTER: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)

    // = TODO()

    init {
        instance = this
        // MekanismGeneratorsConfig.registerConfigs(ModLoadingContext.get())
        val modEventBus = MOD_BUS
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands)
        KontraptionConfigs.registerConfigs(ModLoadingContext.get())
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(::registerKeyBindings)
        }
        modEventBus.addListener(this::commonSetup)
        modEventBus.addListener(this::onConfigLoad)
        modEventBus.addListener(this::imcQueue)
        KontraptionItems.ITEMS.register(modEventBus)
        KontraptionBlocks.BLOCKS.register(modEventBus)
        ENTITIES.register(modEventBus)
        KontraptionParticleTypes.PARTICLE_TYPES.register(modEventBus)
        // GeneratorsFluids.FLUIDS.register(modEventBus)
        // GeneratorsSounds.SOUND_EVENTS.register(modEventBus)
        KontraptionContainerTypes.CONTAINER_TYPES.register(modEventBus)
        KontraptionTileEntityTypes.TILE_ENTITY_TYPES.register(modEventBus)
        KontraptionSounds.SOUND_EVENTS.register(modEventBus)
        // GeneratorsGases.GASES.register(modEventBus)
        // GeneratorsModules.MODULES.register(modEventBus)
        // Set our version number to match the mods.toml file, which matches the one in our build.gradle
        versionNumber = Version(ModLoadingContext.get().activeContainer)
        packetHandler = KontraptionPacketHandler()

        KONTRAPTION_SHIP_MOUNTING_ENTITY_REGISTRY =
            ENTITIES.register("kontraption_ship_mounting_entity") {
                EntityType.Builder.of(
                    ::KontraptionShipMountingEntity,
                    MobCategory.MISC,
                ).sized(.3f, .3f)
                    .build(ResourceLocation(Kontraption.MODID, "kontraption_ship_mounting_entity").toString())
            }

        modEventBus.addListener(::clientSetup)
        modEventBus.addListener(::entityRenderers)
        MinecraftForge.EVENT_BUS.addListener(::levelLoad)
        modEventBus.addListener(::loadComplete)

        TAB_REGISTER.register("general", ::createCreativeTab)
        TAB_REGISTER.register(modEventBus)
    }

    private fun commonSetup(event: FMLCommonSetupEvent) {
        // 1mB hydrogen + 2*bioFuel/tick*200ticks/100mB * 20x efficiency bonus
        /*MekanismGases.ETHENE.get().addAttribute(Fuel(MekanismConfig.general.ETHENE_BURN_TIME,
                FloatingLongSupplier {
                    MekanismConfig.general.FROM_H2.get().add(MekanismGeneratorsConfig.generators.bioGeneration.get()
                            .multiply(2L * MekanismConfig.general.ETHENE_BURN_TIME.get()))
                }))*/
        event.enqueueWork {
            KontraptionTags.init()

            // Ensure our tags are all initialized
            // GeneratorTags.init()
            // Register dispenser behaviors
            // GeneratorsFluids.FLUIDS.registerBucketDispenserBehavior()
            // Register extended build commands (in enqueue as it is not thread safe)
            // BuildCommand.register("turbine", GeneratorsLang.TURBINE, TurbineBuilder())
            // BuildCommand.register("fission", GeneratorsLang.FISSION_REACTOR, FissionReactorBuilder())
            // BuildCommand.register("fusion", GeneratorsLang.FUSION_REACTOR, FusionReactorBuilder())
        }
        packetHandler.initialize()

        // Finalization
        Mekanism.logger.info("Loaded 'Kontraption' module.")
    }

    private fun imcQueue(event: InterModEnqueueEvent) {
        // MekanismIMC.addMekaSuitHelmetModules(GeneratorsModules.SOLAR_RECHARGING_UNIT)
        // MekanismIMC.addMekaSuitPantsModules(GeneratorsModules.GEOTHERMAL_GENERATOR_UNIT)
    }

    override fun getVersion(): Version {
        return versionNumber
    }

    override fun getName(): String {
        return "Kontraption"
    }

    override fun resetClient() {
        // TurbineMultiblockData.clientRotationMap.clear()
    }

    private fun loadComplete(event: FMLLoadCompleteEvent) {
        KONTRAPTION_SHIP_MOUNTING_ENTITY_TYPE = KONTRAPTION_SHIP_MOUNTING_ENTITY_REGISTRY.get()
    }

    private fun entityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(KONTRAPTION_SHIP_MOUNTING_ENTITY_REGISTRY.get(), ::EmptyRenderer)
        // event.registerEntityRenderer(PHYSICS_ENTITY_TYPE_REGISTRY.get(), ::VSPhysicsEntityRenderer)
    }

    fun levelLoad(event: LevelEvent.Load) {
        blockDamageManager.levelLoaded(event.level)
        // event.registerEntityRenderer(PHYSICS_ENTITY_TYPE_REGISTRY.get(), ::VSPhysicsEntityRenderer)
    }

    private fun clientSetup(event: FMLClientSetupEvent) {
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.addListener(ClientRuntimeEvents::onRenderWorld)
    }

    private fun registerKeyBindings(event: RegisterKeyMappingsEvent) {
        KontraptionKeyBindings.clientSetup {
            event.register(it)
        }
    }

    private fun registerCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(CommandKontraption.register())
    }

    private fun onConfigLoad(configEvent: ModConfigEvent) {
        // Note: We listen to both the initial load and the reload, to make sure that we fix any accidentally
        // cached values from calls before the initial loading
        val config = configEvent.config
        // Make sure it is for the same modid as us
        if (config.modId == MODID && config is MekanismModConfig) {
            config.clearCache(configEvent)
        }
    }

    companion object {
        lateinit var KONTRAPTION_SHIP_MOUNTING_ENTITY_TYPE: EntityType<KontraptionShipMountingEntity>
        const val MODID = "kontraption"
        var instance: Kontraption? = null

        // val turbineManager: MultiblockManager<TurbineMultiblockData> = MultiblockManager<TurbineMultiblockData>("industrialTurbine", Supplier<MultiblockCache<TurbineMultiblockData>> { TurbineCache() }, Supplier<IStructureValidator<TurbineMultiblockData>> { TurbineValidator() })
        // val fissionReactorManager: MultiblockManager<FissionReactorMultiblockData> = MultiblockManager<FissionReactorMultiblockData>("fissionReactor", Supplier<MultiblockCache<FissionReactorMultiblockData>> { FissionReactorCache() }, Supplier<IStructureValidator<FissionReactorMultiblockData>> { FissionReactorValidator() })
        // val fusionReactorManager: MultiblockManager<FusionReactorMultiblockData> = MultiblockManager<FusionReactorMultiblockData>("fusionReactor", Supplier<MultiblockCache<FusionReactorMultiblockData>> { FusionReactorCache() }, Supplier<IStructureValidator<FusionReactorMultiblockData>> { FusionReactorValidator() })
        val hydrogenThrusterManager: MultiblockManager<LiquidFuelThrusterMultiblockData?> =
            MultiblockManager(
                "hydrogenThruster",
                { MultiblockCache<LiquidFuelThrusterMultiblockData?>() },
                { LiquidFuelThrusterValidator() },
            )
        val railgunManager: MultiblockManager<RailgunMultiblockData?> =
            MultiblockManager("railgun", { MultiblockCache<RailgunMultiblockData?>() }, { RailgunValidator() })

        val blockDamageManager: BlockDamageManager = BlockDamageManager()

        fun packetHandler(): KontraptionPacketHandler {
            return instance!!.packetHandler
        }

        fun rl(path: String?): ResourceLocation {
            return ResourceLocation(MODID, path)
        }
    }

    @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
    object ClientRegistryHandler {
        @SubscribeEvent
        fun onParticlesRegistry(e: RegisterParticleProvidersEvent?) {
            Minecraft.getInstance().particleEngine.register(THRUSTER.get()) { spriteSet: SpriteSet? -> ThrusterParticle.Factory(spriteSet) }
            Minecraft.getInstance().particleEngine.register(
                MUZZLE_FLASH.get(),
            ) { spriteSet: SpriteSet? -> MuzzleFlashParticle.Factory(spriteSet) }
            // Minecraft.getInstance().particleEngine.register(BULLET.get()) { spriteSet: SpriteSet? -> BulletParticle.Factory(spriteSet) }
        }

        @SubscribeEvent
        fun init(event: FMLClientSetupEvent?) {
            MinecraftForge.EVENT_BUS.register(KontraptionClientTickHandler())
        }

        /*@SubscribeEvent
        fun init(event: FMLClientSetupEvent?) {
            OverlayRegistry.registerOverlayAbove(ForgeIngameGui.HOTBAR_ELEMENT, "Toolgun UI", ToolgunUI())
        }*/

        @SubscribeEvent
        fun registerContainers(event: RegisterEvent) {
            event.register(Registries.MENU) { helper ->
                ClientRegistrationUtil.registerScreen(
                    KontraptionContainerTypes.CANNON,
                ) { mekanismTileContainer: MekanismTileContainer<TileEntityCannon>?, inventory: Inventory, component: Component ->
                    GuiGun(
                        mekanismTileContainer,
                        inventory,
                        component,
                    )
                }
            }
        }
    }

    fun createCreativeTab(): CreativeModeTab {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.kontraption"))
            .icon { KontraptionBlocks.ION_THRUSTER.asItem().defaultInstance }
            .displayItems { _, output ->
                output.accept(KontraptionItems.LIGHTWEIGHT_ALLOY)
                output.accept(KontraptionItems.TOOLGUN)
                // output.accept(KontraptionItems.ESTROGEN)
                // output.accept(KontraptionBlocks.RUBBER_BLOCK)
                output.accept(KontraptionBlocks.LIQUID_FUEL_THRUSTER_CASING)
                output.accept(KontraptionBlocks.LIQUID_FUEL_THRUSTER_VALVE)
                output.accept(KontraptionBlocks.LIQUID_FUEL_THRUSTER_EXHAUST)
                output.accept(KontraptionBlocks.ION_THRUSTER)
                output.accept(KontraptionBlocks.SHIP_CONTROL_INTERFACE)
                output.accept(KontraptionBlocks.CANNON)
                output.accept(KontraptionBlocks.GYRO)
                // output.accept(KontraptionBlocks.SERVO)
                // output.accept(KontraptionBlocks.WHEEL)
            }
            .build()
    }
}
