package dev.vire.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Loads and saves {@link VireConfig} inside the mod's own folder ({@code config/vire/}).
 * Minecraft-free so it can be unit tested. A file that cannot be parsed is kept as
 * {@code config.json.broken-<time>} and defaults are used (PLAN.md §13).
 */
public final class ConfigStore {
	public static final String FILE_NAME = "config.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final Path dir;
	private final Clock clock;
	/** Set by {@link #load()} when a broken file was moved aside; the client tells the user once. */
	private Path lastBackup;

	public ConfigStore(Path dir) {
		this(dir, Clock.systemDefaultZone());
	}

	ConfigStore(Path dir, Clock clock) {
		this.dir = dir;
		this.clock = clock;
	}

	public Path dir() {
		return dir;
	}

	public Path file() {
		return dir.resolve(FILE_NAME);
	}

	/** Path of the broken file that the last {@link #load()} moved aside, or {@code null}. */
	public Path lastBackup() {
		return lastBackup;
	}

	/** Reads the config; a missing file gives defaults (and writes them), a broken one is backed up. */
	public VireConfig load() throws IOException {
		lastBackup = null;
		Path file = file();
		if (!Files.isRegularFile(file)) {
			VireConfig fresh = new VireConfig();
			save(fresh);
			return fresh;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			VireConfig c = GSON.fromJson(r, VireConfig.class);
			if (c == null) {
				throw new JsonParseException("empty file");
			}
			return c.clamp();
		} catch (JsonParseException | IllegalStateException e) {
			lastBackup = dir.resolve(FILE_NAME + ".broken-" + LocalDateTime.now(clock).format(STAMP));
			Files.move(file, lastBackup, StandardCopyOption.REPLACE_EXISTING);
			VireConfig fresh = new VireConfig();
			save(fresh);
			return fresh;
		}
	}

	/** Writes the config through a temp file so a crash never leaves half a file. */
	public void save(VireConfig config) throws IOException {
		Files.createDirectories(dir);
		Path tmp = dir.resolve(FILE_NAME + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(config.clamp(), w);
		}
		try {
			Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
