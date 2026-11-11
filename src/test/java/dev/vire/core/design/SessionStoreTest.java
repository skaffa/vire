package dev.vire.core.design;

import static org.junit.jupiter.api.Assertions.*;

import dev.vire.core.region.Box;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SessionStoreTest {
	@TempDir
	Path dir;

	@Test
	void roundTripPerWorld() throws Exception {
		SessionStore store = new SessionStore(dir);
		String world = "mp:play.example.net:25565/minecraft:overworld";
		SessionStore.Saved s = new SessionStore.Saved(world, new Box(-16, 50, -16, 15, 81, 15), 123L, false, "crash",
				List.of(new SessionStore.Edit(42L, "minecraft:repeater[delay=2,facing=west]"), new SessionStore.Edit(-7L, "minecraft:air")));
		store.save(s);
		assertEquals(s, store.load(world));
		assertNull(store.load("sp:Other/minecraft:overworld"));
		assertTrue(store.file(world).getFileName().toString().matches("[a-z0-9_-]+\\.json"));

		store.save(s.withClean(true, "left"));
		assertTrue(store.load(world).clean());
		assertTrue(store.delete(world));
		assertNull(store.load(world));
	}

	@Test
	void brokenFileIsDropped() throws Exception {
		SessionStore store = new SessionStore(dir);
		Files.createDirectories(dir);
		Files.writeString(store.file("w"), "{not json");
		assertNull(store.load("w"));
		assertFalse(Files.exists(store.file("w")));
	}
}
