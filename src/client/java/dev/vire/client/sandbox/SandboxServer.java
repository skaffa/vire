package dev.vire.client.sandbox;

import com.google.common.base.Stopwatch;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.services.ServicesKeySet;
import com.mojang.serialization.Lifecycle;

import dev.vire.client.VireClient;

import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gizmos.GizmoCollector;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPreset;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Vire's private in-process server (PLAN.md §5): one void overworld, no network, no players. Built
 * like vanilla's {@code GameTestServer}, but it ticks at the normal rate and never exits the JVM.
 */
public final class SandboxServer extends MinecraftServer {
	/** Vanilla features minus experiments that change redstone or minecarts. */
	private static final FeatureFlagSet FEATURES = FeatureFlags.REGISTRY.allFlags()
			.subtract(FeatureFlagSet.of(FeatureFlags.REDSTONE_EXPERIMENTS, FeatureFlags.MINECART_IMPROVEMENTS));
	private static final Services NO_SERVICES = new Services(null, ServicesKeySet.EMPTY, null, new OfflineNames(), new NoProfiles());

	private final LocalSampleLogger sampleLogger = new LocalSampleLogger(4);
	/** How long registry/data pack loading took, for the spike report. */
	public final long resourceLoadMillis;

	/**
	 * Starts a sandbox server on its own thread. The world lives in {@code dir} (wiped first). Returns
	 * at once; poll {@link #isReady()}.
	 */
	public static SandboxServer start(Path dir) throws IOException {
		wipe(dir);
		Files.createDirectories(dir);
		LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(dir).createAccess("world");
		PackRepository packs = ServerPacksSource.createPackRepository(access);
		return MinecraftServer.spin(thread -> create(thread, access, packs));
	}

