package com.utk.speedbeep;

public final class RoadSegment {
    public final String name;
    public final double startLat;
    public final double startLon;
    public final double endLat;
    public final double endLon;
    public final double limitKph;
    public final double corridorM;
    public final double marginKph;

    public RoadSegment(String name, double startLat, double startLon,
                       double endLat, double endLon, double limitKph,
                       double corridorM, double marginKph) {
        this.name = name;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.limitKph = limitKph;
        this.corridorM = corridorM;
        this.marginKph = marginKph;
    }
}
