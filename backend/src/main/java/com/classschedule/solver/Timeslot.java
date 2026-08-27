package com.classschedule.solver;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

public class Timeslot {
    @PlanningId
    private String id;
    private int weekday;
    private int period;
    private String label;
    private String continuityGroup;
    private boolean breakAfter;
    public Timeslot() {}

    public Timeslot(String id, int weekday, int period, String label) {
        this.id = id;
        this.weekday = weekday;
        this.period = period;
        this.label = label;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getWeekday() { return weekday; }
    public void setWeekday(int weekday) { this.weekday = weekday; }
    public int getPeriod() { return period; }
    public void setPeriod(int period) { this.period = period; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getContinuityGroup() { return continuityGroup; }
    public void setContinuityGroup(String continuityGroup) { this.continuityGroup = continuityGroup; }
    public boolean isBreakAfter() { return breakAfter; }
    public void setBreakAfter(boolean breakAfter) { this.breakAfter = breakAfter; }
}