	private static SandboxServer create(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs) {
		packs.reload();
		WorldDataConfiguration dataConfig = new WorldDataConfiguration(new DataPackConfig(List.of("vanilla"), List.of()), FEATURES);
		LevelSettings settings = new LevelSettings("Vire sandbox", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, dataConfig);
		WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, dataConfig, false, true);
		WorldLoader.InitConfig init = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.OWNER);
		Stopwatch sw = Stopwatch.createStarted();
		try {
			WorldStem stem = Util.<WorldStem>blockUntilDone(executor -> WorldLoader.load(init, context -> {
				Registry<LevelStem> noDatapackDimensions = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
				HolderGetter<DimensionType> types = context.datapackWorldRegistries().lookupOrThrow(Registries.DIMENSION_TYPE);
				HolderGetter<FlatLevelGeneratorPreset> presets = context.datapackWorldRegistries().lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET);
				LevelStem overworld = new LevelStem(types.getOrThrow(BuiltinDimensionTypes.OVERWORLD),
						new FlatLevelSource(presets.getOrThrow(FlatLevelGeneratorPresets.THE_VOID).value().settings()));
				Map<ResourceKey<LevelStem>, LevelStem> only = Map.of(LevelStem.OVERWORLD, overworld);
				WorldDimensions dims = new WorldDimensions(only);
				WorldDimensions.Complete complete = dims.bake(noDatapackDimensions);
				PrimaryLevelData data = new PrimaryLevelData(settings, complete.specialWorldProperty(), complete.lifecycle());
				return new WorldLoader.DataLoadOutput<>(
						new LevelDataAndDimensions.WorldDataAndGenSettings(data, new WorldGenSettings(new WorldOptions(0L, false, false), dims)),
						complete.dimensionsRegistryAccess());
			}, WorldStem::new, Util.backgroundExecutor(), executor)).get();
			sw.stop();
			return new SandboxServer(thread, access, packs, stem, sw.elapsed(TimeUnit.MILLISECONDS));
		} catch (Exception e) {
			throw new IllegalStateException("Vire sandbox: could not load the vanilla data pack", e);
		}
	}

	private static GameRules rules() {
		GameRules r = new GameRules(FEATURES);
		r.set(GameRules.ADVANCE_TIME, false, null);
		r.set(GameRules.ADVANCE_WEATHER, false, null);
		r.set(GameRules.SPAWN_MOBS, false, null);
		r.set(GameRules.SPAWN_MONSTERS, false, null);
		r.set(GameRules.SPAWN_PHANTOMS, false, null);
		r.set(GameRules.SPAWN_PATROLS, false, null);
		r.set(GameRules.SPAWN_WANDERING_TRADERS, false, null);
		r.set(GameRules.BLOCK_DROPS, false, null);
		r.set(GameRules.ENTITY_DROPS, false, null);
		r.set(GameRules.RANDOM_TICK_SPEED, VireClient.config.randomTickSpeed, null);
		return r;
	}

	private SandboxServer(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs, WorldStem stem, long loadMillis) {
		super(thread, access, packs, stem, Optional.of(rules()), Proxy.NO_PROXY, DataFixers.getDataFixer(), NO_SERVICES,
				LoggingLevelLoadListener.forDedicatedServer(), false, new NotificationManager());
		this.resourceLoadMillis = loadMillis;
		thread.setName("Vire sandbox");
	}

	@Override
	protected boolean initServer() {
		setPlayerList(new PlayerList(this, registries(), playerDataStorage, new EmptyNotificationService()) {
		});
		// gizmos are per thread; the sandbox draws none
		Gizmos.withCollector(GizmoCollector.NOOP);
		loadLevel();
		VireClient.LOG.info("[Vire] Sandbox server started");
		return true;
	}

	/**
	 * The sandbox world is scratch (wiped on every start), so it is never saved. Saving a void world's
	 * chunks took ~12 s on shutdown in the spike; this also skips the periodic autosave.
	 */
	@Override
	public boolean saveAllChunks(boolean silent, boolean flush, boolean force) {
		return true;
	}

	@Override
	protected void onServerCrash(CrashReport report) {
		super.onServerCrash(report);
		VireClient.LOG.error("[Vire] Sandbox server crashed\n{}", report.getFriendlyReport(ReportType.CRASH));
	}

	@Override
	protected SampleLogger getTickTimeLogger() {
		return sampleLogger;
	}

	@Override
	public boolean isTickTimeLoggingEnabled() {
		return false;
	}

	@Override
	public SystemReport fillServerSystemReport(SystemReport report) {
		report.setDetail("Type", "Vire sandbox server");
		return report;
	}

	@Override
	public boolean isHardcore() {
		return false;
	}

	@Override
	public LevelBasedPermissionSet operatorUserPermissions() {
		return LevelBasedPermissionSet.ALL;
	}

	@Override
	public PermissionSet getFunctionCompilationPermissions() {
		return LevelBasedPermissionSet.OWNER;
	}

	@Override
	public boolean shouldRconBroadcast() {
		return false;
	}

	@Override
	public boolean isDedicatedServer() {
		return false;
	}

	@Override
	public int getRateLimitPacketsPerSecond() {
		return 0;
	}

	@Override
	public int getCommandSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public int getChatSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public boolean useNativeTransport() {
		return false;
	}

	@Override
	public boolean isPublished() {
		return false;
	}

	@Override
	public boolean shouldInformAdmins() {
		return false;
	}

	@Override
	public boolean isSingleplayerOwner(NameAndId nameAndId) {
		return false;
	}

	@Override
	public int getMaxPlayers() {
		return 0;
	}

	/** Deletes the scratch world folder. */
	public static void wipe(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static final class NoProfiles implements ProfileResolver {
		@Override
		public Optional<GameProfile> fetchByName(String name) {
			return Optional.empty();
		}

		@Override
		public Optional<GameProfile> fetchById(UUID id) {
			return Optional.empty();
		}
	}

	private static final class OfflineNames implements UserNameToIdResolver {
		@Override
		public void add(NameAndId nameAndId) {
		}

		@Override
		public Optional<NameAndId> get(String name) {
			return Optional.of(NameAndId.createOffline(name));
		}

		@Override
		public Optional<NameAndId> get(UUID id) {
			return Optional.empty();
		}

		@Override
		public void resolveOfflineUsers(boolean value) {
		}

		@Override
		public void save() {
		}
	}
}
