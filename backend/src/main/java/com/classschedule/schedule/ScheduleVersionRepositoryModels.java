package com.classschedule.schedule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ScheduleVersionRepositoryModels {
    private ScheduleVersionRepositoryModels() {}

    public record VersionSummary(
            long id,
            String status,
            String score,
            boolean publishable,
            Long parentVersionId,
            OffsetDateTime createdAt,
            long revision,
            OffsetDateTime updatedAt,
            OffsetDateTime archivedAt,
            boolean editLocked,
            String editLockOwner) {
        public Integer hardScore() {
            return ScheduleScoreView.parse(score).hardScore();
        }

        public Integer mediumScore() {
            return ScheduleScoreView.parse(score).mediumScore();
        }

        public Integer softScore() {
            return ScheduleScoreView.parse(score).softScore();
        }

        public boolean scoreValid() {
            return ScheduleScoreView.parse(score).valid();
        }

        public VersionSummary(
                long id,
                String status,
                String score,
                boolean publishable,
                Long parentVersionId,
                OffsetDateTime createdAt) {
            this(
                    id,
                    status,
                    score,
                    publishable,
                    parentVersionId,
                    createdAt,
                    0L,
                    null,
                    null,
                    false,
                    null);
        }
    }

    public record VersionPage(List<VersionSummary> items, int page, int size, long total) {}

    public record DiffItem(
            String changeType,
            String occurrenceKey,
            ScheduleAssignmentView before,
            ScheduleAssignmentView after) {}

    public record MutationResult(UUID groupId, List<Long> commandIds, long revision) {}

    public record CommandHistory(
            UUID groupId,
            String commandType,
            long baseRevision,
            long resultRevision,
            String state,
            String idempotencyKey,
            String actor,
            String reason,
            OffsetDateTime createdAt,
            List<CommandItem> commands) {}

    public record CommandItem(
            long commandId,
            int sequence,
            long occurrenceId,
            String fromTimeslotCode,
            String toTimeslotCode,
            String fromRoomCode,
            String toRoomCode,
            String fromSource,
            String toSource,
            Boolean fromLocked,
            Boolean toLocked) {}
}
