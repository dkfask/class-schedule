package com.classschedule.solver;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import java.util.LinkedHashSet;
import java.util.Set;

public class Room {
    @PlanningId
    private String id;
    private String name;
    private int capacity;
    private Set<String> features = new LinkedHashSet<>();
    private Set<String> unavailablePeriodCodes = new LinkedHashSet<>();

    public Room() {}

    public Room(String id, String name, int capacity) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public Set<String> getFeatures() { return features; }
    public void setFeatures(Set<String> features) {
        this.features = features == null ? new LinkedHashSet<>() : new LinkedHashSet<>(features);
    }
    public Set<String> getUnavailablePeriodCodes() { return unavailablePeriodCodes; }
    public void setUnavailablePeriodCodes(Set<String> unavailablePeriodCodes) {
        this.unavailablePeriodCodes = unavailablePeriodCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(unavailablePeriodCodes);
    }
}
