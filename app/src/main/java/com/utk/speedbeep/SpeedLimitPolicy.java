package com.utk.speedbeep;

/**
 * Pure policy for deciding which configured limit triggers one overspeed alarm.
 * Universal is an independent absolute ceiling, without the road/fallback
 * warning margin. The more restrictive road or fallback threshold still wins.
 * No new legal speed limit is inferred from a universal personal alert.
 */
public final class SpeedLimitPolicy {
    private SpeedLimitPolicy() {}

    public enum Source { NONE, ROAD, FALLBACK, UNIVERSAL }

    public static final class Decision {
        public final Source source;
        /** Displayed speed limit in km/h, before any road-specific margin. */
        public final double limitKph;
        /** Exact speed above which the overspeed timer runs. */
        public final double thresholdKph;

        private Decision(Source source, double limitKph, double thresholdKph) {
            this.source = source;
            this.limitKph = limitKph;
            this.thresholdKph = thresholdKph;
        }

        public boolean isOver(double speedKph) {
            return source != Source.NONE && Double.isFinite(speedKph)
                    && speedKph > thresholdKph;
        }

        public boolean sameAlarmAs(Decision other) {
            return other != null && source == other.source
                    && Double.compare(thresholdKph, other.thresholdKph) == 0;
        }
    }

    public static Decision none() {
        return new Decision(Source.NONE, 0, 0);
    }

    /**
     * @param roadLimit nullable; null means no mapped road matched
     * @param universalLimit unverified personal speed ceiling, active everywhere
     */
    public static Decision select(Double roadLimit, double roadMargin,
                                  boolean fallbackEnabled, double fallbackLimit,
                                  double fallbackMargin,
                                  boolean universalEnabled, double universalLimit) {
        Decision chosen = none();
        if (roadLimit != null && valid(roadLimit)) {
            chosen = new Decision(Source.ROAD, roadLimit,
                    roadLimit + margin(roadMargin));
        } else if (fallbackEnabled && valid(fallbackLimit)) {
            chosen = new Decision(Source.FALLBACK, fallbackLimit,
                    fallbackLimit + margin(fallbackMargin));
        }
        // The universal ceiling has NO inherited corridor or margin. It can
        // never disable a stricter mapped or fallback threshold.
        if (universalEnabled && valid(universalLimit)
                && (chosen.source == Source.NONE
                || universalLimit < chosen.thresholdKph)) {
            chosen = new Decision(Source.UNIVERSAL, universalLimit, universalLimit);
        }
        return chosen;
    }

    private static boolean valid(double v) {
        return Double.isFinite(v) && v >= 5 && v <= 200;
    }

    private static double margin(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(50, value)) : 0;
    }
}
