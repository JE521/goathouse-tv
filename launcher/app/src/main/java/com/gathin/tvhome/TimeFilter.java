package com.gathin.tvhome;

/**
 * goathouse: the Sendspin time filter (spec "Clock Synchronization"): a 2-D Kalman filter over the clock offset
 * (server minus client, µs) and its drift. Implemented exactly as the spec's update and conversion steps.
 */
final class TimeFilter {
    private static final double PROCESS_STD_DEV = 0.0;
    private static final double DRIFT_PROCESS_STD_DEV = 1e-11;
    private static final double FORGET_FACTOR = 2.0;
    private static final double ADAPTIVE_CUTOFF = 3.0;
    private static final int MIN_SAMPLES = 100;
    private static final double DRIFT_SIGNIFICANCE = 2.0;
    private static final double MAX_ERROR_SCALE = 0.5;

    private double offset, drift, pOO = Double.POSITIVE_INFINITY, pOD, pDD;
    private long lastUpdate;
    private int count;
    private boolean useDrift;

    synchronized int count() { return count; }

    synchronized void reset() {
        offset = drift = pOD = pDD = 0;
        pOO = Double.POSITIVE_INFINITY;
        lastUpdate = 0;
        count = 0;
        useDrift = false;
    }

    /** One server/time exchange: t1 client sent, t2 server received, t3 server sent, t4 client received (µs). */
    synchronized void update(long t1, long t2, long t3, long t4) {
        long measurement = ((t2 - t1) + (t3 - t4)) / 2;
        long maxError = ((t4 - t1) - (t3 - t2)) / 2;
        update(measurement, maxError, t4);
    }

    synchronized void update(long measurement, long maxError, long timeAdded) {
        if (timeAdded <= lastUpdate || maxError <= 0) return;
        double dt = timeAdded - lastUpdate;
        lastUpdate = timeAdded;
        double r = Math.pow(MAX_ERROR_SCALE * maxError, 2);
        if (count == 0) {
            offset = measurement;
            pOO = r;
            drift = 0;
            count = 1;
            return;
        }
        if (count == 1) {
            drift = (measurement - offset) / dt;
            pDD = (pOO + r) / (dt * dt);
            offset = measurement;
            pOO = r;
            count = 2;
            return;
        }
        double offsetP = offset + drift * dt;
        double pOOp = pOO + 2 * pOD * dt + pDD * dt * dt + PROCESS_STD_DEV * PROCESS_STD_DEV * dt;
        double pODp = pOD + pDD * dt;
        double pDDp = pDD + DRIFT_PROCESS_STD_DEV * DRIFT_PROCESS_STD_DEV * dt;
        double y = measurement - offsetP;
        if (count < MIN_SAMPLES) {
            count++;
        } else if (Math.abs(y) > ADAPTIVE_CUTOFF * maxError) {
            double f2 = FORGET_FACTOR * FORGET_FACTOR;
            pOOp *= f2;
            pODp *= f2;
            pDDp *= f2;
        }
        double s = pOOp + r;
        double kO = pOOp / s, kD = pODp / s;
        offset = offsetP + kO * y;
        drift = drift + kD * y;
        pOO = pOOp - kO * pOOp;
        pOD = pODp - kD * pOOp;
        pDD = pDDp - kD * pODp;
        useDrift = drift * drift > DRIFT_SIGNIFICANCE * DRIFT_SIGNIFICANCE * pDD;
    }

    synchronized long serverTime(long clientUs) {
        double d = useDrift ? drift : 0;
        return clientUs + Math.round(offset + d * (clientUs - lastUpdate));
    }

    synchronized long clientTime(long serverUs) {
        double d = useDrift ? drift : 0;
        return Math.round((serverUs - offset + d * lastUpdate) / (1 + d));
    }

    /** Client clock for Sendspin: monotonic microseconds. */
    static long nowUs() { return System.nanoTime() / 1000L; }
}
