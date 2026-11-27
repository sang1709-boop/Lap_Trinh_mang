package vn.ktd.taskhub.server.feature.progress;

/** A visible tree stops at a cancelled ancestor. Multi-stage tasks awaiting children are never leaves. */
public final class TaskState {
    private TaskState() {}

    public static final String LIVE = """
        WITH RECURSIVE state AS (
            SELECT t.task_id,t.group_id,t.title,t.parent_task_id,t.task_type,t.task_status,
                t.estimated_hours,t.deadline,t.round_no,t.deadline_version,t.deleted_at IS NOT NULL AS hidden,
                a.assignee_user_id AS owner,e.review_status AS review_state,e.submitted_at,
                COALESCE(e.self_review,0) AS self_review,t.completed_at AS closed_at,
                (e.review_status='approved' AND e.submitted_at>t.deadline) AS late_at_close
            FROM tasks t LEFT JOIN task_assignments a ON a.current_task_key=t.task_id
            LEFT JOIN evidence_submissions e ON e.submission_id=(
                SELECT s.submission_id FROM evidence_submissions s WHERE s.assignment_id=a.assignment_id
                AND s.round_no=t.round_no ORDER BY s.version_no DESC LIMIT 1)
        )
        """;

    public static final String HISTORY = """
        WITH RECURSIVE versions AS (
            SELECT r.*,ROW_NUMBER() OVER(PARTITION BY r.task_id ORDER BY r.effective_at DESC,r.revision_id DESC) AS seq
            FROM task_revisions r JOIN tasks t USING(task_id) WHERE t.group_id=? AND r.effective_at<?
        ), state AS (
            SELECT r.task_id,t.group_id,t.title,r.parent_task_id,r.task_type,r.task_status,r.estimated_hours,
                r.deadline,r.round_no,r.deadline_version,r.hidden,a.assignee_user_id AS owner,
                r.review_state,r.submitted_at,r.self_review,r.closed_at,r.late_at_close
            FROM versions r JOIN tasks t USING(task_id) JOIN task_assignments a USING(assignment_id)
            WHERE r.seq=1
        )
        """;

    public static final String TREE = """
        , visible(task_id) AS (
            SELECT task_id FROM state WHERE parent_task_id IS NULL AND hidden=0
            UNION ALL
            SELECT c.task_id FROM visible v JOIN state p ON p.task_id=v.task_id
                JOIN state c ON c.parent_task_id=p.task_id WHERE c.hidden=0 AND p.task_status<>'cancelled'
        ), items AS (
            SELECT s.*, (s.task_type='simple' AND NOT EXISTS (
                SELECT 1 FROM state c WHERE c.parent_task_id=s.task_id)) AS leaf,
                (s.task_status='in_progress' AND EXISTS (SELECT 1 FROM state c WHERE c.parent_task_id=s.task_id
                    AND c.hidden=0 AND c.task_status='completed') AND NOT EXISTS (
                    SELECT 1 FROM state c WHERE c.parent_task_id=s.task_id AND c.hidden=0
                    AND c.task_status IN ('not_started','in_progress'))) AS ready
            FROM state s JOIN visible v USING(task_id)
        )
        """;
}
