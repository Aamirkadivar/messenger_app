package models

import (
	"fmt"
	"strings"

	"gorm.io/gorm"
)

// migratePhase71 installs the two database-owned guarantees durable delivery
// rests on.
//
// 1. An ordered, gap-free, per-chat message sequence (messages.seq).
//
// A reconnecting client must be able to ask "everything after the last message
// I have" and get exactly that. Neither existing column can answer it: ids are
// random v4 UUIDs, so comparing them orders nothing, and created_at is stamped
// by the application BEFORE the INSERT commits - a message stamped earlier can
// commit later than one a reader has already paged past, and is then skipped
// for good. So the sequence is assigned by the database inside the INSERT,
// from a per-chat counter row that the INSERT locks until it commits. Within a
// chat that makes seq order equal commit order: a reader that has seen seq N has
// necessarily seen every committed seq below it.
//
// It is assigned by a trigger rather than by the send handler so that every
// writer gets one - the handler, the dev 2FA relay, a script, a manual INSERT -
// and a row without a position is unrepresentable. It is write-once, for the
// same reason a cursor that can move is no cursor at all.
//
// 2. Idempotent sends: UNIQUE (sender_id, client_message_id).
//
// NULLs never conflict, so rows from clients that send no id are unaffected.
//
// Rows that predate this phase are numbered per chat in (created_at, id) order,
// after whatever the chat already holds. The whole migration runs under a lock
// that blocks concurrent INSERTs, so no message can land between the backfill
// and the trigger taking over. Idempotent: a second run numbers nothing, raises
// no counter and recreates the same objects.
func migratePhase71(db *gorm.DB) error {
	err := db.Transaction(func(tx *gorm.DB) error {
		steps := []struct{ what, sql string }{
			{"lock messages", `LOCK TABLE messages IN SHARE ROW EXCLUSIVE MODE`},
			{"seq column", `ALTER TABLE messages ADD COLUMN IF NOT EXISTS seq BIGINT`},
			{"counter table", `CREATE TABLE IF NOT EXISTS message_chat_seqs (
				chat_id  TEXT PRIMARY KEY,
				last_seq BIGINT NOT NULL)`},
			{"backfill", `
				WITH top AS (
					SELECT chat_id, COALESCE(max(seq), 0) AS n FROM messages GROUP BY chat_id),
				numbered AS (
					SELECT m.id, top.n + row_number() OVER (
						PARTITION BY m.chat_id ORDER BY m.created_at, m.id) AS seq
					FROM messages m JOIN top ON top.chat_id = m.chat_id
					WHERE m.seq IS NULL)
				UPDATE messages SET seq = numbered.seq
				FROM numbered WHERE messages.id = numbered.id`},
			{"seed counters", `
				INSERT INTO message_chat_seqs (chat_id, last_seq)
				SELECT chat_id, max(seq) FROM messages GROUP BY chat_id
				ON CONFLICT (chat_id) DO UPDATE
					SET last_seq = GREATEST(message_chat_seqs.last_seq, EXCLUDED.last_seq)`},
			// The counter row is locked by ON CONFLICT DO UPDATE until the INSERT's
			// transaction ends, which is what serialises one chat's writers into
			// commit order. Any seq a writer supplies is overwritten.
			{"assign function", `
				CREATE OR REPLACE FUNCTION phase71_assign_message_seq() RETURNS trigger AS $$
				BEGIN
					INSERT INTO message_chat_seqs AS s (chat_id, last_seq) VALUES (NEW.chat_id, 1)
					ON CONFLICT (chat_id) DO UPDATE SET last_seq = s.last_seq + 1
					RETURNING s.last_seq INTO NEW.seq;
					RETURN NEW;
				END;
				$$ LANGUAGE plpgsql`},
			{"assign trigger drop", `DROP TRIGGER IF EXISTS trg_phase71_message_seq ON messages`},
			{"assign trigger", `CREATE TRIGGER trg_phase71_message_seq
				BEFORE INSERT ON messages
				FOR EACH ROW EXECUTE FUNCTION phase71_assign_message_seq()`},
			// NULL -> value is the backfill's one permitted transition.
			{"guard function", `
				CREATE OR REPLACE FUNCTION phase71_guard_message_seq() RETURNS trigger AS $$
				BEGIN
					IF OLD.seq IS NOT NULL AND NEW.seq IS DISTINCT FROM OLD.seq THEN
						RAISE EXCEPTION 'phase71: messages.seq is write-once (message %)', OLD.id
							USING ERRCODE = 'check_violation';
					END IF;
					RETURN NEW;
				END;
				$$ LANGUAGE plpgsql`},
			{"guard trigger drop", `DROP TRIGGER IF EXISTS trg_phase71_message_seq_guard ON messages`},
			{"guard trigger", `CREATE TRIGGER trg_phase71_message_seq_guard
				BEFORE UPDATE OF seq ON messages
				FOR EACH ROW EXECUTE FUNCTION phase71_guard_message_seq()`},
			{"seq not null", `ALTER TABLE messages ALTER COLUMN seq SET NOT NULL`},
			{"chat seq index", `CREATE UNIQUE INDEX IF NOT EXISTS ux_messages_chat_seq
				ON messages (chat_id, seq)`},
			{"client id index", `CREATE UNIQUE INDEX IF NOT EXISTS ux_messages_sender_client_msg
				ON messages (sender_id, client_message_id) WHERE client_message_id IS NOT NULL`},
		}
		for _, s := range steps {
			if err := tx.Exec(s.sql).Error; err != nil {
				return fmt.Errorf("phase71 %s: %w", s.what, err)
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	return verifyPhase71Schema(db)
}

// verifyPhase71Schema refuses to start unless every Phase 71 object exists,
// following verifyGate17Schema: a silent no-op in the guarded DDL above would
// otherwise look exactly like success, and a server without the sequence would
// hand clients a sync cursor that orders nothing.
func verifyPhase71Schema(db *gorm.DB) error {
	var missing []string
	for _, tg := range []string{"trg_phase71_message_seq", "trg_phase71_message_seq_guard"} {
		var n int64
		if err := db.Raw(`SELECT count(*) FROM pg_trigger WHERE tgname = ?`, tg).Scan(&n).Error; err != nil {
			return fmt.Errorf("phase71 verify trigger %s: %w", tg, err)
		}
		if n == 0 {
			missing = append(missing, "trigger "+tg)
		}
	}
	for _, ix := range []string{"ux_messages_chat_seq", "ux_messages_sender_client_msg"} {
		var n int64
		if err := db.Raw(`SELECT count(*) FROM pg_indexes WHERE indexname = ?`, ix).Scan(&n).Error; err != nil {
			return fmt.Errorf("phase71 verify index %s: %w", ix, err)
		}
		if n == 0 {
			missing = append(missing, "index "+ix)
		}
	}
	var nullable string
	if err := db.Raw(`SELECT is_nullable FROM information_schema.columns
		WHERE table_schema = current_schema() AND table_name = 'messages' AND column_name = 'seq'`).
		Scan(&nullable).Error; err != nil {
		return fmt.Errorf("phase71 verify seq column: %w", err)
	}
	if nullable != "NO" {
		missing = append(missing, fmt.Sprintf("messages.seq NOT NULL (is_nullable=%q)", nullable))
	}
	if len(missing) > 0 {
		return fmt.Errorf("phase71 schema incomplete, refusing to start: %s", strings.Join(missing, ", "))
	}
	return nil
}
