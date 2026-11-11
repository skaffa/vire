package dev.vire.core.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class ConfigStoreTest {
	@TempDir
	Path tmp;

	private ConfigStore store() {
		Clock fixed = Clock.fixed(Instant.parse("2026-11-11T10:20:30Z"), ZoneOffset.UTC);
		return new ConfigStore(tmp.resolve("vire"), fixed);
	}

	@Test
	void missingFileGivesDefaultsAndCreatesFolder() throws Exception {
		ConfigStore s = store();
		VireConfig c = s.load();
		assertEquals(32, c.regionSize);
		assertTrue(Files.isRegularFile(tmp.resolve("vire").resolve("config.json")));
		assertNull(s.lastBackup());
	}

	@Test
	void roundTrip() throws Exception {
		ConfigStore s = store();
		VireConfig c = new VireConfig();
		c.regionSize = 48;
		c.keepWarm = true;
		s.save(c);
		VireConfig back = s.load();
		assertEquals(48, back.regionSize);
		assertTrue(back.keepWarm);
	}

	@Test
	void outOfRangeValuesAreClamped() throws Exception {
		ConfigStore s = store();
		Files.createDirectories(s.dir());
		Files.writeString(s.file(), "{\"regionSize\": 500, \"reach\": 1, \"cameraSpeed\": -3}", StandardCharsets.UTF_8);
		VireConfig c = s.load();
		assertEquals(64, c.regionSize);
		assertEquals(8, c.reach);
		assertEquals(0.1, c.cameraSpeed, 1e-9);
	}

	@Test
	void brokenFileIsBackedUpAndDefaultsUsed() throws Exception {
		ConfigStore s = store();
		Files.createDirectories(s.dir());
		Files.writeString(s.file(), "{ this is not json", StandardCharsets.UTF_8);
		VireConfig c = s.load();
		assertEquals(32, c.regionSize);
		Path backup = s.dir().resolve("config.json.broken-20261111-102030");
		assertEquals(backup, s.lastBackup());
		assertEquals("{ this is not json", Files.readString(backup, StandardCharsets.UTF_8));
		assertTrue(Files.isRegularFile(s.file()));
	}

	@Test
	void emptyFileCountsAsBroken() throws Exception {
		ConfigStore s = store();
		Files.createDirectories(s.dir());
		Files.writeString(s.file(), "", StandardCharsets.UTF_8);
		s.load();
		assertNotNull(s.lastBackup());
	}
}
