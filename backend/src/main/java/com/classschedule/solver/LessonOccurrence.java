package com.classschedule.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import java.util.LinkedHashSet;
import java.util.Set;

@PlanningEntity
public class LessonOccurrence {
    @PlanningId
    private Long id;
    private String subjectCode;
    private String subjectName;
    private String teacherCode;
    private String teacherName;
    private String studentGroupCode;
    private String studentGroupName;
    private int duration;
    private int studentCount;
    private Long teachingRequirementId;
    private String requirementCode;
    private String occurrenceKey;
    private String activityGroupCode;
    private String activityType;
    private String pinnedPeriodCode;
    private int activityIndex;
    private int activityMemberIndex = -1;
    private Set<String> requiredFeatures = new LinkedHashSet<>();
    private Set<String> unavailablePeriodCodes = new LinkedHashSet<>();
    private Set<String> availablePeriodCodes = new LinkedHashSet<>();
    private Set<String> breakAfterPeriodCodes = new LinkedHashSet<>();
    private java.util.Map<String, String> nextPeriodCodes = new java.util.LinkedHashMap<>();
    @PlanningPin
    private boolean pinned;

    @PlanningVariable(valueRangeProviderRefs = "timeslotRange")
    private Timeslot timeslot;

    @PlanningVariable(valueRangeProviderRefs = "roomRange")
    private Room room;

    public LessonOccurrence() {}

    public LessonOccurrence(Long id, String subjectCode, String subjectName, String teacherCode,
            String teacherName, String studentGroupCode, String studentGroupName) {
        this.id = id;
        this.subjectCode = subjectCode;
        this.subjectName = subjectName;
        this.teacherCode = teacherCode;
        this.teacherName = teacherName;
        this.studentGroupCode = studentGroupCode;
        this.studentGroupName = studentGroupName;
        this.duration = 1;
    }

    public LessonOccurrence copy() {
        LessonOccurrence copy = new LessonOccurrence(id, subjectCode, subjectName, teacherCode,
                teacherName, studentGroupCode, studentGroupName);
        copy.duration = duration;
        copy.studentCount = studentCount;
        copy.teachingRequirementId = teachingRequirementId;
        copy.requirementCode = requirementCode;
        copy.occurrenceKey = occurrenceKey;
        copy.activityGroupCode = activityGroupCode;
        copy.activityType = activityType;
        copy.pinnedPeriodCode = pinnedPeriodCode;
        copy.activityIndex = activityIndex;
        copy.activityMemberIndex = activityMemberIndex;
        copy.requiredFeatures = new LinkedHashSet<>(requiredFeatures);
        copy.unavailablePeriodCodes = new LinkedHashSet<>(unavailablePeriodCodes);
        copy.availablePeriodCodes = new LinkedHashSet<>(availablePeriodCodes);
        copy.breakAfterPeriodCodes = new LinkedHashSet<>(breakAfterPeriodCodes);
        copy.nextPeriodCodes = new java.util.LinkedHashMap<>(nextPeriodCodes);
        copy.pinned = pinned;
        copy.timeslot = timeslot;
        copy.room = room;
        return copy;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public String getTeacherCode() { return teacherCode; }
    public void setTeacherCode(String teacherCode) { this.teacherCode = teacherCode; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    public String getStudentGroupCode() { return studentGroupCode; }
    public void setStudentGroupCode(String studentGroupCode) { this.studentGroupCode = studentGroupCode; }
    public String getStudentGroupName() { return studentGroupName; }
    public void setStudentGroupName(String studentGroupName) { this.studentGroupName = studentGroupName; }
    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }
    public int getStudentCount() { return studentCount; }
    public void setStudentCount(int studentCount) { this.studentCount = studentCount; }
    public Long getTeachingRequirementId() { return teachingRequirementId; }
    public void setTeachingRequirementId(Long teachingRequirementId) { this.teachingRequirementId = teachingRequirementId; }
    public String getRequirementCode() { return requirementCode; }
    public void setRequirementCode(String requirementCode) { this.requirementCode = requirementCode; }
    public String getOccurrenceKey() { return occurrenceKey == null ? String.valueOf(id) : occurrenceKey; }
    public boolean hasExplicitOccurrenceKey() { return occurrenceKey != null && !occurrenceKey.isBlank(); }
    public void setOccurrenceKey(String occurrenceKey) { this.occurrenceKey = occurrenceKey; }
    public String getActivityGroupCode() { return activityGroupCode; }
    public void setActivityGroupCode(String activityGroupCode) { this.activityGroupCode = activityGroupCode; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getPinnedPeriodCode() { return pinnedPeriodCode; }
    public void setPinnedPeriodCode(String pinnedPeriodCode) { this.pinnedPeriodCode = pinnedPeriodCode; }
    public int getActivityIndex() { return activityIndex; }
    public void setActivityIndex(int activityIndex) { this.activityIndex = activityIndex; }
    public int getActivityMemberIndex() { return activityMemberIndex; }
    public void setActivityMemberIndex(int activityMemberIndex) { this.activityMemberIndex = activityMemberIndex; }
    public Set<String> getRequiredFeatures() { return requiredFeatures; }
    public void setRequiredFeatures(Set<String> requiredFeatures) { this.requiredFeatures = requiredFeatures == null ? new LinkedHashSet<>() : new LinkedHashSet<>(requiredFeatures); }
    public Set<String> getUnavailablePeriodCodes() { return unavailablePeriodCodes; }
    public void setUnavailablePeriodCodes(Set<String> unavailablePeriodCodes) { this.unavailablePeriodCodes = unavailablePeriodCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(unavailablePeriodCodes); }
    public Set<String> getAvailablePeriodCodes() { return availablePeriodCodes; }
    public void setAvailablePeriodCodes(Set<String> availablePeriodCodes) { this.availablePeriodCodes = availablePeriodCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(availablePeriodCodes); }
    public Set<String> getBreakAfterPeriodCodes() { return breakAfterPeriodCodes; }
    public void setBreakAfterPeriodCodes(Set<String> breakAfterPeriodCodes) { this.breakAfterPeriodCodes = breakAfterPeriodCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(breakAfterPeriodCodes); }
    public java.util.Map<String, String> getNextPeriodCodes() { return nextPeriodCodes; }
    public void setNextPeriodCodes(java.util.Map<String, String> nextPeriodCodes) { this.nextPeriodCodes = nextPeriodCodes == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(nextPeriodCodes); }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public Timeslot getTimeslot() { return timeslot; }
    public void setTimeslot(Timeslot timeslot) { this.timeslot = timeslot; }
    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }
}
