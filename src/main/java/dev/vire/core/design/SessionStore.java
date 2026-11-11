package dev.vire.core.design;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import dev.vire.core.region.Box;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The last Vire session per world, as {@code config/vire/sessions/<world>.json}: the region and every
 * edited position with its final state. Entering Vire Mode inside that region again continues it, and
 * a session that did not end normally (crash, kick) is offered for restore on the next join.
 */
public final class SessionStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** One edited position (packed key) and its final state as text. */
	public record Edit(long key, String state) {
	}

	/**
	 * @param clean false while the session runs and after an exit the player may not have seen (crash,
	 *              kick, world change); the next join then asks to restore it
	 */
	public record Saved(String world, Box box, long savedAt, boolean clean, String reason, List<Edit> edits) {
		public Saved withClean(boolean c, String why) {
			return new Saved(world, box, savedAt, c, why, edits);
		}
	}

	private final Path dir;

	public SessionStore(Path dir) {
		this.dir = dir;
	}

	public Path file(String world) {
		// cleaned names are cut at 48 characters: the hash keeps two long server names apart
		return dir.resolve(DesignStore.cleanName(world.replace(':', '_').replace('/', '_')) + "-" + Integer.toHexString(world.hashCode()) + ".json");
	}

	public void save(Saved s) throws IOException {
		Files.createDirectories(dir);
		Path file = file(s.world());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(s, w);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The saved session of {@code world}, or null when there is none. A broken file is deleted and gives null. */
	public Saved load(String world) throws IOException {
		Path file = file(world);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Saved s = GSON.fromJson(r, Saved.class);
			if (s != null && s.box() != null && s.edits() != null && s.world() != null) {
				return world.equals(s.world()) ? s : null;
			}
		} catch (JsonParseException | IllegalArgumentException e) {
			// fall through: a broken session file is not worth keeping
		}
		Files.deleteIfExists(file);
		return null;
	}

	public boolean delete(String world) throws IOException {
		return Files.deleteIfExists(file(world));
	}
}
