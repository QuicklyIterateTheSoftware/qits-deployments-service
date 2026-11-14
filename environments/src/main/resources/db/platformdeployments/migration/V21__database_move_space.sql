-- WHAT A DATABASE MOVE MEASURED BEFORE ANYTHING STOPPED (qits-2243).
--
-- A same-server move of a 19 GB database onto a filesystem with 42 GB free filled the node's disk:
-- the dump in the move's volume and the restored copy in the data directory landed on the one
-- filesystem postgres itself runs on, and every service on that postgres went down. A move now
-- measures first (`DatabaseMoves`, `MoveSpace`) and is REFUSED when it does not fit — and the row
-- records what it measured, so a refusal and a started move alike say why:
--
--   source_bytes       pg_database_size of the source, on the source server
--   target_free_bytes  free bytes of the target server's data-directory filesystem (its own df)
--   volume_free_bytes  free bytes of the filesystem the move's volume `qits-dbmove-<id>` is on
--
-- Nullable, and NO BACKFILL: a row from before this column measured nothing, and a measure that
-- failed (which refuses the move) leaves its column null.
alter table pd_database_move add column source_bytes bigint;
alter table pd_database_move add column target_free_bytes bigint;
alter table pd_database_move add column volume_free_bytes bigint;
