package dev.vire.core.design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Undo/redo of user edits in Vire Mode. One user action (a click) is one {@link Step}; a step holds
 * every position the action changed (the placed block, the other door half, neighbor shape updates).
 *
 * @param <S> block state type
 */
public final class EditHistory<S> {
	/** One changed position. */
	public record Change<S>(long key, S before, S after) {
	}

	/** All changes of one user action, in the order they happened. */
	public record Step<S>(List<Change<S>> changes) {
		public Step {
			changes = List.copyOf(changes);
		}

		/** Changes to apply for undo: reverse order, each going back to {@code before}. */
		public List<Change<S>> inverse() {
			List<Change<S>> out = new ArrayList<>(changes.size());
			for (int i = changes.size() - 1; i >= 0; i--) {
				Change<S> c = changes.get(i);
				out.add(new Change<>(c.key(), c.after(), c.before()));
			}
			return out;
		}
	}

	private final int limit;
	private final Deque<Step<S>> undo = new ArrayDeque<>();
	private final Deque<Step<S>> redo = new ArrayDeque<>();
	private Map<Long, Change<S>> open;

	public EditHistory(int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit");
		}
		this.limit = limit;
	}

	/** Starts collecting changes for one user action. */
	public void begin() {
		if (open != null) {
			throw new IllegalStateException("step already open");
		}
		open = new LinkedHashMap<>();
	}

	public boolean isOpen() {
		return open != null;
	}

	/** Adds one change to the open step. A position changed twice keeps its first {@code before}. */
	public void add(long key, S before, S after) {
		if (open == null) {
			throw new IllegalStateException("no open step");
		}
		Change<S> prev = open.get(key);
		open.put(key, new Change<>(key, prev == null ? before : prev.before(), after));
	}

	/** Closes the open step. Returns {@code false} (and records nothing) when nothing really changed. */
	public boolean end() {
		if (open == null) {
			throw new IllegalStateException("no open step");
		}
		List<Change<S>> real = new ArrayList<>();
		for (Change<S> c : open.values()) {
			if (!Objects.equals(c.before(), c.after())) {
				real.add(c);
			}
		}
		open = null;
		if (real.isEmpty()) {
			return false;
		}
		undo.push(new Step<>(real));
		while (undo.size() > limit) {
			undo.removeLast();
		}
		redo.clear();
		return true;
	}

	/** Pops the last step; the caller applies {@link Step#inverse()}. {@code null} when nothing to undo. */
	public Step<S> undo() {
		Step<S> s = undo.poll();
		if (s != null) {
			redo.push(s);
		}
		return s;
	}

	/** Re-does the last undone step; the caller applies {@link Step#changes()}. */
	public Step<S> redo() {
		Step<S> s = redo.poll();
		if (s != null) {
			undo.push(s);
		}
		return s;
	}

	public int undoSize() {
		return undo.size();
	}

	public int redoSize() {
		return redo.size();
	}

	/** Steps from oldest to newest. */
	public List<Step<S>> steps() {
		List<Step<S>> out = new ArrayList<>(undo);
		Collections.reverse(out);
		return out;
	}

	public void clear() {
		undo.clear();
		redo.clear();
		open = null;
	}
}
