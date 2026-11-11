package dev.vire.core.probe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DelayMeterTest {
	@Test
	void firstObservationIsOnlyTheBaseline() {
		DelayMeter m = new DelayMeter();
		m.observe(100, 15, 15);
		assertEquals(-1, m.delay());
		assertEquals(-1, m.period());
	}

	@Test
	void delayOnAndOff() {
		DelayMeter m = new DelayMeter();
		m.observe(10, 0, 0);
		m.observe(11, 15, 0); // input on
		for (long t = 12; t < 23; t++) {
			m.observe(t, 15, 0);
		}
		m.observe(23, 15, 15); // output on 12 gt later
		assertEquals(12, m.delay());
		assertTrue(m.delayRising());
		m.observe(30, 0, 15); // input off
		m.observe(46, 0, 0); // output off 16 gt later
		assertEquals(16, m.delay());
		assertFalse(m.delayRising());
	}

	@Test
	void outputChangeWithoutInputChangeKeepsTheLastDelay() {
		DelayMeter m = new DelayMeter();
		m.observe(0, 0, 0);
		m.observe(1, 15, 0);
		m.observe(3, 15, 15);
		m.observe(5, 15, 0); // output flickers on its own
		assertEquals(2, m.delay());
	}

	@Test
	void newerInputChangeReplacesTheWaitingOne() {
		DelayMeter m = new DelayMeter();
		m.observe(0, 0, 0);
		m.observe(1, 15, 0);
		m.observe(2, 0, 0); // short pulse, output never reacted
		m.observe(3, 15, 0);
		m.observe(7, 15, 15);
		assertEquals(4, m.delay());
	}

	@Test
	void periodFrequencyAndOnTimeOfAClock() {
		DelayMeter m = new DelayMeter();
		m.observe(0, 0, -1);
		// on 2 gt, off 2 gt: period 4, 5 Hz
		long t = 1;
		for (int i = 0; i < 3; i++) {
			m.observe(t, 15, -1);
			m.observe(t + 2, 0, -1);
			t += 4;
		}
		assertEquals(4, m.period());
		assertEquals(5.0, m.frequency(), 1e-9);
		assertEquals(2, m.onTime());
		assertEquals(-1, m.delay(), "no output marked");
	}

	@Test
	void periodFromOutputWhenNoInput() {
		DelayMeter m = new DelayMeter();
		m.observe(0, -1, 0);
		m.observe(5, -1, 7);
		m.observe(6, -1, 0);
		m.observe(15, -1, 3);
		assertEquals(10, m.period());
		assertEquals(2.0, m.frequency(), 1e-9);
	}

	@Test
	void signalStrengthChangesAreNotEdges() {
		DelayMeter m = new DelayMeter();
		m.observe(0, 0, -1);
		m.observe(1, 15, -1);
		m.observe(2, 14, -1);
		m.observe(3, 15, -1);
		assertEquals(-1, m.period());
	}

	@Test
	void unmarkingForgetsTheSignal() {
		DelayMeter m = new DelayMeter();
		m.observe(0, 0, 0);
		m.observe(1, 15, 0);
		m.observe(2, -1, 0); // input unmarked
		m.observe(3, -1, 15);
		assertEquals(-1, m.delay());
		m.observe(4, 15, 15); // marked again: baseline only
		m.observe(5, 15, 0);
		assertEquals(-1, m.delay());
	}

	@Test
	void ticksText() {
		assertEquals("6 gt (3 rt, 0.30 s)", DelayMeter.ticks(6));
		assertEquals("5 gt (2.5 rt, 0.25 s)", DelayMeter.ticks(5));
	}
}
