package dev.vire.core.design;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class EditHistoryTest {
	@Test
	void undoRedoOneStep() {
		EditHistory<String> h = new EditHistory<>(10);
		h.begin();
		h.add(1, "air", "door_lower");
		h.add(2, "air", "door_upper");
		assertTrue(h.end());

		EditHistory.Step<String> s = h.undo();
		assertEquals(List.of(new EditHistory.Change<>(2, "door_upper", "air"), new EditHistory.Change<>(1, "door_lower", "air")), s.inverse());
		assertEquals(0, h.undoSize());
		assertEquals(1, h.redoSize());

		EditHistory.Step<String> r = h.redo();
		assertSame(s, r);
		assertEquals(1, h.undoSize());
		assertNull(h.redo());
	}

	@Test
	void samePositionTwiceKeepsFirstBefore() {
		EditHistory<String> h = new EditHistory<>(10);
		h.begin();
		h.add(5, "dust_none", "dust_east");
		h.add(5, "dust_east", "dust_east_west");
		h.end();
		assertEquals(List.of(new EditHistory.Change<>(5, "dust_none", "dust_east_west")), h.undo().changes());
	}

	@Test
	void noOpStepIsDropped() {
		EditHistory<String> h = new EditHistory<>(10);
		h.begin();
		h.add(1, "stone", "air");
		h.add(1, "air", "stone");
		assertFalse(h.end());
		assertEquals(0, h.undoSize());
	}

	@Test
	void newEditClearsRedoAndLimitDropsOldest() {
		EditHistory<String> h = new EditHistory<>(2);
		for (int i = 0; i < 3; i++) {
			h.begin();
			h.add(i, "air", "s" + i);
			h.end();
		}
		assertEquals(2, h.undoSize());
		assertEquals(1, h.steps().get(0).changes().get(0).key());
		h.undo();
		assertEquals(1, h.redoSize());
		h.begin();
		h.add(9, "air", "x");
		h.end();
		assertEquals(0, h.redoSize());
	}

	@Test
	void misuseThrows() {
		EditHistory<String> h = new EditHistory<>(1);
		assertThrows(IllegalStateException.class, () -> h.add(1, "a", "b"));
		assertThrows(IllegalStateException.class, h::end);
		h.begin();
		assertThrows(IllegalStateException.class, h::begin);
	}
}
