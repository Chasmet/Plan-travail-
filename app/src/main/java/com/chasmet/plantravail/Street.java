package com.chasmet.plantravail;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

public class Street {
    private final String name;
    private final List<GeoPoint> points;

    public Street(String name, List<GeoPoint> points) {
        this.name = name;
        this.points = new ArrayList<>(points);
    }

    public String getName() {
        return name;
    }

    public List<GeoPoint> getPoints() {
        return new ArrayList<>(points);
    }
}
