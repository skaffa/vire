package dev.vire.core.design;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Designs as {@code config/vire/designs/<name>.json} (PLAN.md §9). */
public final class DesignStore {
	public static final String AUTOSAVE = "_autosave";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path dir;

	public DesignStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return dir;
	}

	/** Lower case letters, digits, {@code _} and {@code -}; other characters become {@code _}. Never empty. */
	public static String cleanName(String name) {
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
		if (n.length() > 48) {
			n = n.substring(0, 48);
		}
		return n.isEmpty() ? "design" : n;
	}

	public Path file(String name) {
		return dir.resolve(cleanName(name) + ".json");
	}

	public void save(Design d) throws IOException {
		Files.createDirectories(dir);
		Path file = file(d.name());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(d, w);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The design, or null when there is no such file. Throws when the file is broken. */
	public Design load(String name) throws IOException {
		Path file = file(name);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Design d = GSON.fromJson(r, Design.class);
			if (d == null || d.entries() == null) {
				throw new IOException("Empty design file " + file);
			}
			return d;
		} catch (JsonParseException e) {
			throw new IOException("Broken design file " + file, e);
		}
	}

	public boolean delete(String name) throws IOException {
		return Files.deleteIfExists(file(name));
	}

	/** Names of the saved designs, sorted. */
	public List<String> list() throws IOException {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : ds) {
				String f = p.getFileName().toString();
				out.add(f.substring(0, f.length() - 5));
			}
		}
		out.sort(null);
		return out;
	}

	/** {@code base}, or {@code base-2}, {@code base-3}… whichever is free. */
	public String freeName(String base) throws IOException {
		String b = cleanName(base);
		if (!Files.exists(file(b))) {
			return b;
		}
		for (int i = 2; ; i++) {
			if (!Files.exists(file(b + "-" + i))) {
				return b + "-" + i;
			}
		}
	}
}
